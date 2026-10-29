/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.ai.context;

import org.dinky.data.model.Task;
import org.dinky.service.TaskService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 命名名册服务（AI Chat FlinkSQL 上下文 P0）。
 *
 * <p><b>为什么需要</b>：FlinkSQL 作业有三个「必须逐作业唯一」的标识——{@code pipeline.name}、
 * Kafka {@code properties.group.id}、Doris {@code sink.label-prefix}。历史上复用 group.id 导致两个作业
 * 瓜分同一消费组分区、数据偏小（见 {@code jobs/device-top5/README.md} §消费组必须独立）。AI 在「生成作业」
 * 或「答疑」时需要知道这些名字是否已被占用，否则会给出注定出错的建议。
 *
 * <p><b>只扫 {@code dinky_task}</b>（P0 决策 D4）：作业 SQL 文件目录 {@code jobs/} 位于中控服务器 manager，
 * 并未同步进 Dinky 容器，扫描不到；容器内唯一权威来源就是 {@code dinky_task.statement}。
 *
 * <p><b>租户范围</b>：{@code dinky_task} 在租户过滤白名单内（{@code MybatisPlusConfig}），因此本服务
 * <b>只在本租户内</b>统计——跨租户不可见，AI 上下文里需说明该口径。
 *
 * <p><b>缓存</b>：进程内快照 + 60s TTL（名册变动不频繁，避免每次会话全表扫描 {@code mediumtext}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NameRegistryService {

    /** pipeline.name / group.id / label-prefix / table.identifier / topic 的抽取正则（先引号形态，再裸值形态）。 */
    private static final Map<String, List<Pattern>> PATTERNS = new LinkedHashMap<>();

    /** 名册种类 → 展示名（顺序即输出顺序）。 */
    private static final Map<String, String> KIND_LABEL = new LinkedHashMap<>();

    static {
        addKind("pipelineName", "pipeline.name", "pipeline\\.name");
        addKind("kafkaGroupId", "group.id (kafka)", "properties\\.group\\.id");
        addKind("dorisLabelPrefix", "sink.label-prefix (doris)", "sink\\.label-prefix");
        addKind("dorisTargetTable", "table.identifier (doris)", "table\\.identifier");
        addKind("kafkaTopic", "topic (kafka)", "topic");
    }

    private static void addKind(String kind, String label, String keyRegex) {
        KIND_LABEL.put(kind, label);
        PATTERNS.put(
                kind,
                Arrays.asList(
                        Pattern.compile("'" + keyRegex + "'\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE),
                        Pattern.compile(
                                "\\b" + keyRegex + "\\s*=\\s*'?([A-Za-z0-9_.\\-]+)'?", Pattern.CASE_INSENSITIVE)));
    }

    /**
     * canal-json 血缘抽取（P1）：{@code 'canal-json.database.include'} / {@code 'canal-json.table.include'}。
     *
     * <p><b>为什么不进上面那份"名册"</b>：名册输出的是"必须逐作业唯一的名字"（pipeline.name / group.id /
     * label-prefix …），而这两个是"作业消费的源位置"，不是需要避让的名字；混进名册既污染 P0 的输出口径，
     * 也会让模型误以为它们是"已占用名"。它们只在 {@code @topic/} 的"topic → 源库表"血缘里使用。
     */
    private static final Pattern CANAL_DATABASE =
            Pattern.compile("'canal-json\\.database\\.include'\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANAL_TABLE =
            Pattern.compile("'canal-json\\.table\\.include'\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE);

    private static final long TTL_MS = 60_000L;

    private final TaskService taskService;

    private volatile Snapshot snapshot;
    private volatile long snapshotAt = 0L;

    /**
     * 取名册快照（带 60s 内存缓存）。
     *
     * @return 名册快照；查询失败时返回空快照（不抛异常，避免阻塞 AI 请求）
     */
    public Snapshot getSnapshot() {
        Snapshot cached = snapshot;
        if (cached != null && System.currentTimeMillis() - snapshotAt < TTL_MS) {
            return cached;
        }
        synchronized (this) {
            if (snapshot != null && System.currentTimeMillis() - snapshotAt < TTL_MS) {
                return snapshot;
            }
            Snapshot fresh = load();
            snapshot = fresh;
            snapshotAt = System.currentTimeMillis();
            return fresh;
        }
    }

    private Snapshot load() {
        Map<String, Set<String>> collected = new LinkedHashMap<>();
        KIND_LABEL.keySet().forEach(kind -> collected.put(kind, new LinkedHashSet<>()));
        List<TaskLink> links = new ArrayList<>();
        int taskCount = 0;
        try {
            List<Task> tasks = taskService.list(new LambdaQueryWrapper<Task>()
                    .select(Task::getId, Task::getName, Task::getDialect, Task::getStatement)
                    .in(Task::getDialect, FLINK_SQL, FLINK_SQL_ENV));
            if (tasks != null) {
                for (Task task : tasks) {
                    String statement = task.getStatement();
                    if (statement == null || statement.isEmpty()) {
                        continue;
                    }
                    taskCount++;
                    Map<String, String> taskValues = new LinkedHashMap<>();
                    for (Map.Entry<String, List<Pattern>> entry : PATTERNS.entrySet()) {
                        String value = extract(statement, entry.getValue());
                        if (value != null && !value.isEmpty()) {
                            collected.get(entry.getKey()).add(value);
                            taskValues.put(entry.getKey(), value);
                        }
                    }
                    // P1：canal 血缘（不参与名册输出，只用于 @topic/ 的 topic ↔ 源库表映射）
                    taskValues.put("canalDatabase", extract(statement, CANAL_DATABASE));
                    taskValues.put("canalTable", extract(statement, CANAL_TABLE));
                    if (taskValues.get("kafkaTopic") != null
                            || taskValues.get("canalDatabase") != null
                            || taskValues.get("canalTable") != null) {
                        links.add(new TaskLink(
                                task.getId(),
                                task.getName(),
                                taskValues.get("kafkaTopic"),
                                taskValues.get("kafkaGroupId"),
                                taskValues.get("canalDatabase"),
                                taskValues.get("canalTable")));
                    }
                }
            }
        } catch (Exception e) {
            // 名册属"锦上添花"上下文：失败不阻塞 AI 请求
            log.warn("Build name registry failed, return empty snapshot", e);
            return new Snapshot(taskCount, Collections.emptyMap(), Collections.emptyList());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        collected.forEach((kind, values) -> result.put(kind, new ArrayList<>(values)));
        return new Snapshot(taskCount, result, links);
    }

    /** 单模式抽取（P1 的 canal 血缘不需要"引号形态 + 裸值形态"两套模式） */
    private static String extract(String statement, Pattern pattern) {
        Matcher matcher = pattern.matcher(statement);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extract(String statement, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(statement);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** 名册快照。 */
    @Getter
    public static class Snapshot {

        /** 参与统计的 FlinkSql / FlinkSqlEnv 任务数。 */
        private final int taskCount;

        /** 种类 → 已占用的值（去重、保序）。 */
        private final Map<String, List<String>> valuesByKind;

        /**
         * P1：逐作业的血缘快照（topic ↔ canal 源库表 ↔ group.id）。
         *
         * <p>与 {@code valuesByKind} 的区别：后者是"按种类去重的名字集合"（适合回答"这个名字被占用了吗"），
         * 这里保留<b>作业维度的对应关系</b>——{@code @topic/<名>} 要回答的是"该 topic 被哪些作业消费、
         * 对应哪张源表、已用了哪些 group.id"，去重后就答不出来了。
         */
        private final List<TaskLink> taskLinks;

        public Snapshot(int taskCount, Map<String, List<String>> valuesByKind, List<TaskLink> taskLinks) {
            this.taskCount = taskCount;
            this.valuesByKind = valuesByKind;
            this.taskLinks = taskLinks == null ? Collections.emptyList() : taskLinks;
        }

        /** P1：消费该 topic 的作业血缘（忽略大小写；无匹配返回空列表）。 */
        public List<TaskLink> linksOfTopic(String topic) {
            List<TaskLink> matched = new ArrayList<>();
            if (topic == null) {
                return matched;
            }
            for (TaskLink link : taskLinks) {
                if (link.getTopic() != null && link.getTopic().equalsIgnoreCase(topic)) {
                    matched.add(link);
                }
            }
            return matched;
        }

        /**
         * P1：判断某个名字是否已被占用（供 {@code check_name_conflict} 工具）。
         *
         * @param kind 名册种类（见 {@link #kinds()}）
         * @param value 待检查的名字
         * @return 冲突的已占用值（通常 0 或 1 个；仅大小写不同的同名会给出多个）
         */
        public List<String> conflictsOf(String kind, String value) {
            List<String> conflicts = new ArrayList<>();
            if (kind == null || value == null || value.isEmpty()) {
                return conflicts;
            }
            for (String occupied : get(kind)) {
                if (occupied != null && occupied.equalsIgnoreCase(value.trim())) {
                    conflicts.add(occupied);
                }
            }
            return conflicts;
        }

        public boolean isEmpty() {
            return valuesByKind.values().stream().noneMatch(v -> v != null && !v.isEmpty());
        }

        /** @return 该种类已占用的值（可能为空列表） */
        public List<String> get(String kind) {
            List<String> values = valuesByKind.get(kind);
            return values == null ? Collections.emptyList() : values;
        }

        /** @return 所有已占用的值（跨种类，用于"这个名字是否被任何作业占用"的粗判） */
        public Collection<String> all() {
            Set<String> all = new LinkedHashSet<>();
            valuesByKind.values().forEach(v -> {
                if (v != null) {
                    all.addAll(v);
                }
            });
            return all;
        }

        public static String kindLabel(String kind) {
            return KIND_LABEL.getOrDefault(kind, kind);
        }

        public static Collection<String> kinds() {
            return KIND_LABEL.keySet();
        }
    }

    /** P1：单个作业的血缘（topic ↔ canal 源库表 ↔ group.id）。 */
    @Getter
    public static class TaskLink {

        private final Integer taskId;
        private final String taskName;
        private final String topic;
        private final String kafkaGroupId;
        private final String canalDatabase;
        private final String canalTable;

        public TaskLink(
                Integer taskId,
                String taskName,
                String topic,
                String kafkaGroupId,
                String canalDatabase,
                String canalTable) {
            this.taskId = taskId;
            this.taskName = taskName;
            this.topic = topic;
            this.kafkaGroupId = kafkaGroupId;
            this.canalDatabase = canalDatabase;
            this.canalTable = canalTable;
        }

        /** 血缘展示串 {@code 源库.源表}；无血缘信息时返回 {@code null}。 */
        public String sourceTablePath() {
            if (canalDatabase == null || canalTable == null) {
                return null;
            }
            return canalDatabase + "." + canalTable;
        }
    }

    private static final String FLINK_SQL = "FlinkSql";
    private static final String FLINK_SQL_ENV = "FlinkSqlEnv";
}
