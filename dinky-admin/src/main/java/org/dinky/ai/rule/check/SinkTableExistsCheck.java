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

package org.dinky.ai.rule.check;

import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;
import org.dinky.ai.rule.SqlKit;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.DataBaseService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.sink_table_exists}：sink 的目标表必须在**默认 sink 库**里真实存在。
 *
 * <p><b>为什么必须拦</b>：Flink 侧 {@code CREATE TABLE ... WITH(...)} 只是逻辑映射，不会建表。
 * 目标表不存在时作业<b>提交成功、RUNNING</b>，直到第一条数据进 sink 才失败（或 sink 缓冲满才报）。
 * 2026-11-04 实测：模型编了一张 {@code traccar.dws_traccar_device_position_top5_df}，
 * 而该库里实际只有 {@code dwd_tc_events_enriched / dws_device_position_window / dws_event_minute / ods_tc_devices}。
 *
 * <p>与 {@code FLINK-SINK-002}（{@code api.table_exists}，precheck 档）的分工：
 * 那条依赖模型主动调 {@code describe_tables} 才判得了，模型不调就是 {@code unknown}；
 * 本条在<b>草稿阶段</b>用服务端自己的数据源连接去查，不依赖模型配合。
 *
 * <p>表清单按 {@code schema} 做 60 秒缓存：校验会在一次对话里被调多次（自愈闭环每轮一次），
 * 每次都连库既慢又给目标库添无谓连接。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
public class SinkTableExistsCheck implements RuleCheck {

    /** 表清单缓存 TTL：够覆盖一轮对话的自愈过程，又不至于长期与真实库脱节 */
    private static final long CACHE_TTL_MS = 60_000L;

    private static final int MAX_REPORTED = 20;

    /** schema → (表名集合, 加载时间) */
    private static final Map<String, CachedTables> CACHE = new ConcurrentHashMap<>();

    private final DataBaseService dataBaseService;

    public SinkTableExistsCheck() {
        this(null);
    }

    @Autowired
    public SinkTableExistsCheck(DataBaseService dataBaseService) {
        this.dataBaseService = dataBaseService;
    }

    @Override
    public String kind() {
        return "sql.sink_table_exists";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sinkTable = context.facts().sinkTable();
        if (StrUtil.isBlank(sinkTable)) {
            return RuleOutcome.notApplicable("未解析到 INSERT INTO 的目标表");
        }
        SqlKit.TableDef def = context.facts().createTables().get(sinkTable);
        if (def == null) {
            // 目标表可能来自已注册 catalog（没有内联 DDL），此时无从得知 table-name
            return RuleOutcome.unknown("sink 表 `" + sinkTable + "` 没有内联 DDL，无法确定物理表名");
        }
        String declared = def.option("table-name");
        if (StrUtil.isBlank(declared)) {
            return RuleOutcome.notApplicable("sink 表没有 table-name 选项（非 jdbc/doris 形态）");
        }
        DataBase sink = resolveDefaultSink();
        if (sink == null) {
            return RuleOutcome.unknown("配置中心未解析到可用的默认 sink 数据源，无法核对目标表是否存在");
        }
        String schema = schemaOf(declared, sink);
        if (schema.isEmpty()) {
            return RuleOutcome.unknown("无法从连接串或 table-name 推断库名，跳过目标表存在性校验");
        }
        Set<String> tables = loadTables(sink, schema);
        if (tables == null) {
            return RuleOutcome.unknown("读取 `" + schema + "` 库的表清单失败（数据源不可达），跳过该校验");
        }
        String target = tableNameOf(declared);
        if (tables.contains(target.toLowerCase(Locale.ROOT))) {
            return RuleOutcome.pass("目标表 `" + schema + "." + target + "` 已存在");
        }
        return RuleOutcome.fail("目标表 `" + schema + "." + target + "` 在默认 sink 库中**不存在**"
                + "（该库现有表: " + join(tables) + "）。Flink 的 CREATE TABLE 只是逻辑映射，不会建表；"
                + "把不存在的表写进 INSERT INTO，作业能提交、首条数据才失败");
    }

