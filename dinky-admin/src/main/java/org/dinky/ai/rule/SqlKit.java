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

package org.dinky.ai.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

/**
 * FlinkSQL 文本预处理（规则引擎的公共前置工序）。
 *
 * <p><b>为什么不直接对原文跑正则</b>（S0 阻断级发现 F1）：标杆作业把"反例写法"写在注释里当语料，
 * B2 的 5 处 {@code ROW_NUMBER} 全在 {@code --} 注释中 —— 不去注释会把合格作业判成回撤流。
 * 但 {@code /*+ LOOKUP(...) *}{@code /} 这类 <b>hint 也是注释形态</b>，必须保留，否则
 * {@code FLINK-LOOKUP-001} 会把合格作业判成"缺 hint"。
 *
 * @since 2026/11/02
 */
public final class SqlKit {

    private static final Pattern SET_STMT = Pattern.compile("(?is)^\\s*set\\s+'([^']+)'\\s*=\\s*'([^']*)'");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([`\\w.]+)");

    private static final Pattern INSERT_INTO = Pattern.compile("(?is)insert\\s+into\\s+([`\\w.]+)");

    private static final Pattern KV = Pattern.compile("'([^']+)'\\s*=\\s*'([^']*)'");

    private static final Pattern GROUP_BY = Pattern.compile("(?i)\\bgroup\\s+by\\b");

    private static final Pattern WINDOW_FN = Pattern.compile("(?i)\\b(tumble|hop|cumulate|session)\\s*\\(");

    private SqlKit() {}

    /**
     * 去注释，但保留 {@code /*+ ... *}{@code /} 提示（hint）。
     *
     * <p>同时保留字符串字面量内容 —— 否则 {@code 'format' = 'canal-json'} 这类判定会失效。
     */
    public static String stripComments(String sql) {
        if (StrUtil.isEmpty(sql)) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        char quote = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                boolean hint = i + 2 < n && sql.charAt(i + 2) == '+';
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    if (hint) {
                        out.append(sql, i, n);
                    }
                    break;
                }
                if (hint) {
                    out.append(sql, i, end + 2);
                }
                i = end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 按引号外的 {@code ;} 切分语句（空语句丢弃） */
    public static List<String> statements(String sql) {
        List<String> list = new ArrayList<>();
        if (StrUtil.isEmpty(sql)) {
            return list;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';') {
                addIfNotBlank(list, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(list, current.toString());
        return list;
    }

    private static void addIfNotBlank(List<String> list, String value) {
        if (StrUtil.isNotBlank(value)) {
            list.add(value.trim());
        }
    }

    /** 解析所有 {@code SET 'key' = 'value'}，key 统一小写（Flink 参数名大小写不敏感） */
    public static Map<String, String> setStatements(String strippedSql) {
        Map<String, String> sets = new LinkedHashMap<>();
        for (String stmt : statements(strippedSql)) {
            Matcher m = SET_STMT.matcher(stmt);
            if (m.find()) {
                sets.put(m.group(1).trim().toLowerCase(Locale.ROOT), m.group(2).trim());
            }
        }
        return sets;
    }

    /**
     * 解析 {@code CREATE TABLE name (...) WITH ('k'='v', ...)} 块。
     *
     * @return 表名（小写）→ 表定义
     */
    public static Map<String, TableDef> createTables(String strippedSql) {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        for (String stmt : statements(strippedSql)) {
            Matcher m = CREATE_TABLE.matcher(stmt);
            if (!m.find()) {
                continue;
            }
            String name = unquote(m.group(1)).toLowerCase(Locale.ROOT);
            Map<String, String> options = new LinkedHashMap<>();
            int withIdx = indexOfKeywordOutsideParens(stmt, "with", m.end());
            if (withIdx > 0) {
                int open = stmt.indexOf('(', withIdx);
                String body = balancedBody(stmt, open);
                if (body != null) {
                    Matcher kv = KV.matcher(body);
                    while (kv.find()) {
                        options.put(
                                kv.group(1).trim().toLowerCase(Locale.ROOT),
                                kv.group(2).trim());
                    }
                }
            }
            tables.put(name, new TableDef(name, stmt, options));
        }
        return tables;
    }

    /** 最后一个 {@code INSERT INTO} 的目标表（含 schema 前缀时原样返回） */
    public static String sinkTable(String strippedSql) {
        String table = null;
        Matcher m = INSERT_INTO.matcher(strippedSql);
        while (m.find()) {
            table = unquote(m.group(1));
        }
        return table;
    }

    /** 是否包含窗口聚合（{@code GROUP BY} + 窗口表值函数）—— F2：B2/B4 的形态 */
    public static boolean hasWindowAggregation(String strippedSql) {
        return GROUP_BY.matcher(strippedSql).find()
                && WINDOW_FN.matcher(strippedSql).find();
    }

    public static boolean hasGroupBy(String strippedSql) {
        return GROUP_BY.matcher(strippedSql).find();
    }

    public static int countByOption(Map<String, TableDef> tables, String option, String expected) {
        int count = 0;
        for (TableDef def : tables.values()) {
            String value = def.options.get(option);
            if (value != null && value.equalsIgnoreCase(expected)) {
                count++;
            }
        }
        return count;
    }

    /** 在语句中查某关键字（跳过字符串与括号内），返回小写匹配位置；找不到返回 -1 */
    public static int indexOfKeywordOutsideParens(String sql, String keyword, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = Math.max(0, from); i < sql.length() - keyword.length() + 1; i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth == 0 && sql.regionMatches(true, i, keyword, 0, keyword.length())) {
                boolean before = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                int afterIdx = i + keyword.length();
                boolean after = afterIdx >= sql.length() || !Character.isLetterOrDigit(sql.charAt(afterIdx));
                if (before && after) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 取 {@code (} 之后配对的括号内容；不配对返回 null */
    public static String balancedBody(String sql, int openIdx) {
        if (openIdx < 0 || openIdx >= sql.length() || sql.charAt(openIdx) != '(') {
            return null;
        }
        int depth = 0;
        char quote = 0;
        for (int i = openIdx; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openIdx + 1, i);
                }
            }
        }
        return null;
    }

    public static String unquote(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("`", "").replace("\"", "").trim();
    }

    /** 去掉 schema 前缀，取最后一段 */
    public static String lastSegment(String ref) {
        if (ref == null) {
            return null;
        }
        String cleaned = unquote(ref);
        int dot = cleaned.lastIndexOf('.');
        return dot < 0 ? cleaned : cleaned.substring(dot + 1);
    }

    /** 单张 Flink 建表语句的解析结果 */
    public static class TableDef {

        private final String name;

        private final String statement;

        private final Map<String, String> options;

        public TableDef(String name, String statement, Map<String, String> options) {
            this.name = name;
            this.statement = statement;
            this.options = options;
        }

        public String getName() {
            return name;
        }

        public String getStatement() {
            return statement;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public String option(String key) {
            return key == null ? null : options.get(key.toLowerCase(Locale.ROOT));
        }
    }
}
