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
import org.dinky.ai.draft.FlinkJobDraft;
import org.dinky.ai.draft.RuleReport;
import org.dinky.ai.draft.TrialRunGuard;
import org.dinky.ai.rule.FlinkSqlRuleEngine;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleViolation;
import org.dinky.config.Dialect;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.CatalogueTaskDTO;
import org.dinky.data.dto.TaskSubmitDto;
import org.dinky.data.enums.JobLifeCycle;
import org.dinky.data.ext.ConfigItem;
import org.dinky.data.model.Catalogue;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Task;
import org.dinky.data.model.ext.TaskExtConfig;
import org.dinky.job.JobResult;
import org.dinky.service.TaskService;
import org.dinky.service.catalogue.CatalogueService;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 把四段式草稿写成 Dinky 作业（写类工具，<b>默认关闭</b>）。
 *
 * <p><b>编排顺序不可变，每步幂等</b>（计划 §4.3）：
 * ① 校验器全量跑（有 error 直接失败，不调任何写接口）→ ② 目录/重名检查 →
 * ③ 建目录叶子 + 任务（{@code saveOrUpdateCatalogueAndTask}）→
 * ④ 补 type/cluster/env/parallelism/customConfig（{@code saveOrUpdateTask}）→
 * ⑤ 可选发布 → ⑥ 可选试跑（受节流约束）→ ⑦ runtime 断言。
 *
 * <p><b>③④ 不可合并</b>：{@code saveOrUpdateCatalogueAndTask} 的更新分支只覆盖
 * {@code statement}/{@code databaseId}/{@code enabled}，其余字段静默无效（反面清单 #24）。
 *
 * <p><b>不自动发布</b>：{@code publish}/{@code trial} 必须由调用方显式置 true，
 * 默认只落开发态——发布权归人（纲领 §十 #2）。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateTaskTool implements AiTool {

    public static final String NAME = "create_task";

    private final FlinkSqlRuleEngine ruleEngine;

    private final CatalogueService catalogueService;

    private final TaskService taskService;

    private final TrialRunGuard trialRunGuard;

    @Override
    public String name() {
        return NAME;
    }

    /** 写类工具默认关闭，与 exec_sql 同一保守口径 */
    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config.isLlmToolWriteTaskEnable();
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("sql", AiToolSpec.stringProperty("必填，完整 FlinkSQL（含 SET 头与 INSERT INTO）"));
        properties.set("name", AiToolSpec.stringProperty("必填，作业名（也会用于校验 pipeline.name 一致性）"));
        properties.set("parentId", AiToolSpec.integerProperty("必填，目标目录 id（目录树中的项目/文件夹）"));
        properties.set("envId", AiToolSpec.integerProperty("必填，FlinkSQL 环境 id"));
        properties.set("clusterId", AiToolSpec.integerProperty("必填，Flink 集群 id（须属于当前租户）"));
        properties.set("parallelism", AiToolSpec.integerProperty("可选，并行度，默认取环境值"));
        properties.set("customConfig", arrayObjectProperty("任务自定义配置列表 [{key,value}]"));
        properties.set("ddl", arrayObjectProperty("目标库建表 DDL 列表 [{engine,database,table,statement}]"));
        properties.set("precheck", arrayObjectProperty("前置检查列表 [{item,how,status}]"));
        properties.set("publish", booleanProperty("可选，是否发布（默认 false；发布权归人，仅用户明确要求时置 true）"));
        properties.set("trial", booleanProperty("可选，是否试跑（默认 false；受空闲 slot 与 5 分钟频次限制）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("把四段式 FlinkSQL 草稿写成一个 Dinky 作业（新建，不覆盖既有作业）。"
                        + "执行前会自动跑规则校验，存在 error 级违规时直接失败、不会写入。"
                        + "默认只落开发态、不发布不试跑；publish/trial 仅在用户明确要求时置 true。"
                        + "返回 taskId / catalogueId 与实际执行到哪一步。")
                .parameters(AiToolSpec.objectSchema(
                        null, properties, java.util.Arrays.asList("sql", "name", "parentId", "envId", "clusterId")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        FlinkJobDraft draft = FlinkJobDraft.fromJson(args);
        if (!draft.hasSql()) {
            return AiToolResult.failure("缺少必填参数 sql", System.currentTimeMillis() - start);
        }
        Integer parentId = args.getInt("parentId");
        if (parentId == null) {
            return AiToolResult.failure("缺少必填参数 parentId（目标目录 id）", System.currentTimeMillis() - start);
        }
        String name = StrUtil.blankToDefault(draft.getName(), StrUtil.trimToNull(args.getStr("name")));
        if (StrUtil.isBlank(name)) {
            return AiToolResult.failure("缺少必填参数 name（作业名）", System.currentTimeMillis() - start);
        }
        boolean publish = args.getBool("publish", false);
        boolean trial = args.getBool("trial", false);

        RuleContext ruleContext = draft.toRuleContext();
        ruleContext.setTaskName(name);
        if (context != null) {
            ruleContext.setTenantId(context.getTenantId());
            ruleContext.setRequestSnapshot(context.getRequestSnapshot());
        }

        // ① 校验：有 error 直接失败，不调任何写接口
        List<RuleViolation> violations = ruleEngine.validate(ruleContext);
        ruleEngine.sort(violations);
        if (ruleEngine.hasBlocking(violations)) {
            JSONObject report = RuleReport.build(RuleReport.meaningful(violations));
            return AiToolResult.failure("规则校验未通过，未写入任何数据: " + report, System.currentTimeMillis() - start);
        }

        JSONObject result = new JSONObject();
        result.set("name", name);
        result.set("warnings", RuleReport.build(RuleReport.meaningful(violations)));

        Integer originTenant = null;
        try {
            // ② 目录 / 重名检查
            Catalogue exists = catalogueService.findByParentIdAndName(parentId, name);
            if (exists != null) {
                return AiToolResult.failure("同一目录下已存在同名作业/目录: " + name, System.currentTimeMillis() - start);
            }
            // ③ 建目录叶子 + 任务
            CatalogueTaskDTO dto = new CatalogueTaskDTO();
            dto.setParentId(parentId);
            dto.setName(name);
            dto.setType(StrUtil.blankToDefault(args.getStr("type"), "FlinkSql"));
            dto.setDialect(Dialect.FLINK_SQL.getValue());
            dto.setLeaf(true);
            dto.setNote("AI Chat 创建（规则校验通过）");
            dto.setConfigJson(new TaskExtConfig());
            if (context != null && context.getTenantId() != null) {
                originTenant = (Integer) TenantContextHolder.get();
                TenantContextHolder.set(context.getTenantId());
                dto.setTenantId(context.getTenantId());
            }
            Catalogue catalogue = catalogueService.saveOrUpdateCatalogueAndTask(dto);
            if (catalogue == null || catalogue.getTaskId() == null) {
                return AiToolResult.failure("目录/任务创建失败（未返回 taskId）", System.currentTimeMillis() - start);
            }
            Integer taskId = catalogue.getTaskId();
            result.set("taskId", taskId);
            result.set("catalogueId", catalogue.getId());

            // ④ 补字段（③ 只落 statement，其余字段必须单独更新）
            Task task = new Task();
            task.setId(taskId);
            task.setStatement(draft.getSql());
            task.setDialect(Dialect.FLINK_SQL.getValue());
            task.setType(dto.getType());
            task.setClusterId(draft.getClusterId());
            task.setEnvId(draft.getEnvId());
            task.setParallelism(draft.getParallelism());
            task.setStep(JobLifeCycle.DEVELOP.getValue());
            if (context != null && context.getTenantId() != null) {
                task.setTenantId(context.getTenantId());
            }
            TaskExtConfig extConfig = new TaskExtConfig();
            for (Map.Entry<String, String> entry : draft.getCustomConfig().entrySet()) {
                if (StrUtil.isNotBlank(entry.getKey())) {
                    extConfig.getCustomConfig().add(new ConfigItem(entry.getKey(), entry.getValue()));
                }
            }
            task.setConfigJson(extConfig);
            if (!taskService.saveOrUpdateTask(task)) {
                return AiToolResult.failure("任务字段更新失败（taskId=" + taskId + "）", System.currentTimeMillis() - start);
            }

            // ⑤ 发布（可选）
            if (publish || trial) {
                taskService.changeTaskLifeRecyle(taskId, JobLifeCycle.PUBLISH);
                result.set("published", true);
            } else {
                result.set("published", false);
            }

            // ⑥ 试跑（可选，受节流约束）
            if (trial) {
                Integer userId = context == null ? null : context.getUserId();
                TrialRunGuard.Result guard = trialRunGuard.check(userId);
                if (!guard.isAllowed()) {
                    result.set("trial", false);
                    result.set("trialRejected", guard.getReason());
                } else {
                    trialRunGuard.onStart(userId);
                    try {
                        TaskSubmitDto submitDto = new TaskSubmitDto();
                        submitDto.setId(taskId);
                        submitDto.setIsOnline(true);
                        JobResult jobResult = taskService.submitTask(submitDto);
                        result.set("trial", true);
                        result.set("jobInstanceId", jobResult == null ? null : jobResult.getJobInstanceId());
                        // ⑦ runtime 断言（拿不到 job id 时由规则自己判"不适用"）
                        RuleContext runtimeContext = ruleContext;
                        runtimeContext.setTaskId(taskId);
                        List<RuleViolation> runtimeViolations = ruleEngine.assertRuntime(runtimeContext);
                        result.set("runtime", RuleReport.build(RuleReport.meaningful(runtimeViolations)));
                    } finally {
                        trialRunGuard.onFinish(userId);
                    }
                }
            }
            result.set("success", true);
            return AiToolResult.success(result.toString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("create_task failed: {}", name, e);
            return AiToolResult.failure(
                    "创建作业失败: " + StrUtil.maxLength(String.valueOf(e.getMessage()), 200),
                    System.currentTimeMillis() - start);
        } finally {
            if (originTenant != null) {
                TenantContextHolder.set(originTenant);
            } else if (context != null && context.getTenantId() != null) {
                TenantContextHolder.clear();
            }
        }
    }

    private static JSONObject arrayObjectProperty(String description) {
        JSONObject property = new JSONObject();
        property.set("type", "array");
        property.set("description", description);
        property.set("items", new JSONObject().set("type", "object"));
        return property;
    }

    private static JSONObject booleanProperty(String description) {
        JSONObject property = new JSONObject();
        property.set("type", "boolean");
        property.set("description", description);
        return property;
    }
}
