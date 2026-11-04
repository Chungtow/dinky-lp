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

package org.dinky.controller;

import org.dinky.ai.context.AiTaskBinding;
import org.dinky.ai.context.AiTaskBindingResolver;
import org.dinky.ai.context.NameRegistryService;
import org.dinky.ai.draft.FlinkJobDraft;
import org.dinky.ai.fewshot.SimilarTasksQuery;
import org.dinky.ai.fewshot.SimilarTasksService;
import org.dinky.ai.kafka.KafkaTopicCatalog;
import org.dinky.ai.rule.RuleValidationService;
import org.dinky.config.Dialect;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.constant.PermissionConstants;
import org.dinky.data.dto.AiChatConfirmRequest;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.dto.AiChatWriteAuditRequest;
import org.dinky.data.dto.AiValidateDraftRequest;
import org.dinky.data.model.Task;
import org.dinky.data.result.Result;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.data.vo.AiChatContextPreview;
import org.dinky.service.AiChatService;
import org.dinky.service.TaskService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI Chat Controller
 *
 * <p>提供数据开发场景下的 AI 对话能力（Text-to-SQL / Explain），以 SSE 流式返回。
 *
 * <p>2026-10-14 菜单与权限登记缺口修复（分级权限）：
 *
 * <ul>
 *   <li>{@code aiChat:use} —— 面板使用：对话（{@code /chat}）、配置查询（{@code /config}）、
 *       {@code @topic/} 候选（{@code /mentionTopics}）；
 *   <li>{@code aiChat:write} —— <b>落盘闸门</b>：写审计（{@code /write-audit}）、二次确认
 *       （{@code /confirm}）、中断（{@code /cancel}）。
 * </ul>
 *
 * <p>之所以把权限挂在闸门而非 {@code /chat} 内部：{@code /chat} 单次请求内可能既产出只读建议、
 * 也可能产出待确认的写提案，接口级注解无法按请求参数细分；而阶段 2c 已实现"写语句必须人工确认"，
 * 因此在 {@code /confirm} 等闸门接口上收紧即可做到"能对话、不能落盘"。
 *
 * @since 2026/09/26
 */
@Slf4j
@RestController
@Api(tags = "AI Chat Controller")
@RequestMapping("/api/aiChat")
@RequiredArgsConstructor
@SaCheckLogin
public class AiChatController {

    private final AiChatService aiChatService;

    /** P1-A：{@code @topic/} 候选来自命名名册（零连接 Kafka） */
    private final NameRegistryService nameRegistryService;

    /** P1（2026-11-04）：{@code @topic/} 候选的 broker 实时来源（与名册取并集） */
    private final KafkaTopicCatalog kafkaTopicCatalog;

    /** P2（2026-11-04）：{@code @job/} 候选的作业清单来源 */
    private final TaskService taskService;

    /** M1-C：few-shot 召回（L1 静态语料 + L2 本租户线上作业） */
    private final SimilarTasksService similarTasksService;

    /** 规则校验（前端「校验当前 SQL」按钮直连，与模型工具共用同一份实现） */
    private final RuleValidationService ruleValidationService;

    /** 作业权威信息解析（作业名 / 并行度），用于覆盖请求体里可能过时或自报的值 */
    private final AiTaskBindingResolver taskBindingResolver;

    /**
     * 发起对话，流式返回（SSE）。
     *
     * @param request {@link AiChatRequest}
     * @return {@link SseEmitter}
     */
    @PostMapping("/chat")
    @ApiOperation("Chat With AI (SSE Stream)")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public SseEmitter chat(@RequestBody AiChatRequest request) {
        if (request != null) {
            try {
                // 同步线程中取登录用户并覆盖，避免前端伪造 userId 绕过限流/配额
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI chat failed: {}", e.getMessage());
            }
        }
        return aiChatService.chat(request);
    }

    /**
     * 查询 AI 能力配置状态（不含密钥）。
     *
     * @return {@link Result}<{@link AiChatConfig}>
     */
    @GetMapping("/config")
    @ApiOperation("Query AI Chat Config")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<AiChatConfig> getConfig() {
        return Result.succeed(aiChatService.getConfig());
    }

