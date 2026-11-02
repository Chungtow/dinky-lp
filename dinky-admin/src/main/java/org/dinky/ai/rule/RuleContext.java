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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 一次校验的输入（四段式草稿 + 任务字段 + 环境事实）。
 *
 * <p>SQL 预处理结果（去注释、SET 表、建表块、sink 目标）在此缓存一次，
 * 20 条规则共享同一份解析结果 —— 避免每条规则各解析一遍（B1~B4 单条 SQL 上百行）。
 *
 * @since 2026/11/02
 */
@Data
@Builder
public class RuleContext {

    /** 四段式 {@code sql} 段 */
    private String sql;

    /** 四段式 {@code customConfig} 段（key → value） */
    @Builder.Default
    private Map<String, String> customConfig = new LinkedHashMap<>();

    /** 四段式 {@code ddl} 段的建表语句原文（目标库 DDL，非 Flink 建表） */
    @Builder.Default
    private List<String> ddlStatements = new ArrayList<>();

    private Integer taskId;

    private String taskName;

    private Integer parallelism;

    private Integer clusterId;

    private Integer envId;

    private Integer tenantId;

    /** Flink 作业 id（runtime 断言用；来自 {@code changeTaskLife} 之后的提交结果） */
    private String jobId;

    /** 数据源快照（precheck 查表存在性用；由请求线程预解析后注入） */
    @Builder.Default
    private org.dinky.ai.context.RequestSnapshot requestSnapshot = org.dinky.ai.context.RequestSnapshot.empty();

    /** 目标库类型覆盖（草稿显式声明时优先于 SQL 推断，如 {@code clickhouse} / {@code doris}） */
    private String sinkConnectorOverride;

    private volatile SqlFacts facts;

    /** SQL 事实（懒解析 + 缓存） */
    public SqlFacts facts() {
        SqlFacts local = facts;
        if (local == null) {
            synchronized (this) {
                local = facts;
                if (local == null) {
                    local = SqlFacts.of(getSql());
                    facts = local;
                }
            }
        }
        return local;
    }

    /** 目标库类型：草稿声明优先，其次从 sink 建表推断 */
    public String sinkConnector() {
        if (sinkConnectorOverride != null && !sinkConnectorOverride.trim().isEmpty()) {
            return sinkConnectorOverride.trim().toLowerCase(Locale.ROOT);
        }
        return facts().sinkConnector();
    }

    /** 全部 DDL 文本（四段式 ddl 段 + SQL 内建表语句），供空值 / Doris 键规则使用 */
    public String ddlText() {
        StringBuilder sb = new StringBuilder();
        if (ddlStatements != null) {
            for (String ddl : ddlStatements) {
                if (ddl != null) {
                    sb.append(ddl).append(";\n");
                }
            }
        }
        for (SqlKit.TableDef def : facts().createTables().values()) {
            sb.append(def.getStatement()).append(";\n");
        }
        return sb.toString();
    }

    public Map<String, String> customConfigSafe() {
        return customConfig == null ? Collections.emptyMap() : customConfig;
    }

    /** SQL 预处理结果 */
    public static class SqlFacts {

        private final String stripped;

        private final Map<String, String> sets;

        private final Map<String, SqlKit.TableDef> tables;

        private final String sinkTable;

        private final String sinkConnector;

        private final boolean windowAggregation;

        private final int kafkaSourceCount;

        private SqlFacts(
                String stripped,
                Map<String, String> sets,
                Map<String, SqlKit.TableDef> tables,
                String sinkTable,
                String sinkConnector,
                boolean windowAggregation,
                int kafkaSourceCount) {
            this.stripped = stripped;
            this.sets = sets;
            this.tables = tables;
            this.sinkTable = sinkTable;
            this.sinkConnector = sinkConnector;
            this.windowAggregation = windowAggregation;
            this.kafkaSourceCount = kafkaSourceCount;
        }

        public static SqlFacts of(String sql) {
            String stripped = SqlKit.stripComments(sql);
            Map<String, String> sets = SqlKit.setStatements(stripped);
            Map<String, SqlKit.TableDef> tables = SqlKit.createTables(stripped);
            String sink = SqlKit.sinkTable(stripped);
            String connector = resolveConnector(tables, sink);
            return new SqlFacts(
                    stripped,
                    sets,
                    tables,
                    sink,
                    connector,
                    SqlKit.hasWindowAggregation(stripped),
                    SqlKit.countByOption(tables, "connector", "kafka"));
        }

        /**
         * 解析 sink 连接器类型。
         *
         * <p>F7：ClickHouse 走的是 {@code connector='jdbc'} + 自研方言，光看 connector 分不清
         * CH 与 MySQL —— 必须再看 {@code url} 的 scheme（{@code jdbc:clickhouse:}）。
         */
        private static String resolveConnector(Map<String, SqlKit.TableDef> tables, String sinkTable) {
            if (sinkTable == null) {
                return null;
            }
            String key = sinkTable.toLowerCase(Locale.ROOT);
            SqlKit.TableDef def = tables.get(key);
            if (def == null) {
                def = tables.get(SqlKit.lastSegment(key).toLowerCase(Locale.ROOT));
            }
            if (def == null) {
                return null;
            }
            String connector = def.option("connector");
            if (connector == null) {
                return null;
            }
            String lower = connector.toLowerCase(Locale.ROOT);
            if (lower.contains("doris")) {
                return "doris";
            }
            if ("jdbc".equals(lower)) {
                String url = def.option("url");
                if (url != null && url.toLowerCase(Locale.ROOT).contains("clickhouse")) {
                    return "clickhouse";
                }
            }
            return lower;
        }

        public String stripped() {
            return stripped;
        }

        public Map<String, String> sets() {
            return sets;
        }

        public Map<String, SqlKit.TableDef> createTables() {
            return tables;
        }

        public String sinkTable() {
            return sinkTable;
        }

        public String sinkConnector() {
            return sinkConnector;
        }

        public boolean windowAggregation() {
            return windowAggregation;
        }

        public int kafkaSourceCount() {
            return kafkaSourceCount;
        }

        public String set(String key) {
            return key == null ? null : sets.get(key.toLowerCase(Locale.ROOT));
        }
    }
}
