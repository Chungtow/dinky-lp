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
import org.dinky.ai.context.AiTaskBinding;
import org.dinky.ai.draft.FlinkJobDraft;
import org.dinky.ai.rule.RuleValidationService;

import java.util.Collections;

import org.springframework.stereotype.Component;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 校验 FlinkSQL 作业草稿（只读，默认可用）。
 *
 * <p><b>这是"审作业"的入口</b>：模型在给出作业前后都可以调它，拿到逐条结论
 * （{@code fail} / {@code unknown}），再决定改还是写。
 *
 * <p>只读工具<b>不需要二次确认</b>：它不碰任何写接口，最坏的结果是把 SQL 文本算错一遍。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateTaskDraftTool implements AiTool {

    public static final String NAME = "validate_task_draft";

    /** 与前端「校验当前 SQL」按钮共用同一实现，避免"按钮说通过、模型说违规" */
    private final RuleValidationService ruleValidationService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("sql", AiToolSpec.stringProperty("必填，完整 FlinkSQL（含 SET 头与 INSERT INTO）"));
        properties.set(
                "customConfig",
                arrayProperty("任务「自定义配置」列表，元素形如 {\"key\":\"execution.checkpointing.min-pause\",\"value\":\"10s\"}"));
        properties.set(
                "ddl",
                arrayProperty(
                        "目标库建表 DDL 列表，元素形如 {\"engine\":\"clickhouse\",\"database\":\"traccar\",\"table\":\"dws_x\",\"statement\":\"CREATE TABLE ...\"}"));
        properties.set(
                "precheck",
                arrayProperty("前置检查列表，元素形如 {\"item\":\"sink 表已存在\",\"how\":\"describe_tables\",\"status\":\"pass\"}"));
        properties.set("name", AiToolSpec.stringProperty("可选，作业名（用于校验 pipeline.name 是否与之一致）"));
        properties.set("parallelism", AiToolSpec.integerProperty("可选，任务并行度（用于校验 parallelism.default）"));
        properties.set("clusterId", AiToolSpec.integerProperty("可选，目标 Flink 集群 id"));
        properties.set("envId", AiToolSpec.integerProperty("可选，FlinkSQL 环境 id"));
        properties.set("taskId", AiToolSpec.integerProperty("可选，既有作业 id（更新场景）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("用规则库校验一段 FlinkSQL 作业草稿（只读、不写任何数据）。"
                        + "返回 {passed, counts, violations[]}：violations 里 status=fail 是明确违规（error 级会阻断写入），"
                        + "status=unknown 表示该项未能判定（缺输入或接口不可达），需要人确认。"
                        + "生成或修改 FlinkSQL 作业后应先调用本工具，再决定是否写入草稿。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("sql")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        FlinkJobDraft draft = FlinkJobDraft.fromJson(args);
        if (!draft.hasSql()) {
            return AiToolResult.failure("缺少必填参数 sql（完整 FlinkSQL 作业）", System.currentTimeMillis() - start);
        }
        applyServerBinding(draft, context);
        JSONObject report = ruleValidationService.validate(
                draft,
                context == null ? null : context.getTenantId(),
                context == null ? null : context.getRequestSnapshot());
        return AiToolResult.success(report.toString(), System.currentTimeMillis() - start);
    }

    /**
     * 用服务端的作业信息覆盖模型自报值。
     *
     * <p>动机见 {@link org.dinky.ai.context.AiTaskBinding}：不覆盖时，模型只要把 {@code name}
     * 传成自己写的 {@code pipeline.name}，{@code FLINK-PARAM-004} 就会"自证通过"。
     *
     * <p>只在<b>已存在作业</b>（binding 非空）时覆盖：新建作业场景没有权威作业名，模型取名是合法的。
     */
    private static void applyServerBinding(FlinkJobDraft draft, AiToolContext context) {
        AiTaskBinding binding = context == null ? null : context.getTaskBinding();
        if (binding == null) {
            return;
        }
        draft.setTaskId(binding.getTaskId());
        // 拿不到权威作业名时**清空**而不是沿用模型自报值：
        // 否则模型照旧可以自报一个与自己 pipeline.name 同名的作业名来"自证一致"。
        // 清空后由校验器判 unknown（已绑定作业但无法确认作业名），不会静默放过。
        draft.setName(binding.hasTaskName() ? binding.getTaskName() : null);
        if (binding.getParallelism() != null) {
            draft.setParallelism(binding.getParallelism());
        }
    }

    private static JSONObject arrayProperty(String description) {
        JSONObject property = new JSONObject();
        property.set("type", "array");
        property.set("description", description);
        property.set("items", new JSONObject().set("type", "object"));
        return property;
    }
}