    /**
     * 预览会注入给模型的 FlinkSQL 上下文（**只读、不调模型、不写任何数据**）。
     *
     * <p>存在的意义是"自证"：上下文是不是给到位了，过去只能让模型原样贴出来（M1-17），
     * 既费 token 又会被模型改写。这里直接返回装配结果 + 段落清单 + 7 段自检，
     * UAT 断言 {@code allPass=true} 即可；排查"模型为什么编 connector"时也能区分
     * "没注入"与"注入了没遵守"。
     *
     * <p>注意：{@code taskId} 为空是可测的**正常场景**（新建 / 未保存的 tab）——
     * 此时框架事实（能力 / 默认 / 规则 / 协议）必须仍然齐全。
     *
     * @param taskId  作业 id（可空）
     * @param dialect 方言（为空时按 FlinkSql 处理；非 FlinkSql 返回空上下文）
     * @return {@link Result}<{@link AiChatContextPreview}>
     */
    @GetMapping("/context")
    @ApiOperation("Preview Injected FlinkSQL Context (read-only)")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<AiChatContextPreview> previewContext(
            @RequestParam(value = "taskId", required = false) Integer taskId,
            @RequestParam(value = "dialect", required = false) String dialect) {
        return Result.succeed(aiChatService.previewFlinkContext(taskId, dialect));
    }

    /**
     * 用规则库校验一段 FlinkSQL 草稿（**只读、不调模型、不写任何数据**）。
     *
     * <p><b>为什么必须有不经过模型的入口</b>：UAT 的"审作业零误报"判据是规则引擎行为。
     * 只靠模型转述会失真——2026-11-03 实测出现过 {@code tool_call_count=0}（模型压根没调工具）
     * 却照样输出"命中/未命中规则"的结论，测试无法据此判定。
     *
     * <p>返回结构与 {@code validate_task_draft} 工具一致：{@code {passed, counts, violations[]}}。
     *
     * @param request 草稿（sql 必填，其余可选）
     * @return 校验报告
     */
    @PostMapping("/validateDraft")
    @ApiOperation("Validate FlinkSQL Draft With Rule Engine (No LLM)")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<Object> validateDraft(@RequestBody AiValidateDraftRequest request) {
        Integer tenantId = Convert.toInt(TenantContextHolder.get(), null);
        FlinkJobDraft draft = request.toDraft();
        // 作业名 / 并行度以服务端为准：前端可能传旧值，脚本调用方更可能是"自报"。
        // 查不到（作业已删 / 无租户）时降级为请求体原值，不阻断校验。
        AiTaskBinding binding = taskBindingResolver.resolve(request.getTaskId());
        if (binding != null) {
            draft.setTaskId(binding.getTaskId());
            // 拿不到权威作业名时清空，避免"自报同名绕过"（校验器会判 unknown）
            draft.setName(binding.hasTaskName() ? binding.getTaskName() : null);
            if (binding.getParallelism() != null) {
                draft.setParallelism(binding.getParallelism());
            }
        }
        return Result.succeed(ruleValidationService.validate(draft, tenantId));
    }

    /**
     * few-shot 召回：按表 / topic / 关键词返回最相似的作业骨架（M1-C）。
     *
     * <p>只读、不调模型：召回走本地打分，返回体已脱敏（口令类字面量打码）。
     *
     * @param query 召回条件（全部可选）
     * @return {@link Result}<{@link Object}> 形如 {@code {items[], candidates:{l1,l2}}}
     */
    @PostMapping("/similarTasks")
    @ApiOperation("Recall Similar FlinkSQL Tasks (few-shot)")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<Object> similarTasks(@RequestBody(required = false) SimilarTasksQuery query) {
        return Result.succeed(similarTasksService.recall(query));
    }

