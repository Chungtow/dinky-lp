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

import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 列出数据源下的表清单（🟢 纯元数据）。
 *
 * <p><b>为什么不用 {@code DataBaseService#getTables}</b>：该方法内部会 {@code getById(id)}，
 * 而 dinky 的租户过滤基于 ThreadLocal，工具循环跑在异步线程里，反查会查不到数据源
 * （表现为"数据源不存在"）。这里直接使用上下文中<b>已解析好</b>的 DataBase 对象建连接。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
public class ListTablesTool implements AiTool {

    public static final String NAME = "list_tables";

    /** 表名清单上限：清单本身不是答案，过长既费 token 又挤占上下文 */
    private static final int MAX_TABLES = 300;

    /** 合法标识符：工具必须自己校验，不能把模型给的字符串直接拼进元数据调用 */
    static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("schemaName", AiToolSpec.stringProperty("可选，schema / database 名；留空表示用户在编辑器中当前绑定的 schema"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("列出指定数据源（或其某个 schema）下的所有表名。当用户问'有哪些表'、" + "或需要确认某张表的准确名称时使用。只返回表名清单，不含字段信息。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.<String>emptyList()))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        if (context == null || context.getDataBase() == null) {
            return AiToolResult.failure("未绑定数据源，无法查询表清单", 0L);
        }
        String schemaName = resolveSchema(args, context);
        if (!SAFE_NAME.matcher(schemaName).matches()) {
            return AiToolResult.failure("schema 名不合法，已拒绝执行", System.currentTimeMillis() - start);
        }
        try (Driver driver = Driver.build(context.getDataBase().getDriverConfig())) {
            List<Table> tables = driver.listTables(schemaName);
            if (CollUtil.isEmpty(tables)) {
                return AiToolResult.success(
                        "未获取到任何表（schema: " + schemaName + "）。可能该 schema 下确无表，或当前账号无权查看。",
                        System.currentTimeMillis() - start);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("schema ")
                    .append(schemaName)
                    .append(" 下共 ")
                    .append(tables.size())
                    .append(" 张表");
            int limit = Math.min(tables.size(), MAX_TABLES);
            if (limit < tables.size()) {
                sb.append("（仅列出前 ").append(limit).append(" 张）");
            }
            sb.append("：\n");
            for (int i = 0; i < limit; i++) {
                Table table = tables.get(i);
                sb.append("- ").append(table.getName());
                if (StrUtil.isNotBlank(table.getComment())) {
                    sb.append(" -- ").append(table.getComment());
                }
                sb.append("\n");
            }
            return AiToolResult.success(sb.toString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("list_tables failed, schema: {}, message: {}", schemaName, e.getMessage());
            return AiToolResult.failure("查询表清单失败：请确认数据源可连通、schema 名正确，或改用其他方式回答", System.currentTimeMillis() - start);
        }
    }

    /** 模型未给出 schema 时回退到会话绑定的 schema */
    static String resolveSchema(JSONObject args, AiToolContext context) {
        String schemaName = args == null ? null : StrUtil.trimToNull(args.getStr("schemaName"));
        if (schemaName != null) {
            return schemaName;
        }
        return StrUtil.nullToEmpty(context == null ? null : context.getSchemaName());
    }
}
