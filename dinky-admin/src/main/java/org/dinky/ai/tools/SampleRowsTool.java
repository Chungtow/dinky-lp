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

package org.dinky.ai.tools;

import static org.dinky.ai.tools.ListTablesTool.SAFE_NAME;
import static org.dinky.ai.tools.ListTablesTool.resolveSchema;

import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.metadata.driver.Driver;
import org.dinky.metadata.result.JdbcSelectResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 返回表的前 N 行样例数据（🔴 唯一触碰业务数据行的工具）。
 *
 * <p><b>默认不注册</b>（{@code sys.llm.settings.toolSampleRowsEnable=false}）——不下发给模型比
 * 下发后再拒绝更安全。启用后仍受四重约束：行数上限、敏感列掩码、字符截断、审计留痕。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
public class SampleRowsTool implements AiTool {

    public static final String NAME = "sample_rows";

    /** 行数硬上限：即便模型要 100 行也只给这么多 */
    private static final int MAX_LIMIT = 20;

    private static final int DEFAULT_LIMIT = 5;
    /** 单个单元格的字符上限 */
    private static final int MAX_CELL_CHARS = 100;

    /**
     * 敏感列：命中其一即整体掩码。
     *
     * <p>按「宁可多掩」处理——样例数据的用途只是让模型看清数据形态（枚举值、日期格式），
     * 不需要真实敏感值。
     */
    private static final List<String> SENSITIVE_COLUMN_HINTS = Arrays.asList(
            "password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "privatekey",
            "private_key",
            "accesskey",
            "access_key",
            "phone",
            "mobile",
            "idcard",
            "id_card",
            "creditcard",
            "bankcard",
            "bank_card");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config != null && config.isLlmToolSampleRowsEnable();
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("tableName", AiToolSpec.stringProperty("必填，表名"));
        properties.set("schemaName", AiToolSpec.stringProperty("可选，schema / database 名；留空表示当前绑定的 schema"));
        properties.set("limit", AiToolSpec.integerProperty("返回行数，1~" + MAX_LIMIT + "，默认 " + DEFAULT_LIMIT));
        return AiToolSpec.builder()
                .name(NAME)
                .description(
                        "返回指定表的前 N 行样例数据（N 上限 " + MAX_LIMIT + "，且敏感列已脱敏）。仅用于确认数据形态（如枚举值、日期格式），" + "严禁用于导出、统计或推断全量数据。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("tableName")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        if (context == null || context.getDataBase() == null) {
            return AiToolResult.failure("未绑定数据源，无法取样例数据", 0L);
        }
        String tableName = StrUtil.trimToNull(args == null ? null : args.getStr("tableName"));
        String schemaName = resolveSchema(args, context);
        if (tableName == null
                || !SAFE_NAME.matcher(tableName).matches()
                || !SAFE_NAME.matcher(schemaName).matches()) {
            return AiToolResult.failure("表名或 schema 名不合法，已拒绝执行", System.currentTimeMillis() - start);
        }
        int limit = parseLimit(args);
        // 表名已通过标识符白名单校验，可直接拼接；此处仍是只读 SELECT + LIMIT
        String qualified = StrUtil.isBlank(schemaName) ? tableName : schemaName + "." + tableName;
        String sql = "SELECT * FROM " + qualified + " LIMIT " + limit;
        try (Driver driver = Driver.build(context.getDataBase().getDriverConfig())) {
            JdbcSelectResult result = driver.query(sql, limit);
            if (result == null || !result.isSuccess()) {
                String error = result == null ? null : result.getError();
                log.warn("sample_rows failed, table: {}, message: {}", qualified, error);
                return AiToolResult.failure(
                        "获取样例数据失败：表名可能不存在、无权访问，或数据源不支持 LIMIT 语法。", System.currentTimeMillis() - start);
            }
            return AiToolResult.success(render(qualified, result), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("sample_rows failed, table: {}, message: {}", qualified, e.getMessage());
            return AiToolResult.failure("获取样例数据失败：表名可能不存在、无权访问，或数据源不支持该语法。", System.currentTimeMillis() - start);
        }
    }

    private int parseLimit(JSONObject args) {
        int limit = args == null ? DEFAULT_LIMIT : args.getInt("limit", DEFAULT_LIMIT);
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String render(String qualified, JdbcSelectResult result) {
        List<LinkedHashMap<String, Object>> rows = result.getRowData();
        if (CollUtil.isEmpty(rows)) {
            return "表 " + qualified + " 无数据行（表可能存在但为空）。";
        }
        List<String> columnNames = result.getColumns();
        StringBuilder sb = new StringBuilder();
        sb.append("表 ").append(qualified).append(" 的前 ").append(rows.size()).append(" 行样例");
        sb.append("（敏感列已脱敏，单个值超过 ").append(MAX_CELL_CHARS).append(" 字符会截断）：\n");
        sb.append(String.join(" | ", maskedHeaders(columnNames))).append("\n");
        for (LinkedHashMap<String, Object> row : rows) {
            StringBuilder line = new StringBuilder();
            for (String column : columnNames) {
                if (line.length() > 0) {
                    line.append(" | ");
                }
                line.append(mask(column, row.get(column)));
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<String> maskedHeaders(List<String> columnNames) {
        return columnNames == null ? Collections.<String>emptyList() : columnNames;
    }

    /** 敏感列整体替换为掩码，其余值做长度截断 */
    private String mask(String column, Object value) {
        if (isSensitive(column)) {
            return "***";
        }
        String text = value == null ? "NULL" : String.valueOf(value);
        text = text.replaceAll("\\s+", " ");
        return StrUtil.sub(text, 0, MAX_CELL_CHARS);
    }

    private boolean isSensitive(String column) {
        if (StrUtil.isBlank(column)) {
            return false;
        }
        String lower = column.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String hint : SENSITIVE_COLUMN_HINTS) {
            if (lower.contains(hint.replaceAll("[^a-z0-9]", ""))) {
                return true;
            }
        }
        return false;
    }
}
