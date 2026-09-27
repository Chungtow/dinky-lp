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

package org.dinky.ai;

import org.dinky.data.model.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 大库场景下的表召回（阶段 0.2）。
 *
 * <p>问题：企业库动辄上千张表，全量塞进 context 既超 token 又会稀释模型注意力。
 * 本类按问题关键词对表名 / 表注释做<b>打分召回</b>，只把最相关的表放进上下文。
 *
 * <p>实现刻意保持轻量（纯内存字符串匹配，<b>不引入向量库等第三方组件</b>）：
 * <ol>
 *   <li>从问题中抽取关键词：英文标识符按词提取；中文片段按 2-gram 切分</li>
 *   <li>与表名 / 表注释比对打分：表名相等 100 &gt; 表名包含 60 &gt; 注释包含 40</li>
 *   <li>按分值降序取 TopN；一个都没命中时退回前 TopN 张（保证上下文不为空）</li>
 * </ol>
 *
 * @since 2026/09/27
 */
public final class TableSelector {

    private TableSelector() {}

    /** 英文/数字标识符（表名、字段名常见形态） */
    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,}");
    /** 连续中文片段 */
    private static final Pattern CN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private static final int SCORE_TABLE_EQUALS = 100;
    private static final int SCORE_TABLE_CONTAINS = 60;
    private static final int SCORE_COMMENT_CONTAINS = 40;

    /**
     * 召回与问题最相关的表。
     *
     * @param tables 该 schema 下的全部表
     * @param question 用户问题
     * @param topN 召回数量上限
     * @return 召回结果（按分值降序；一个都没命中时返回前 topN 张）
     */
    public static List<Table> select(List<Table> tables, String question, int topN) {
        int limit = Math.max(topN, 1);
        if (CollUtil.isEmpty(tables)) {
            return new ArrayList<>();
        }
        if (tables.size() <= limit) {
            return new ArrayList<>(tables);
        }
        Set<String> keywords = extractKeywords(question);
        if (keywords.isEmpty()) {
            return new ArrayList<>(tables.subList(0, limit));
        }

        List<ScoredTable> scored = new ArrayList<>(tables.size());
        for (Table table : tables) {
            int score = score(table, keywords);
            if (score > 0) {
                scored.add(new ScoredTable(table, score));
            }
        }
        if (scored.isEmpty()) {
            // 一个都没命中：不能让模型"盲写"，退回清单开头若干张
            return new ArrayList<>(tables.subList(0, limit));
        }
        scored.sort(Comparator.comparingInt(ScoredTable::getScore).reversed());
        List<Table> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            result.add(scored.get(i).getTable());
        }
        return result;
    }

    /** 抽取问题关键词（英文标识符 + 中文 2-gram） */
    public static Set<String> extractKeywords(String question) {
        Set<String> keywords = new LinkedHashSet<>();
        if (StrUtil.isBlank(question)) {
            return keywords;
        }
        Matcher wordMatcher = WORD_PATTERN.matcher(question);
        while (wordMatcher.find()) {
            keywords.add(wordMatcher.group().toLowerCase());
        }
        Matcher cnMatcher = CN_PATTERN.matcher(question);
        while (cnMatcher.find()) {
            String segment = cnMatcher.group();
            if (segment.length() <= 4) {
                keywords.add(segment);
                continue;
            }
            for (int i = 0; i + 2 <= segment.length(); i++) {
                keywords.add(segment.substring(i, i + 2));
            }
        }
        return keywords;
    }

    /** 单表打分 */
    private static int score(Table table, Set<String> keywords) {
        String tableName = StrUtil.nullToEmpty(table.getName()).toLowerCase();
        String comment = StrUtil.nullToEmpty(table.getComment()).toLowerCase();
        if (StrUtil.isBlank(tableName)) {
            return 0;
        }
        int score = 0;
        for (String keyword : keywords) {
            if (tableName.equals(keyword)) {
                score += SCORE_TABLE_EQUALS;
            } else if (tableName.contains(keyword)) {
                score += SCORE_TABLE_CONTAINS;
            } else if (StrUtil.isNotBlank(comment) && comment.contains(keyword)) {
                score += SCORE_COMMENT_CONTAINS;
            }
        }
        return score;
    }

    /** 带分值的表（排序用） */
    private static class ScoredTable {
        private final Table table;
        private final int score;

        ScoredTable(Table table, int score) {
            this.table = table;
            this.score = score;
        }

        Table getTable() {
            return table;
        }

        int getScore() {
            return score;
        }
    }
}
