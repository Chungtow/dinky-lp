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

package org.dinky.ai.mention;

import org.dinky.ai.context.NameRegistryService;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.DataBaseService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @topic/<名>} 引用的渲染器——<b>零连接版</b>（AI Chat FlinkSQL 上下文 P1 批次 1）。
 *
 * <p><b>为什么不连 Kafka</b>：生成 FlinkSQL 作业真正需要的是「这个 topic 的字段是什么、group.id 该怎么起名」，
 * 而这两件事都能从<b>已有信息</b>推出来——① 名册（{@code dinky_task} 的 DDL）给出 topic ↔ 作业 ↔ group.id；
 * ② 作业里的 {@code 'canal-json.database.include' / 'table.include'} 给出 topic ↔ 源库表血缘；
 * ③ 源表结构用<b>已有的 MySQL driver</b> 查（canal-json 报文 = 源表字段 + 固定包裹）。
 * 因此本批<b>不引入任何 Kafka 客户端依赖</b>；「枚举平台里所有 topic（含未被任何作业使用的）」与
 * 分区/lag 这类<b>增量</b>信息，留给批次 2 的 {@code list_topics} / {@code describe_topic}（AdminClient）。
 *
 * <p><b>已知边界</b>：只覆盖「已被作业使用」的 topic——这是 P1 明确接受的口径（计划 §3.3），
 * 输出里会原样声明，避免模型把"没查到"误读为"不存在"。
 *
 * @since 2026/10/08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicRefRenderer {

    public static final String PREFIX = "@topic/";

    /** 找源表结构时最多尝试几个 MySQL 数据源（血缘只给了库表名，未记录它属于哪个数据源） */
    private static final int MAX_DATASOURCE_PROBES = 3;

    private static final int MAX_COLUMNS = 40;

    private final NameRegistryService nameRegistryService;

    private final DataBaseService dataBaseService;

    /**
     * 渲染一条 {@code @topic/} 引用（<b>永不抛异常</b>，失败即降级为可读说明）。
     *
     * @param rawName topic 名（允许带句末点号）
     */
    public String render(String rawName) {
        String topic = StrUtil.trimToEmpty(rawName);
        while (topic.endsWith(".")) {
            topic = topic.substring(0, topic.length() - 1);
        }
        if (StrUtil.isBlank(topic)) {
            return "### " + PREFIX + "\n- 未指定 topic 名，用法：`@topic/<topic名>`\n";
        }
        if (topic.contains("/")) {
            return "### " + PREFIX + topic + "\n- topic 名不应包含 `/`（格式：`@topic/<topic名>`）\n";
        }
        StringBuilder sb = new StringBuilder(320);
        sb.append("### ").append(PREFIX).append(topic).append("（来源：作业 DDL 血缘 + 源表结构；**未连接 Kafka**）\n");
        try {
            NameRegistryService.Snapshot snapshot = nameRegistryService.getSnapshot();
            List<NameRegistryService.TaskLink> links = snapshot.linksOfTopic(topic);
            if (CollUtil.isEmpty(links)) {
                appendNotFound(sb, snapshot);
            } else {
                appendTopicDetail(sb, topic, links);
            }
        } catch (Exception e) {
            log.warn("Render {} failed, topic: {}, message: {}", PREFIX, topic, e.getMessage());
            sb.append("- ❌ 读取作业血缘失败（").append(e.getClass().getSimpleName()).append("），本次已降级。\n");
        }
        return applyBudget(sb.toString(), SystemConfiguration.getInstances().getLlmSourceExpandMaxChars());
    }

    /** 名册里找不到该 topic：说清楚"为什么没查到"与"还能怎么查"，避免模型误判为"topic 不存在"。 */
    private void appendNotFound(StringBuilder sb, NameRegistryService.Snapshot snapshot) {
        sb.append("- ⚠️ 未在任何作业的 DDL 中找到该 topic（本口径**只覆盖已被作业使用的 topic**）\n");
        sb.append("- 可能原因: ① topic 名拼写不同；② 该 topic 存在但尚未被任何作业消费；").append("③ 它只被 FlinkSQL 之外的作业使用\n");
        List<String> known = snapshot.get("kafkaTopic");
        if (CollUtil.isNotEmpty(known)) {
            sb.append("- 名册中已出现的 topic: ").append(joinLimit(known, 20)).append('\n');
        }
        sb.append("- 提示: 枚举平台里**全部** topic（含未被使用的）需要实时连接 Kafka，属 P1 批次 2 的 `list_topics` 能力。\n");
    }

    /** 命中血缘：给出作业 / 血缘 / group.id 占用 / 建议值 / 报文字段。 */
    private void appendTopicDetail(StringBuilder sb, String topic, List<NameRegistryService.TaskLink> links) {
        Set<String> tasks = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        String firstDatabase = null;
        String firstTable = null;
        for (NameRegistryService.TaskLink link : links) {
            if (StrUtil.isNotBlank(link.getTaskName())) {
                tasks.add(link.getTaskName());
            }
            if (StrUtil.isNotBlank(link.getKafkaGroupId())) {
                groups.add(link.getKafkaGroupId());
            }
            String source = link.sourceTablePath();
            if (source != null) {
                sources.add(source);
                if (firstDatabase == null) {
                    firstDatabase = link.getCanalDatabase();
                    firstTable = link.getCanalTable();
                }
            }
        }
        sb.append("- 被引用的作业（")
                .append(tasks.size())
                .append(" 个）: ")
                .append(joinLimit(new ArrayList<>(tasks), 10))
                .append('\n');
        if (CollUtil.isNotEmpty(sources)) {
            sb.append("- 格式: canal-json（据作业 DDL 的 `canal-json.*` 参数）\n");
            sb.append("- 血缘: ").append(joinLimit(new ArrayList<>(sources), 5)).append("（topic → 源库表）\n");
        } else {
            sb.append("- 血缘: 作业 DDL 里没有 `canal-json.database.include` / `table.include`，无法推断源表\n");
        }
        sb.append("- 已占用 group.id: ")
                .append(groups.isEmpty() ? "(无)" : joinLimit(new ArrayList<>(groups), 10))
                .append('\n');
        sb.append("- 建议新 group.id: `")
                .append(SystemConfiguration.getInstances().getKafkaConsumerGroupPrefix())
                .append("_<新作业名>_v1`（同一 topic 的每个作业**必须**独立 group.id，否则会瓜分分区导致数据偏小）\n");
        appendMessageFields(sb, firstDatabase, firstTable);
    }

    /** 报文字段：从 MySQL 源表结构推导（canal-json 报文 = 源表字段 + 固定包裹）。 */
    private void appendMessageFields(StringBuilder sb, String database, String table) {
        if (StrUtil.isBlank(database) || StrUtil.isBlank(table)) {
            return;
        }
        List<Column> columns = probeSourceColumns(database, table);
        if (CollUtil.isEmpty(columns)) {
            sb.append("- 报文字段: 未能取到（源表 `")
                    .append(database)
                    .append('.')
                    .append(table)
                    .append("` 不在当前租户任何启用的 MySQL 数据源里，或连接不可用）；可改用 `@source/<数据源>/")
                    .append(database)
                    .append('/')
                    .append(table)
                    .append("` 直接取源表结构\n");
            return;
        }
        sb.append("- 报文字段（取自 MySQL 源表 `")
                .append(database)
                .append('.')
                .append(table)
                .append("`；canal-json 报文在下列字段外另有 `data` / `old` / `type` 包裹）:\n");
        int limit = Math.min(columns.size(), MAX_COLUMNS);
        for (int i = 0; i < limit; i++) {
            Column column = columns.get(i);
            if (column == null) {
                continue;
            }
            sb.append("    - ").append(column.getName()).append(' ').append(StrUtil.nullToEmpty(column.getType()));
            if (column.isKeyFlag()) {
                sb.append(" [PK]");
            }
            sb.append('\n');
        }
        if (columns.size() > limit) {
            sb.append("    ... (共 ").append(columns.size()).append(" 列)\n");
        }
    }

    /**
     * 试探源表结构：血缘只给了「库.表」，没记它属于哪个数据源，故在<b>启用中的 MySQL 数据源</b>里逐个试。
     *
     * <p>只试前 {@value #MAX_DATASOURCE_PROBES} 个、失败静默——报文字段属加分项，
     * 拿不到时输出里会明确说明（而不是让模型以为"这个 topic 没有字段"）。
     */
    private List<Column> probeSourceColumns(String database, String table) {
        List<DataBase> databases;
        try {
            databases = dataBaseService.listEnabledAll();
        } catch (Exception e) {
            log.warn("Probe source columns failed (list datasource): {}", e.getMessage());
            return new ArrayList<>();
        }
        if (CollUtil.isEmpty(databases)) {
            return new ArrayList<>();
        }
        int probes = 0;
        for (DataBase dataBase : databases) {
            if (dataBase == null || !isMysql(dataBase)) {
                continue;
            }
            if (probes++ >= MAX_DATASOURCE_PROBES) {
                break;
            }
            try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
                List<Column> columns = driver.listColumns(database, table);
                if (CollUtil.isNotEmpty(columns)) {
                    return columns;
                }
            } catch (Exception e) {
                log.debug(
                        "Probe source columns failed, datasource: {}, message: {}", dataBase.getName(), e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    private static boolean isMysql(DataBase dataBase) {
        String type = dataBase.getType();
        return type != null && type.toLowerCase().contains("mysql");
    }

    private static String applyBudget(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (该 @ 引用内容超预算，已截断)\n";
    }

    private static String joinLimit(List<String> items, int limit) {
        if (CollUtil.isEmpty(items)) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        int size = Math.min(items.size(), limit);
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(items.get(i));
        }
        if (items.size() > size) {
            sb.append(" …等 ").append(items.size()).append(" 个");
        }
        return sb.toString();
    }
}