    /**
     * 查询 {@code @topic/} 的候选列表（P1-A · 名册版）。
     *
     * <p><b>为什么需要这个接口</b>：前端「{@code @} 候选浮层」只能拿到它已知的数据（当前面板数据源的库表、
     * 已打开的作业、skill 资产…），而 topic 清单只存在于服务端的名册里。不给接口就只能靠手打，
     * 用户体验上就是“输入 {@code @topic/} 什么都不出”。
     *
     * <p><b>为什么还是零连接</b>：取值全部来自命名名册（{@code dinky_task} 里本租户
     * {@code FlinkSql}/{@code FlinkSqlEnv} 作业的 DDL），不提交任何 Kafka 请求。因此候选只覆盖
     * <b>已被作业使用</b>的 topic；“平台里存在但无任何作业消费”的 topic 需等批次 2 的
     * AdminClient（{@code list_topics}）。
     *
     * <p>这里故意<b>不做</b> {@code mentionSourceEnable} 开关门控：该开关的口径是“{@code @source/} +
     * {@code describe_source} / {@code check_name_conflict}”，而 {@code @topic/} 在批次 1 一直可用（同其渲染器）。
     *
     * @return 已占用（即已被作业引用）的 topic 名清单（本租户）
     */
    @GetMapping("/mentionTopics")
    @ApiOperation("List topic candidates for @topic mentions (broker enumeration merged with name registry)")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<List<String>> listMentionTopics() {
        // 2026-11-04（P1）：候选口径 = **broker 实时列举**。
        //
        // 为什么不并名册：名册是"从作业 DDL 里提取到的 topic 名"，它记录的是**有人这么写过**，
        // 不是**这个 topic 真的存在**。实测作业 #128 曾写下 'topic' = 'ods_traccar_tc_events'
        // （模型把 Hive 表名当 topic），并集把这颗脏数据一起带进了候选——
        // 用户看到的就是一个根本不存在的 topic，点选后写出来的作业必然空跑。
        // 所以：broker 可达 → 只认 broker；不可达 → 才退回名册（并如实说明这是登记值）。
        List<KafkaTopicCatalog.TopicInfo> topics = null;
        try {
            topics = kafkaTopicCatalog.externalTopics();
        } catch (Exception e) {
            log.debug("mentionTopics: enumerate kafka topics failed: {}", e.getMessage());
        }
        if (topics != null) {
            List<String> names = new ArrayList<>();
            for (KafkaTopicCatalog.TopicInfo topic : topics) {
                names.add(topic.getName());
            }
            return Result.succeed(names);
        }
        List<String> registered = nameRegistryService.getSnapshot().get("kafkaTopic");
        return Result.succeed(registered == null ? new ArrayList<>() : registered);
    }

