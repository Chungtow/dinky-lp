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
import org.dinky.data.model.Column;
import org.dinky.data.model.ForeignKey;
import org.dinky.data.model.TableRelations;
import org.dinky.metadata.driver.Driver;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 获取指定表的字段结构（🟢 纯元数据）。
 *
 * <p>这是阶段 1b 最核心的工具：上下文里没有展开的表，模型可以自己来问它，而不是反问用户
 * "请展开这张表"。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
public class DescribeTableTool implements AiTool {

    public static final String NAME = "describe_table";

    /** 每张表最多给出的列数 */
    private static final int MAX_COLUMNS = 40;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("tableName", AiToolSpec.stringProperty("必填，表名（区分大小写，与 list_tables 返回的完全一致）"));
        properties.set("schemaName", AiToolSpec.stringProperty("可选，schema / database 名；留空表示当前绑定的 schema"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("获取指定表的字段结构，含字段名、类型、注释与是否可空。"
                        + "当用户的提问涉及某张表的字段，而该表的结构尚未提供给你时使用。"
                        + "若不确定准确表名，请先用 list_tables 确认；表名不存在时会返回失败。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("tableName")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        if (context == null || context.getDataBase() == null) {
            return AiToolResult.failure("未绑定数据源，无法获取表结构", 0L);
        }
        String tableName = StrUtil.trimToNull(args == null ? null : args.getStr("tableName"));
        String schemaName = resolveSchema(args, context);
        if (tableName == null
                || !SAFE_NAME.matcher(tableName).matches()
                || !SAFE_NAME.matcher(schemaName).matches()) {
            return AiToolResult.failure("表名或 schema 名不合法，已拒绝执行", System.currentTimeMillis() - start);
        }
        StringBuilder sb = new StringBuilder();
        try (Driver driver = Driver.build(context.getDataBase().getDriverConfig())) {
            List<Column> columns = driver.listColumns(schemaName, tableName);
            if (CollUtil.isEmpty(columns)) {
                // 列为空基本等价于表不存在或无权限（多数 JDBC 驱动查不存在的表会直接抛异常，
                // 走到这里说明驱动返回了空）——统一给脱敏提示，不区分具体原因
                return AiToolResult.failure(
                        "未获取到表 " + schemaName + "." + tableName + " 的字段：表名可能不存在，或当前账号无权访问。" + "可用 list_tables 确认准确表名。",
                        System.currentTimeMillis() - start);
            }
            sb.append("Table: ")
                    .append(schemaName)
                    .append(".")
                    .append(tableName)
                    .append("\n");
            sb.append("  Columns:\n");
            int limit = Math.min(columns.size(), MAX_COLUMNS);
            for (int i = 0; i < limit; i++) {
                Column column = columns.get(i);
                sb.append("    - ").append(column.getName()).append(" ").append(StrUtil.nullToEmpty(column.getType()));
                if (column.isKeyFlag()) {
                    sb.append(" [PK]");
                }
                if (StrUtil.isNotBlank(column.getComment())) {
                    sb.append(" -- ").append(column.getComment());
                }
                sb.append("\n");
            }
            if (columns.size() > limit) {
                sb.append("    ... (共 ")
                        .append(columns.size())
                        .append(" 列，仅展示前 ")
                        .append(limit)
                        .append(" 列)\n");
            }
            appendRelations(sb, driver, schemaName, tableName);
            return AiToolResult.success(sb.toString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("describe_table failed, table: {}.{}, message: {}", schemaName, tableName, e.getMessage());
            return AiToolResult.failure(
                    "获取表 " + schemaName + "." + tableName + " 的字段失败：表名可能不存在，或当前账号无权访问。",
                    System.currentTimeMillis() - start);
        }
    }

    /** 外键关系属于加分项，部分数据源不支持失败时静默降级（与现有 schema 构建逻辑一致） */
    private void appendRelations(StringBuilder sb, Driver driver, String schemaName, String tableName) {
        try {
            TableRelations relations = driver.getTableRelations(schemaName, tableName);
            if (relations == null
                    || (CollUtil.isEmpty(relations.getForeignKeys())
                            && CollUtil.isEmpty(relations.getReferencedBy()))) {
                return;
            }
            sb.append("  Relations:\n");
            for (ForeignKey fk : relations.getForeignKeys()) {
                sb.append("    - FK ")
                        .append(tableName)
                        .append(".")
                        .append(join(fk.getColumns()))
                        .append(" -> ")
                        .append(fk.getRefTableName())
                        .append(".")
                        .append(join(fk.getRefColumns()))
                        .append("\n");
            }
            for (ForeignKey fk : relations.getReferencedBy()) {
                sb.append("    - FK ")
                        .append(fk.getTableName())
                        .append(".")
                        .append(join(fk.getColumns()))
                        .append(" -> ")
                        .append(tableName)
                        .append(".")
                        .append(join(fk.getRefColumns()))
                        .append("\n");
            }
        } catch (Exception e) {
            log.debug("Describe table relations failed, table: {}, message: {}", tableName, e.getMessage());
        }
    }

    private String join(List<String> values) {
        return values == null ? "" : String.join(",", values);
    }
}
