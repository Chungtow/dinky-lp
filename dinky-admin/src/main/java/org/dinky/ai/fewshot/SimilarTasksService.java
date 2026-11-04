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

package org.dinky.ai.fewshot;

import org.dinky.ai.rule.FlinkSqlRuleEngine;
import org.dinky.ai.rule.RuleContext;
import org.dinky.config.Dialect;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.model.Task;
import org.dinky.service.TaskService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * few-shot 召回（M1-C）：L1 静态语料 + L2 本租户线上作业。
 *
 * <p>三件事必须同时成立才叫"可用"：
 * <ol>
 *   <li><b>只回骨架</b>：SQL 里的字面量（口令/表名映射）打码后再返回</li>
 *   <li><b>脏作业不进池</b>：L2 作业先过 draft 档规则，有 error 的直接排除（反面教材不能当范例）</li>
 *   <li><b>不调模型</b>：打分纯本地，召回是"检索"不是"生成"，延迟必须毫秒级</li>
 * </ol>
 *
 * @since 2026/11/02
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarTasksService {

    /** 口令类字面量（含 WITH 里的 jdbc 用户/密码） */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)('?)(password|passwd|pwd|secret|token|api[-_]?key|username|user)(\\1\\s*=\\s*')([^']*)(')");

    private static final Pattern TOPIC = Pattern.compile("(?i)'topic'\\s*=\\s*'([^']+)'");

    private static final Pattern SINK = Pattern.compile("(?i)insert\\s+into\\s+([`\\w.]+)");

    private final StaticCorpusLoader staticCorpusLoader;

    private final TaskService taskService;

    private final FlinkSqlRuleEngine ruleEngine;

    /** 召回入口：返回 {@code {items[], candidates:{l1,l2}}} */
    public JSONObject recall(SimilarTasksQuery query) {
        SimilarTasksQuery safeQuery = query == null ? new SimilarTasksQuery() : query;
        int limit = safeQuery.getLimit() == null ? 2 : Math.max(1, Math.min(5, safeQuery.getLimit()));

        List<SimilarTask> l1 = new ArrayList<>(staticCorpusLoader.all());
        List<SimilarTask> l2 = dynamicCandidates();

        List<SimilarTask> pool = new ArrayList<>(l1);
        pool.addAll(l2);
        pool.sort(Comparator.comparingInt((SimilarTask item) -> score(item, safeQuery))
                .reversed());

        List<SimilarTask> picked = new ArrayList<>();
        for (SimilarTask item : pool) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(item);
        }
        JSONObject result = new JSONObject();
        JSONArray items = new JSONArray();
        for (SimilarTask item : picked) {
            items.add(toJson(item));
        }
        JSONObject candidates = new JSONObject();
        candidates.set("l1", l1.size());
        candidates.set("l2", l2.size());
        result.set("items", items);
        result.set("candidates", candidates);
        return result;
    }

    /** L2 候选缓存：跑规则校验有成本（最多 200 个作业），按租户缓存 5 分钟 */
    private static final long L2_TTL_MS = 300_000L;

    private final Map<String, CachedCandidates> l2Cache = new ConcurrentHashMap<>();

    /**
     * L2：本租户线上 FlinkSQL 作业（脏数据由 draft 档规则挡掉）。
     *
     * <p><b>2026-11-04 加缓存</b>：P0 把召回接进对话链路后，本方法会在**每次提问**时被调用；
     * 而它要对最多 200 个作业逐个跑 {phase=draft} 规则校验，实测单次 1~2 秒。
     * 不缓存的话，用户每问一句都要等这段扫描——而召回本身是"检索"，没理由比生成还慢。
     * 按租户缓存 5 分钟：够覆盖一轮对话，又不至于让刚改过的作业长期以旧版出现。
     */
    private List<SimilarTask> dynamicCandidates() {
        String key = String.valueOf(TenantContextHolder.get());
        CachedCandidates cached = l2Cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < L2_TTL_MS) {
            return cached.items;
        }
        List<SimilarTask> loaded = loadDynamicCandidates();
        l2Cache.put(key, new CachedCandidates(loaded, now));
        return loaded;
    }

    private List<SimilarTask> loadDynamicCandidates() {
        List<SimilarTask> candidates = new ArrayList<>();
        List<Task> tasks;
        try {
            tasks = taskService
                    .lambdaQuery()
                    .eq(Task::getDialect, Dialect.FLINK_SQL.getValue())
                    .last("limit 200")
                    .list();
        } catch (Exception e) {
            log.warn("few-shot L2 query failed, fallback to L1 only: {}", e.getMessage());
            return candidates;
        }
        if (tasks == null) {
            return candidates;
        }
        for (Task task : tasks) {
            String sql = task.getStatement();
            if (StrUtil.isBlank(sql)) {
                continue;
            }
            RuleContext ruleContext = RuleContext.builder()
                    .sql(sql)
                    .taskId(task.getId())
                    .taskName(task.getName())
                    .parallelism(task.getParallelism())
                    .build();
            try {
                if (ruleEngine.hasBlocking(ruleEngine.validateDraft(ruleContext))) {
                    continue;
                }
            } catch (Exception e) {
                log.debug("few-shot L2 skip uncheckable task {}", task.getId(), e);
                continue;
            }
            candidates.add(SimilarTask.builder()
                    .id("task_" + task.getId())
                    .taskId(task.getId())
                    .title(task.getName())
                    .topics(extract(TOPIC, sql))
                    .tables(extract(SINK, sql))
                    .keywords(keywordsOf(task.getName()))
                    .sql(SECRET.matcher(sql).replaceAll("$1$2$3***$5"))
                    .source("L2")
                    .build());
        }
        return candidates;
    }

    private static List<String> extract(Pattern pattern, String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<String> keywordsOf(String name) {
        List<String> keywords = new ArrayList<>();
        if (StrUtil.isBlank(name)) {
            return keywords;
        }
        for (String part : name.split("[_\\-\\s]+")) {
            if (part.length() > 1) {
                keywords.add(part);
            }
        }
        return keywords;
    }

    /** 命中表 ×3、topic ×2、关键词 ×1（歧义时靠 title 兜底排序） */
    private static int score(SimilarTask item, SimilarTasksQuery query) {
        int score = 0;
        String blob = item.blob().toLowerCase(Locale.ROOT);
        for (String table : query.getTables()) {
            if (StrUtil.isNotBlank(table) && containsIgnoreCase(item.getTables(), table)) {
                score += 3;
            }
        }
        for (String topic : query.getTopics()) {
            if (StrUtil.isNotBlank(topic) && containsIgnoreCase(item.getTopics(), topic)) {
                score += 2;
            }
        }
        for (String keyword : query.getKeywords()) {
            if (StrUtil.isNotBlank(keyword) && blob.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject toJson(SimilarTask item) {
        JSONObject json = new JSONObject();
        json.set("id", item.getId());
        json.set("title", item.getTitle());
        json.set("source", item.getSource());
        json.set("taskId", item.getTaskId());
        json.set("topics", item.getTopics());
        json.set("tables", item.getTables());
        json.set("keywords", item.getKeywords());
        json.set("sql", item.getSql());
        json.set("customConfig", item.getCustomConfig());
        json.set("ddl", item.getDdl());
        json.set("precheck", item.getPrecheck());
        return json;
    }

    /** L2 候选缓存项 */
    private static final class CachedCandidates {

        private final List<SimilarTask> items;

        private final long loadedAt;

        private CachedCandidates(List<SimilarTask> items, long loadedAt) {
            this.items = items;
            this.loadedAt = loadedAt;
        }
    }
}