    /**
     * {@code @job/} 候选（P2，2026-11-04）：本租户 FlinkSQL 作业清单。
     *
     * <p>只返回 {@code id + name}，不返回 SQL 正文——候选列表不需要正文，
     * 选了哪个由后端在装配上下文时按名/ id 去查（见 {@code JobRefRenderer}），避免把一堆 SQL 塞进前端。
     *
     * @return 作业清单（按更新时间倒序，最近改过的排在前面）
     */
    @GetMapping("/mentionJobs")
    @ApiOperation("List FlinkSQL job candidates for @job mentions")
    @SaCheckPermission(PermissionConstants.AI_CHAT_USE)
    public Result<List<Map<String, Object>>> listMentionJobs() {
        // 2026-11-04 修：dinky_task 在 MybatisPlusConfig.IGNORE_TABLE_NAMES 里，会被
        // TenantLineInnerInterceptor 拼上 `tenant_id = ?`；而本接口线程里 TenantContextHolder 为空
        // （登录态没带租户），`getTenantId()` 返回 NullValue → 条件是 `tenant_id = NULL`，
        // 恒不成立 → **永远查不到任何作业**，且不报错，前端只看到空候选。
        // 处理：查询期间显式忽略租户拦截；若有租户上下文则自行按它过滤（"当前租户下的作业"）。
        Object currentTenant = TenantContextHolder.get();
        boolean wasIgnoring = TenantContextHolder.isIgnoreTenant();
        List<Task> tasks;
        try {
            TenantContextHolder.ignoreTenant();
            LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
            // 2026-11-04 修：原先写死 dialect='FlinkSql'，把 Hive / DataX / SparkSql / Mysql / Presto /
            // Shell / ClickHouse / Doris 全滤掉了。而 @job/ 的语义是"引用任意作业当参考"——
            // 用户要照 DataX 作业改一个同步任务、照 Hive 作业改一个统计口径，都是正常诉求。
            // 唯一排除 FlinkSqlEnv：那是环境配置（SET catalog/password 之类），不是作业本身，
            // 注入进来只会误导（且它更适合走 @env/）。
            wrapper.ne(Task::getDialect, Dialect.FLINK_SQL_ENV.getValue());
            if (currentTenant != null) {
                wrapper.eq(Task::getTenantId, currentTenant);
            }
            // 上限 300：本集群总计 100+ 个作业，100 会截断（按更新时间倒序，旧的永远出不来）
            wrapper.orderByDesc(Task::getUpdateTime).last("limit 300");
            tasks = taskService.getBaseMapper().selectList(wrapper);
        } finally {
            if (!wasIgnoring) {
                TenantContextHolder.clearIgnoreTenant();
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (tasks == null) {
            return Result.succeed(result);
        }
        for (Task task : tasks) {
            if (task == null || StrUtil.isBlank(task.getName())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.getId());
            item.put("name", task.getName());
            // 带上方言：候选里同时有 Hive / DataX / SparkSql 等同名或近似名作业时，
            // 不带类型根本分不清该选哪个
            item.put("dialect", task.getDialect());
            result.add(item);
        }
        return Result.succeed(result);
    }

    /**
     * 记录 Craft 写入审计（阶段 2：T2-5）。
     *
     * <p><b>只记录，不写作业</b>：写入动作已在前端完成，这里补的是"谁在什么时候让 AI 改了哪个作业"，
     * 是放开写能力后唯一的事后追溯手段。userId 由服务端覆盖，不接受前端传入。
     *
     * @param request {@link AiChatWriteAuditRequest}
     * @return {@link Result}
     */
    @PostMapping("/write-audit")
    @ApiOperation("Record AI Craft Write Audit")
    @SaCheckPermission(PermissionConstants.AI_CHAT_WRITE)
    public Result<Void> writeAudit(@RequestBody AiChatWriteAuditRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI craft audit failed: {}", e.getMessage());
            }
        }
        aiChatService.recordCraftWrite(request);
        return Result.succeed();
    }

    /**
     * 二次确认：写语句（DML / DDL）是否执行（阶段 2c-0）。
     *
     * <p>AI 生成写语句时后端<b>挂起等待</b>，前端弹确认框后调用本端点投递结果；未确认不执行。
     * userId 由服务端覆盖，且仅创建该 runId 的用户可确认。
     *
     * @param request {@link AiChatConfirmRequest}
     * @return 投递是否成功
     */
    @PostMapping("/confirm")
    @ApiOperation("Confirm AI Write Execution")
    @SaCheckPermission(PermissionConstants.AI_CHAT_WRITE)
    public Result<Void> confirm(@RequestBody AiChatConfirmRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI confirm failed: {}", e.getMessage());
            }
        }
        boolean accepted = aiChatService.confirmRun(request);
        return accepted ? Result.succeed() : Result.failed("确认请求已失效（运行已结束或无权限）");
    }

    /**
     * 中断本次运行（阶段 2c-0）。
     *
     * <p>与前端「停止」按钮配合：不再只是断开 SSE HTTP 流，而是让服务端工具循环/正在等待的
     * 确认真正收到取消信号。
     *
     * @param request {@link AiChatConfirmRequest}（只用 runId）
     * @return 取消是否被接受
     */
    @PostMapping("/cancel")
    @ApiOperation("Cancel AI Chat Run")
    @SaCheckPermission(PermissionConstants.AI_CHAT_WRITE)
    public Result<Void> cancel(@RequestBody AiChatConfirmRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI cancel failed: {}", e.getMessage());
            }
        }
        boolean accepted = aiChatService.cancelRun(request);
        return accepted ? Result.succeed() : Result.failed("取消失败（运行已结束或无权限）");
    }
}