    /** 默认 sink 的解析与上下文装配同口径：先按名称，再按 id 兼容 */
    private DataBase resolveDefaultSink() {
        if (dataBaseService == null) {
            return null;
        }
        SystemConfiguration config = SystemConfiguration.getInstances();
        String name = StrUtil.trimToEmpty(config.getAiDefaultSinkDataBaseName());
        if (!name.isEmpty()) {
            try {
                DataBase sink = dataBaseService.getEnabledByName(name);
                if (sink != null) {
                    return sink;
                }
            } catch (Exception e) {
                log.debug("sink table check: load default sink by name failed", e);
            }
        }
        int id = config.getAiDefaultSinkDataBaseId();
        if (id > 0) {
            try {
                return dataBaseService.getById(id);
            } catch (Exception e) {
                log.debug("sink table check: load default sink by id failed", e);
            }
        }
        return null;
    }

    /** {@code 'table-name' = 'traccar.dws_x'} 的库名优先；没写则用连接串里的默认库 */
    private static String schemaOf(String declared, DataBase sink) {
        String text = declared.trim();
        int dot = text.lastIndexOf('.');
        if (dot > 0) {
            return text.substring(0, dot).trim();
        }
        return defaultSchemaOfUrl(sink);
    }

    private static String tableNameOf(String declared) {
        String text = declared.trim();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1).trim() : text;
    }

    /** 与 {@code FlinkContextProvider#defaultSchemaOf} 同口径：{@code jdbc:clickhouse://host:port/db} → db */
    private static String defaultSchemaOfUrl(DataBase sink) {
        try {
            Object connectConfig = sink.getDriverConfig() == null
                    ? null
                    : sink.getDriverConfig().getConnectConfig();
            if (!(connectConfig instanceof Map)) {
                return "";
            }
            Object url = ((Map<?, ?>) connectConfig).get("url");
            if (url == null) {
                return "";
            }
            String text = String.valueOf(url);
            int query = text.indexOf('?');
            if (query >= 0) {
                text = text.substring(0, query);
            }
            int slash = text.lastIndexOf('/');
            if (slash < 0 || slash == text.length() - 1) {
                return "";
            }
            String schema = text.substring(slash + 1).trim();
            if (schema.isEmpty() || schema.contains(":") || schema.matches("\\d+")) {
                return "";
            }
            return schema;
        } catch (Exception e) {
            return "";
        }
    }

    /** @return 小写表名集合；读取失败返回 null（与"空库"区分开：空库是确定结论，读失败是判不了） */
    private Set<String> loadTables(DataBase sink, String schema) {
        String key = sink.getId() + "@" + schema;
        CachedTables cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt < CACHE_TTL_MS) {
            return cached.tables;
        }
        try {
            Driver driver = Driver.build(sink.getDriverConfig());
            List<Table> tables = driver.listTables(schema);
            Set<String> names = new LinkedHashSet<>();
            if (tables != null) {
                for (Table table : tables) {
                    if (table != null && StrUtil.isNotBlank(table.getName())) {
                        names.add(table.getName().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            CACHE.put(key, new CachedTables(names, now));
            return names;
        } catch (Exception e) {
            log.debug("sink table check: list tables of {} failed", schema, e);
            return null;
        }
    }

    private static String join(Set<String> tables) {
        if (tables.isEmpty()) {
            return "（空）";
        }
        List<String> sorted = new ArrayList<>(tables);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String table : sorted) {
            if (shown >= MAX_REPORTED) {
                sb.append(" …");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append('`').append(table).append('`');
            shown++;
        }
        return sb.toString();
    }

    /** 表清单缓存项 */
    private static final class CachedTables {

        private final Set<String> tables;

        private final long loadedAt;

        private CachedTables(Set<String> tables, long loadedAt) {
            this.tables = tables;
            this.loadedAt = loadedAt;
        }
    }
}
