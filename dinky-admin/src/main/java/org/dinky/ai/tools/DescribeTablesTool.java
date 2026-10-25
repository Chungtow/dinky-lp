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
import org.dinky.metadata.driver.Driver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量获取多张表的字段结构（🟢 纯元数据，2026-10-25 新增）。
 *
 * <p><b>为什么需要它</b>：大规模调研（"还有哪些表能组合成主题域"）需要一次看十来张表的结构，
 * 而 {@code describe_table} 一次只能查一张——模型会在<b>同一轮</b>里并发请求 N 个调用，既容易触顶
 * 单轮工具上限（2026-10-24 事故的直接触发点），也让一次 SSE 的等待被拉长。批量工具把 N 次调用
 * 压成 1 次：一次建连、顺序取列、单表失败不影响整体。
 *
 * <p><b>与 {@code describe_table} 的关系</b>：共用
 * {@link DescribeTableTool#renderTable} 的渲染格式，避免两套格式漂移——模型面对同一种对象
 * 必须拿到同一种描述，否则工具选择会变得不可预测。
 *
 * <p><b>不做的事</b>：不读业务数据（那是 {@code sample_rows}，默认不注册）；不做跨 schema 批量
 * （需要时调用两次，保持参数语义简单）。
 *
 * @since 2026/10/25
 */
@Slf4j
@Component
public class DescribeTablesTool implements AiTool {

    public static final String NAME = "describe_tables";

    /**
     * 单次最多几张表。
     *
     * <p>12 是经验值：调研型提问的典型规模是 5~10 张；再大则应拆分（结果整体还受
     * {@code sys.llm.settings.toolResultMaxChars} 约束，超出会被截断）。
     */
    private static final int MAX_TABLES = 12;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AiToolSpec spec() {
        JSONObject tableNames = new JSONObject();
        tableNames.set("type", "array");
        tableNames.set("description", "必填，要查询结构的表名列表，最多 " + MAX_TABLES + " 张（区分大小写，与 list_tables 返回的完全一致）");
        tableNames.set("items", AiToolSpec.stringProperty("表名"));
        JSONObject properties = new JSONObject();
        properties.set("tableNames", tableNames);
        properties.set("schemaName", AiToolSpec.stringProperty("可选，schema / database 名；留空表示当前绑定的 schema"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("批量获取多张表的字段结构（一次最多 " + MAX_TABLES + " 张）。"
                        + "当需要同时比较、关联或梳理多张表（例如设计主题域、判断表间能否串联）时优先使用本工具，"
                        + "一次调用即可拿到全部结构，不要为每张表单独调用 describe_table。"
                        + "不确定准确表名时，先用 list_tables 确认。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("tableNames")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        if (context == null || context.getDataBase() == null) {
            return AiToolResult.failure("未绑定数据源，无法获取表结构", 0L);
        }
        String schemaName = resolveSchema(args, context);
        if (!SAFE_NAME.matcher(schemaName).matches()) {
            return AiToolResult.failure("schema 名不合法，已拒绝执行", System.currentTimeMillis() - start);
        }
        Set<String> requested = parseTableNames(args);
        if (requested.isEmpty()) {
            return AiToolResult.failure(
                    "tableNames 为空或格式不合法：请给出 1~" + MAX_TABLES + " 个表名", System.currentTimeMillis() - start);
        }
        for (String tableName : requested) {
            if (!SAFE_NAME.matcher(tableName).matches()) {
                return AiToolResult.failure(
                        "表名不合法（" + StrUtil.sub(tableName, 0, 64) + "），已拒绝执行", System.currentTimeMillis() - start);
            }
        }

        boolean truncated = requested.size() > MAX_TABLES;
        List<String> targets = new ArrayList<>(requested);
        if (truncated) {
            targets = targets.subList(0, MAX_TABLES);
        }

        StringBuilder body = new StringBuilder();
        List<String> failed = new ArrayList<>();
        int ok = 0;
        try (Driver driver = Driver.build(context.getDataBase().getDriverConfig())) {
            for (String tableName : targets) {
                try {
                    String rendered = DescribeTableTool.renderTable(driver, schemaName, tableName);
                    if (rendered == null) {
                        failed.add(tableName + "（未获取到字段：表名可能不存在，或当前账号无权访问）");
                        continue;
                    }
                    ok++;
                    body.append(rendered);
                } catch (Exception e) {
                    // 单表失败不影响整体：批量工具的价值在于"能拿多少拿多少"
                    log.warn(
                            "describe_tables failed on one table, table: {}.{}, message: {}",
                            schemaName,
                            tableName,
                            e.getMessage());
                    failed.add(tableName + "（获取失败：表名可能不存在，或当前账号无权访问）");
                }
            }
        } catch (Exception e) {
            log.warn("describe_tables failed, schema: {}, message: {}", schemaName, e.getMessage());
            return AiToolResult.failure(
                    "批量获取表结构失败（schema: " + schemaName + "），请稍后重试或改用 describe_table 单表查询",
                    System.currentTimeMillis() - start);
        }

        if (ok == 0) {
            return AiToolResult.failure(
                    "未获取到任何表的结构（schema: " + schemaName + "）。可能表名不存在或当前账号无权访问；" + "可用 list_tables 确认准确表名。",
                    System.currentTimeMillis() - start);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tables: ")
                .append(ok)
                .append(" 张（schema: ")
                .append(schemaName)
                .append("）\n");
        if (truncated) {
            sb.append("  note: 一次最多 ")
                    .append(MAX_TABLES)
                    .append(" 张，其余 ")
                    .append(requested.size() - MAX_TABLES)
                    .append(" 张需再调用一次\n");
        }
        sb.append("\n").append(body);
        if (CollUtil.isNotEmpty(failed)) {
            sb.append("\n  Unavailable:\n");
            for (String item : failed) {
                sb.append("    - ").append(item).append("\n");
            }
        }
        return AiToolResult.success(sb.toString(), System.currentTimeMillis() - start);
    }

    /** 解析并去重 tableNames；非法 JSON / 非字符串元素一律忽略（由调用方判断"全空"） */
    private Set<String> parseTableNames(JSONObject args) {
        Set<String> names = new LinkedHashSet<>();
        if (args == null) {
            return names;
        }
        JSONArray array;
        try {
            array = args.getJSONArray("tableNames");
        } catch (Exception e) {
            return names;
        }
        if (array == null) {
            return names;
        }
        for (Object item : array) {
            String name = StrUtil.trimToNull(item == null ? null : String.valueOf(item));
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }
}
