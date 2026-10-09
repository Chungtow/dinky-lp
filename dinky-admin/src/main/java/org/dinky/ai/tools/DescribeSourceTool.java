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
import org.dinky.ai.mention.SourceRefRenderer;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 获取【任意数据源】的库 / 表 / 字段结构（🟢 纯元数据；AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>与 {@code describe_table} 的分工</b>：后者只能看"当前作业绑定的那个数据源"，
 * 而 FlinkSQL 作业<b>没有数据源</b>（P0 结论）——但它恰恰需要同时看 MySQL 源表与 Doris sink 表。
 * 本工具提供 {@code @source/} 的"工具形态"：模型可自行按需拉取，不必要求用户先写引用。
 *
 * <p><b>与 {@code @source/} 共用同一实现</b>（{@link SourceRefRenderer}）：四级粒度、Flink DDL 骨架、
 * 失败分类文案完全一致——否则"用户引用"与"模型自查"会给出两种口径，模型很容易被自己带偏。
 *
 * <p><b>租户与线程</b>：数据源来自请求线程预解析的快照（{@code AiToolContext#getRequestSnapshot()}），
 * 工具线程只做"按名取对象 + 直连 driver"，故不依赖租户上下文。
 *
 * @since 2026/10/08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DescribeSourceTool implements AiTool {

    public static final String NAME = "describe_source";

    /** 参数白名单：与 {@code @source/} 的路径段一致（工具参数同样必须防注入） */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[\\p{L}\\p{N}_\\-.$]+");

    private final SourceRefRenderer sourceRefRenderer;

    @Override
    public String name() {
        return NAME;
    }

    /** P1：与 {@code @source/} 提及同一开关——关掉引用能力时，工具也不该继续对模型开放 */
    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config.isLlmMentionSourceEnable();
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("datasource", AiToolSpec.stringProperty("必填，数据源名（与数据源列表中的名称完全一致，区分大小写）"));
        properties.set("database", AiToolSpec.stringProperty("可选，库 / database 名；留空则只返回该数据源的库清单"));
        properties.set("table", AiToolSpec.stringProperty("可选，表名；留空则只返回该库的表清单"));
        properties.set("column", AiToolSpec.stringProperty("可选，字段名；填写后只返回该字段的明细"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("获取任意数据源的库 / 表 / 字段结构（四级粒度：数据源 → 库 → 表 → 字段），"
                        + "表级会附带可直接使用的 Flink DDL 骨架。"
                        + "当提问涉及【其它数据源】的表（例如为 FlinkSQL 作业准备 from / sink 表）时使用；"
                        + "当前作业已绑定数据源内的表请优先用 list_tables / describe_table。"
                        + "数据源或表不存在时会返回失败与可选值提示。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("datasource")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        Map<String, DataBase> sources =
                context == null ? null : context.getRequestSnapshot().getDataBasesByName();
        if (sources == null || sources.isEmpty()) {
            return AiToolResult.failure(
                    "本次对话没有可用的数据源快照，无法跨数据源查询。请让用户直接在输入框用 @source/ 引用目标表。", System.currentTimeMillis() - start);
        }
        String datasource = rawArg(args, "datasource");
        if (datasource == null) {
            return AiToolResult.failure("缺少必填参数 datasource（数据源名）", System.currentTimeMillis() - start);
        }
        String database = rawArg(args, "database");
        String table = rawArg(args, "table");
        String column = rawArg(args, "column");
        if (unsafe(datasource) || unsafe(database) || unsafe(table) || unsafe(column)) {
            return AiToolResult.failure("数据源名 / 库名 / 表名 / 字段名含非法字符，已拒绝执行", System.currentTimeMillis() - start);
        }
        StringBuilder path = new StringBuilder(datasource);
        if (database != null) {
            path.append('/').append(database);
        }
        if (table != null) {
            path.append('/').append(table);
        }
        if (column != null) {
            path.append('.').append(column);
        }
        String rendered = sourceRefRenderer.render(path.toString(), sources);
        // 渲染器用 "❌" 标记降级/失败（数据源不存在、库表不存在、连接失败…），此处转成工具失败语义，
        // 让模型知道"这条路走不通"，而不是把降级文案当成正常结构去用。
        if (rendered.contains("❌")) {
            log.info("describe_source degraded, path: {}", path);
            return AiToolResult.failure(rendered, System.currentTimeMillis() - start);
        }
        return AiToolResult.success(rendered, System.currentTimeMillis() - start);
    }

    /** 取原始参数（去空）；未填返回 {@code null} */
    private static String rawArg(JSONObject args, String key) {
        return args == null ? null : StrUtil.trimToNull(args.getStr(key));
    }

    /** 参数白名单校验：非法（含引号 / 空格 / 斜杠等）返回 true。工具参数来自模型，必须当作不可信输入。 */
    private static boolean unsafe(String segment) {
        return segment != null && !SAFE_SEGMENT.matcher(segment).matches();
    }
}
