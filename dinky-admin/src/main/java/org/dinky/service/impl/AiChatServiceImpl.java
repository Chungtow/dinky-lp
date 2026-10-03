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

package org.dinky.service.impl;

import org.dinky.ai.AiChatRateLimiter;
import org.dinky.ai.AiChatRunRegistry;
import org.dinky.ai.AiToolCall;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolLoop;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolRunResult;
import org.dinky.ai.ChangeRisk;
import org.dinky.ai.LlmClient;
import org.dinky.ai.PromptStore;
import org.dinky.ai.SqlVerifier;
import org.dinky.ai.TableSelector;
import org.dinky.ai.TokenUsage;
import org.dinky.data.dto.AiChatConfirmRequest;
import org.dinky.data.dto.AiChatMention;
import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.dto.AiChatWriteAuditRequest;
import org.dinky.data.model.AiChatLog;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.ForeignKey;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.data.model.TableRelations;
import org.dinky.data.model.job.History;
import org.dinky.data.model.job.JobInstance;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatLogService;
import org.dinky.service.AiChatService;
import org.dinky.service.DataBaseService;
import org.dinky.service.HistoryService;
import org.dinky.service.JobInstanceService;
import org.dinky.sse.SseEmitterUTF8;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI Chat 服务实现。
 *
 * <p><b>安全红线</b>：组装给大模型的上下文<strong>只有元数据</strong>（表名 / 列名 / 类型 / 注释 / 外键关系），
 * 不包含任何业务数据行。
 *
 * @since 2026/09/26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final String ACTION_TEXT_TO_SQL = "TEXT_TO_SQL";
    private static final String ACTION_EXPLAIN = "EXPLAIN";
    /** 阶段 2b 局部改写：基于最近执行报错修复「用户选中的 SQL」 */
    private static final String ACTION_FIX_SQL = "FIX_SQL";
    /** 阶段 2b 局部改写：综合优化改写「用户选中的 SQL」 */
    private static final String ACTION_REWRITE_SQL = "REWRITE_SQL";
    /** 阶段 2 双模式：Craft（可改写编辑器内容）；Ask 为默认，不单独定义常量 */
    private static final String MODE_CRAFT = "craft";
    /** 阶段 2 审计动作：AI 整块改写编辑器内容 */
    private static final String ACTION_CRAFT_WRITE = "CRAFT_WRITE";

    /** 表清单最多列出的表名数量（表名很短，尽量全列，否则模型看不到目标表） */
    private static final int MAX_TABLE_LIST = 300;
    /** 每张表最多包含的列数量 */
    private static final int MAX_COLUMNS_PER_TABLE = 40;
    /**
     * 以下三项<strong>已于阶段 1a 迁移为可配置项</strong>（`sys.llm.settings.*`，见
     * {@link SystemConfiguration#getLlmColumnBudgetChars()}），此处的常量仅保留原默认值作为文档说明，
     * 代码中不再引用：
     * <ul>
     *   <li>{@code COLUMN_BUDGET_CHARS = 20000}：字段详情的字符预算（小于 schemaMaxChars，给表清单留余量）</li>
     *   <li>{@code MAX_SCHEMA_CHARS = 24000}：schema 区块总上限（兜底，避免 token 爆炸）</li>
     *   <li>{@code MAX_EDITOR_SQL_CHARS = 6000}：编辑区内容上限</li>
     * </ul>
     * 调大前请先跑评测集（见阶段 1 计划 §3.4.5）：预算并非越大越好，需权衡准确率、p95 延迟与成本。
     */
    @SuppressWarnings("unused")
    private static final int COLUMN_BUDGET_CHARS = 20000;

    /** 注入上下文的作业报错原文字符上限 */
    private static final int MAX_JOB_ERROR_CHARS = 1500;
    /** 最多携带的历史对话轮次（一问一答算一轮，此处按消息条数算） */
    private static final int MAX_HISTORY_MESSAGES = 10;
    /** 单个工具返回给模型的字符上限（超长清单既费 token 又挤占上下文） */
    private static final int MAX_TOOL_RESULT_CHARS = 8000;
    /** 审计列 tool_calls 的字符上限 */
    private static final int MAX_TOOL_AUDIT_CHARS = 2000;
    /** 工具参数在一帧里展示的字符上限 */
    private static final int MAX_TOOL_ARGS_CHARS = 60;
    /** 阶段 2c-0：写语句二次确认的等待超时（秒），超时按「拒绝」处理；常量统一由 {@link AiChatRunRegistry} 持有 */
    private static final int WRITE_CONFIRM_TIMEOUT_SECONDS = AiChatRunRegistry.CONFIRM_TIMEOUT_SECONDS;

    private final DataBaseService dataBaseService;
    private final LlmClient llmClient;
    private final SqlVerifier sqlVerifier;
    private final AiChatRateLimiter rateLimiter;
    private final AiChatRunRegistry runRegistry;
    private final AiChatLogService aiChatLogService;
    private final JobInstanceService jobInstanceService;
    private final HistoryService historyService;
    private final AiToolLoop toolLoop;

    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    @Override
    public SseEmitter chat(AiChatRequest request) {
        SystemConfiguration chatConfig = SystemConfiguration.getInstances();
        int timeoutSeconds = Math.max(chatConfig.getLlmTimeout(), 1);
        // 工具循环会把「多轮 LLM 请求」与「多次工具执行」串接起来，原先「单次 LLM 超时 + 30s」
        // 的 emitter 超时已不再够用，需按轮数与工具超时放大
        int toolRounds = Math.max(chatConfig.getLlmToolCallMaxRounds(), 1);
        int toolTimeout = Math.max(chatConfig.getLlmToolTimeoutSeconds(), 1);
        long emitterTimeoutMs = chatConfig.isLlmToolCallEnable()
                ? (timeoutSeconds * (toolRounds + 1L) + toolTimeout * (long) toolRounds + 30L) * 1000L
                : (timeoutSeconds + 30L) * 1000L;
        SseEmitter emitter = new SseEmitterUTF8(emitterTimeoutMs);
        // 数据源必须在请求线程中解析：对话跑在异步线程，此时租户上下文（ThreadLocal）已丢失，
        // 再按 id 查询会因租户过滤而查不到数据源（此前表现为"数据源不存在"）。
        DataBase dataBase = resolveDataBase(request);
        // schema 也在请求线程构建：元数据接口内部按 id 反查数据源，同样依赖租户上下文
        String built = null;
        try {
            if (request != null) {
                built = buildSchemaContext(request);
            }
        } catch (Exception e) {
            log.warn("Build schema context before stream failed", e);
        }
        final String schemaContext = built;
        chatExecutor.execute(() -> doChat(request, emitter, dataBase, schemaContext));
        return emitter;
    }

    /** 解析当前作业绑定的数据源（失败时返回 null，由 SqlVerifier 给出明确提示） */
    private DataBase resolveDataBase(AiChatRequest request) {
        if (request == null || request.getDatabaseId() == null) {
            return null;
        }
        try {
            return dataBaseService.getById(request.getDatabaseId());
        } catch (Exception e) {
            log.warn("Resolve data base failed, databaseId: {}", request.getDatabaseId(), e);
            return null;
        }
    }

    @Override
    public AiChatConfig getConfig() {
        SystemConfiguration config = SystemConfiguration.getInstances();
        return AiChatConfig.builder()
                .enable(config.isLlmEnable())
                .model(config.getLlmModel())
                .baseUrl(config.getLlmBaseUrl())
                .hasApiKey(StrUtil.isNotBlank(config.getLlmApiKey()))
                .craftModeEnable(config.isLlmCraftModeEnable())
                .build();
    }

    /** 实际对话逻辑（在异步线程中执行） */
    private void doChat(AiChatRequest request, SseEmitter emitter, DataBase dataBase, String prebuiltSchemaContext) {
        long start = System.currentTimeMillis();
        TokenUsage totalUsage = new TokenUsage();
        AiChatLog audit = new AiChatLog();
        // 阶段 2c-0：运行 id（承载二次确认与服务端中断），finally 中清理
        String runId = null;
        try {
            SystemConfiguration config = SystemConfiguration.getInstances();
            if (!config.isLlmEnable()) {
                sendFrame(emitter, "error", "AI 能力未启用：请先在【配置中心 - 全局设置 - LLM 配置】中开启并配置模型服务。");
                emitter.complete();
                return;
            }
            if (request == null) {
                sendFrame(emitter, "error", "请求参数为空。");
                emitter.complete();
                return;
            }

            Integer userId = request.getUserId();
            // 阶段 2c-0：建立运行上下文并下发 runId（前端凭它做二次确认 / 服务端中断）
            runId = UUID.randomUUID().toString();
            final AiChatRunRegistry.RunContext run = runRegistry.create(runId, userId);
            sendJsonFrame(emitter, "runId", new JSONObject().set("runId", runId));
            audit.setUserId(userId);
            audit.setSessionId(request.getSessionId());
            audit.setAction(StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL));
            audit.setModel(config.getLlmModel());
            audit.setDatabaseId(request.getDatabaseId());
            audit.setSchemaName(request.getSchemaName());
            audit.setQuestion(request.getMessage());

            // 限流（每分钟）
            String limited = rateLimiter.tryAcquire(userId == null ? 0 : userId, config.getLlmRateLimitPerMinute());
            if (limited != null) {
                sendFrame(emitter, "error", limited);
                emitter.complete();
                audit.setSuccess(false);
                audit.setExecStatus("rejected");
                audit.setExecError(limited);
                return;
            }
            // 配额（按天）
            String quotaExceeded = checkDailyQuota(userId, config);
            if (quotaExceeded != null) {
                sendFrame(emitter, "error", quotaExceeded);
                emitter.complete();
                audit.setSuccess(false);
                audit.setExecStatus("rejected");
                audit.setExecError(quotaExceeded);
                return;
            }

            String action = StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                    .trim()
                    .toUpperCase();
            boolean isExplain = ACTION_EXPLAIN.equals(action);
            // 阶段 2b：局部改写（Fix / Rewrite）——单轮显式改写，只产出 SQL，不执行
            boolean isFix = ACTION_FIX_SQL.equals(action);
            boolean isRewrite = ACTION_REWRITE_SQL.equals(action);

            // 阶段 2b（Fix）：无最近执行报错时明确提示并终止，避免模型凭空"幻觉修复"（计划 P6）
            if (isFix && StrUtil.isBlank(resolveLatestJobError(request))) {
                sendFrame(emitter, "error", "未取到当前作业最近一次执行报错；可改用「改写」，或先在编辑器中执行一次产生报错后再修复。");
                audit.setSuccess(false);
                audit.setExecStatus("rejected");
                emitter.complete();
                return;
            }

            String schemaContext =
                    StrUtil.isNotEmpty(prebuiltSchemaContext) ? prebuiltSchemaContext : buildSchemaContext(request);
            List<AiChatMessage> messages = buildMessages(request, schemaContext);

            StringBuilder answer = new StringBuilder();
            AiToolRunResult toolRun = null;
            if (config.isLlmToolCallEnable()) {
                toolRun = runToolLoop(
                        request,
                        messages,
                        emitter,
                        answer,
                        dataBase,
                        config,
                        runId,
                        run,
                        () -> runRegistry.isCancelled(run));
                mergeUsage(totalUsage, toolRun.getUsage());
                // 阶段 2c-0：用户已请求中断——停止后续校验 / 修复，直接收尾
                if (runRegistry.isCancelled(run)) {
                    audit.setExecStatus("cancelled");
                    sendFrame(emitter, "status", "cancelled");
                    emitter.complete();
                    return;
                }
            } else {
                mergeUsage(totalUsage, generate(messages, emitter, answer));
            }

            int retryCount = 0;
            String finalSql = null;
            String execStatus = "none";
            String execError = null;

            // 阶段 2b：局部改写（Fix / Rewrite）只产出待确认的 SQL 文本，绝不执行——
            // 提取出来经 sql 帧下发给前端做 diff 对照，用户确认后由前端替换选中片段。
            if (isFix || isRewrite) {
                String rewritten = sqlVerifier.extractSql(answer.toString());
                if (StrUtil.isNotBlank(rewritten)) {
                    finalSql = rewritten;
                    sendFrame(emitter, "sql", rewritten);
                }
                execStatus = "rewritten";
            }

            // 正确性闭环：生成 → 执行校验 → 报错回传 → 自动修复（最多 maxRetry 次）
            // （阶段 2b：Fix / Rewrite 不进该闭环，避免自动执行用户的 SQL）
            if (!isExplain && !isFix && !isRewrite && config.isLlmSqlVerifyEnable()) {
                int maxRetry = Math.max(config.getLlmSqlVerifyMaxRetry(), 0);
                String sql = sqlVerifier.extractSql(answer.toString());
                if (StrUtil.isNotBlank(sql)) {
                    sendFrame(emitter, "sql", sql);
                    SqlVerifier.SqlType sqlType = sqlVerifier.classify(sql);
                    // 阶段 2c-0：自动校验闭环**只执行只读语句**（SELECT / METADATA / UNKNOWN）；
                    // 写语句（DML / DDL）绝不在此自动执行——须经二次确认后由执行工具触发（2c-1 接入），
                    // 避免"管理员开关一开就默默写库"。
                    boolean allowAutoExec = sqlType == SqlVerifier.SqlType.SELECT
                            || sqlType == SqlVerifier.SqlType.METADATA
                            || sqlType == SqlVerifier.SqlType.UNKNOWN;
                    if (!allowAutoExec) {
                        // 阶段 2c-0：写语句（DML/DDL）经「二次确认」闸门（§8.0 决策 2 中间档）。
                        // ① 管理员未开放该类语句 → 直接拒绝，不进入确认；
                        // ② 已开放 → 下发确认请求并挂起等待；确认才执行写通道，拒绝/超时一律不执行。
                        String writeReject = sqlVerifier.rejectReason(sqlType);
                        if (writeReject != null) {
                            execStatus = "rejected";
                            execError = writeReject;
                            sendFrame(emitter, "status", "rejected");
                            log.info("Write SQL rejected by policy, type={}, reason={}", sqlType, writeReject);
                        } else {
                            finalSql = sql;
                            // 阶段 2c-1：携带风险信息（语句类型 / 是否 DDL / 目标对象）；此处模型未自报影响范围
                            boolean approved = requestWriteConfirmation(
                                    emitter, runId, run, sql, ChangeRisk.of(sqlType, sql, null));
                            if (approved) {
                                sendFrame(emitter, "status", "verifying");
                                SqlVerifier.VerifyResult writeResult = sqlVerifier.verify(dataBase, sql);
                                sendExecResult(emitter, writeResult);
                                execStatus = writeResult.isSuccess() ? "executed" : "failed";
                                execError = writeResult.getError();
                                sendFrame(emitter, "status", execStatus);
                            } else {
                                execStatus = "rejected";
                                execError = "用户未确认执行该写语句";
                                sendFrame(emitter, "status", "rejected");
                                // 把「用户已拒绝」回灌给模型，让它改用只读方案或收尾，避免反复重试写操作
                                messages.add(AiChatMessage.of(
                                        "user",
                                        "用户拒绝执行上一条写语句（" + sqlType.name() + "）。请勿再次生成写语句；如需该操作，请改为只读方案或提示用户手动执行。"));
                            }
                        }
                    }
                    for (int attempt = 0; allowAutoExec && attempt <= maxRetry; attempt++) {
                        sendFrame(emitter, "status", "verifying");
                        SqlVerifier.VerifyResult verifyResult = sqlVerifier.verify(dataBase, sql);
                        sendExecResult(emitter, verifyResult);
                        finalSql = sql;
                        if (verifyResult.isSuccess()) {
                            execStatus = "verified";
                            sendFrame(emitter, "status", "verified");
                            break;
                        }
                        execStatus = (verifyResult.isRejected() || !verifyResult.isExecuted()) ? "rejected" : "failed";
                        execError = verifyResult.getError();
                        sendFrame(emitter, "status", execStatus);
                        if (!verifyResult.isRepairable() || attempt == maxRetry) {
                            break;
                        }
                        // 把数据源返回的真实报错回传给模型修复
                        retryCount++;
                        sendFrame(emitter, "status", "retrying");
                        messages.add(AiChatMessage.of("assistant", answer.toString()));
                        messages.add(AiChatMessage.of(
                                "user",
                                buildRepairPrompt(sql, verifyResult.getError(), schemaContext, request.getDialect())));
                        answer.setLength(0);
                        sendFrame(emitter, "content", "\n\n> 自动修复 " + retryCount + "/" + maxRetry + "：\n");
                        mergeUsage(totalUsage, generate(messages, emitter, answer));
                        sql = sqlVerifier.extractSql(answer.toString());
                        if (StrUtil.isBlank(sql)) {
                            break;
                        }
                        sendFrame(emitter, "sql", sql);
                    }
                }
            }

            audit.setSqlText(finalSql);
            audit.setExecStatus(execStatus);
            audit.setExecError(StrUtil.sub(execError, 0, 2000));
            audit.setRetryCount(retryCount);
            if (toolRun != null) {
                audit.setToolCallCount(toolRun.getToolCallCount());
                String logs = toolRun.getToolCallLogs() == null
                        ? ""
                        : toolRun.getToolCallLogs().toString();
                audit.setToolCalls(StrUtil.sub(logs, 0, MAX_TOOL_AUDIT_CHARS));
            }
            audit.setSuccess(!"failed".equals(execStatus));
            emitter.complete();
        } catch (Exception e) {
            log.error("AI chat failed", e);
            sendFrame(emitter, "error", e.getMessage());
            audit.setSuccess(false);
            audit.setExecStatus("failed");
            audit.setExecError(StrUtil.sub(e.getMessage(), 0, 2000));
            emitter.completeWithError(e);
        } finally {
            recordAudit(audit, totalUsage, start);
            // 阶段 2c-0：清理运行上下文（确认 / 取消的凭据随之失效）
            runRegistry.remove(runId);
        }
    }

    /** 调用模型一次：流式内容同时下发给前端并累积到 answer */
    private TokenUsage generate(List<AiChatMessage> messages, SseEmitter emitter, StringBuilder answer) {
        TokenUsage usage = llmClient.streamChat(
                messages,
                delta -> {
                    answer.append(delta);
                    sendFrame(emitter, "content", delta);
                },
                delta -> sendFrame(emitter, "reasoning", delta));
        return usage == null ? new TokenUsage() : usage;
    }

    /**
     * 运行工具循环（阶段 1b）。
     *
     * <p>它与下面的 SQL 修复重试是两个<b>独立</b>闭环：工具循环解决"模型自己去查"，修复重试解决
     * "照着报错改"。这里只做前者，但 {@code messages} 会被追加工具轮次的消息，后者继续复用。
     *
     * <p><b>为什么可以放心让模型自己查</b>：list_tables / describe_table 只返回元数据；唯一触碰业务
     * 数据行的 sample_rows 默认不注册（即不出现在下发给模型的工具清单里），且其结果与调用过程
     * 全程入审计。
     */
    private AiToolRunResult runToolLoop(
            AiChatRequest request,
            List<AiChatMessage> messages,
            SseEmitter emitter,
            StringBuilder answer,
            DataBase dataBase,
            SystemConfiguration config,
            String runId,
            AiChatRunRegistry.RunContext run,
            BooleanSupplier cancelled) {
        AiToolContext context = AiToolContext.create(
                dataBase,
                StrUtil.nullToEmpty(request.getSchemaName()),
                request.getUserId(),
                null,
                Math.max(config.getLlmToolTimeoutSeconds(), 1),
                MAX_TOOL_RESULT_CHARS);
        // 阶段 2c-1：把「运行上下文 + 发确认帧通道」注入工具上下文——exec_sql 这类写类工具
        // 据此在执行前下发 confirmRequest 并挂起等待用户拍板（未注入时工具会直接拒绝写操作）
        context.setRunId(runId);
        context.setConfirmRequester((sql, risk) -> requestWriteConfirmation(emitter, runId, run, sql, risk));
        return toolLoop.run(
                messages,
                context,
                delta -> {
                    answer.append(delta);
                    sendFrame(emitter, "content", delta);
                },
                delta -> sendFrame(emitter, "reasoning", delta),
                new AiToolLoop.Listener() {
                    @Override
                    public void onToolCall(AiToolCall call) {
                        // status 必须与前端 AiChatToolStep.status 的三态完全一致：
                        // running 表示已发起但尚未返回，toolResult 帧会把它更新为 success / failed
                        sendJsonFrame(
                                emitter,
                                "toolCall",
                                new JSONObject()
                                        .set("toolCallId", StrUtil.nullToEmpty(call.getId()))
                                        .set("name", StrUtil.nullToEmpty(call.getName()))
                                        .set("argsSummary", summarizeArgs(call.getArguments()))
                                        .set("status", "running"));
                    }

                    @Override
                    public void onToolResult(AiToolCall call, AiToolResult result) {
                        JSONObject payload = new JSONObject()
                                .set("toolCallId", StrUtil.nullToEmpty(call.getId()))
                                .set("name", StrUtil.nullToEmpty(call.getName()))
                                // 用 status 而不是布尔 success：前端按三态渲染（进行中 / 成功 / 失败）
                                .set("status", result.isSuccess() ? "success" : "failed")
                                .set("costMs", result.getCostMs())
                                .set("error", StrUtil.nullToEmpty(result.getErrorMessage()));
                        // 阶段 2c-1：写类工具回报受影响行数与风险摘要（只读工具无此值，不下发）
                        if (result.getAffectedRows() != null) {
                            payload.set("affectedRows", result.getAffectedRows());
                        }
                        if (StrUtil.isNotBlank(result.getRiskSummary())) {
                            payload.set("riskSummary", result.getRiskSummary());
                        }
                        sendJsonFrame(emitter, "toolResult", payload);
                    }

                    @Override
                    public void onRoundCompleted(int round, int maxRounds) {
                        // 心跳帧：既保活 SseEmitter，也让用户看到"还在查"而不是黑屏等待
                        sendJsonFrame(
                                emitter,
                                "heartbeat",
                                new JSONObject().set("round", round).set("maxRounds", maxRounds));
                    }
                },
                cancelled);
    }

    /**
     * 把工具参数压成一句人类可读的摘要：{@code {"tableName":"tc_users"}} → {@code tc_users}。
     *
     * <p>参数只用于过程展示，不打真实请求体——展示层不需要完整 JSON，短摘要更利于阅读。
     */
    private String summarizeArgs(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return "";
        }
        try {
            JSONObject json = JSONUtil.parseObj(arguments);
            StringBuilder sb = new StringBuilder();
            for (String key : json.keySet()) {
                if (StrUtil.isBlank(json.getStr(key))) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                // 保留参数名：只显示裸值会让"两个值"无从分辨来自哪个参数
                sb.append(key).append("=").append(json.getStr(key));
            }
            return StrUtil.sub(sb.length() > 0 ? sb.toString() : arguments, 0, MAX_TOOL_ARGS_CHARS);
        } catch (Exception e) {
            return StrUtil.sub(arguments, 0, MAX_TOOL_ARGS_CHARS);
        }
    }

    /** 构造"依据真实报错修复 SQL"的提示词 */
    private String buildRepairPrompt(String sql, String error, String schemaContext, String dialect) {
        Map<String, String> params = new HashMap<>(4);
        params.put(PromptStore.PLACEHOLDER_SQL, StrUtil.nullToEmpty(sql));
        params.put(PromptStore.PLACEHOLDER_ERROR, StrUtil.nullToEmpty(error));
        params.put(PromptStore.PLACEHOLDER_SCHEMA, StrUtil.nullToEmpty(schemaContext));
        params.put(PromptStore.PLACEHOLDER_DIALECT, StrUtil.blankToDefault(dialect, "SQL"));
        return PromptStore.render(PromptStore.SQL_REPAIR, params);
    }

    /** 下发校验结果帧（JSON 对象） */
    private void sendExecResult(SseEmitter emitter, SqlVerifier.VerifyResult result) {
        JSONObject payload = new JSONObject()
                .set("success", result.isSuccess())
                .set("executed", result.isExecuted())
                .set("rejected", result.isRejected())
                .set("rowCount", result.getRowCount())
                .set("costMs", result.getCostMs())
                .set("error", StrUtil.nullToEmpty(result.getError()));
        sendJsonFrame(emitter, "execResult", payload);
    }

    /** 按天配额检查：超限返回提示文案，未超限返回 null */
    private String checkDailyQuota(Integer userId, SystemConfiguration config) {
        int maxRequests = config.getLlmMaxRequestsPerDay();
        long maxTokens = config.getLlmMaxTokensPerDay();
        if (maxRequests <= 0 && maxTokens <= 0) {
            return null;
        }
        AiChatLogService.DailyUsage usage = aiChatLogService.todayUsage(userId);
        if (maxRequests > 0 && usage.getRequests() >= maxRequests) {
            return "今日 AI 对话次数已达上限（" + maxRequests + " 次），请明天再试或联系管理员调整配额";
        }
        if (maxTokens > 0 && usage.getTokens() >= maxTokens) {
            return "今日 AI 对话 token 用量已达上限（" + maxTokens + "），请明天再试或联系管理员调整配额";
        }
        return null;
    }

    /**
     * 记录 Craft 写入审计（阶段 2 T2-5）。
     *
     * <p>只落 hash 与字符数变化，<b>不落代码正文</b>；审计总开关关闭时不记录。
     * 整段为旁路逻辑——审计失败绝不影响用户已经完成的改写。
     */
    @Override
    public void recordCraftWrite(AiChatWriteAuditRequest request) {
        try {
            if (request == null || !SystemConfiguration.getInstances().isLlmAuditEnable()) {
                return;
            }
            AiChatLog audit = new AiChatLog();
            audit.setUserId(request.getUserId());
            audit.setSessionId(request.getSessionId());
            audit.setAction(ACTION_CRAFT_WRITE);
            audit.setSqlText(StrUtil.format(
                    "Craft 整块改写作业 #{}：字符变化 {}（before={} / after={}）",
                    request.getTaskId(),
                    request.getChars(),
                    request.getBeforeHash(),
                    request.getAfterHash()));
            if (request.getTaskId() != null) {
                audit.setWriteTaskId(request.getTaskId().longValue());
            }
            audit.setWriteBeforeHash(request.getBeforeHash());
            audit.setWriteAfterHash(request.getAfterHash());
            audit.setWriteChars(request.getChars());
            audit.setSuccess(true);
            audit.setCreateTime(LocalDateTime.now());
            aiChatLogService.record(audit);
        } catch (Exception e) {
            log.warn("Record AI craft write audit failed: {}", e.getMessage());
        }
    }

    /** 写审计日志（旁路：失败不影响对话） */
    private void recordAudit(AiChatLog audit, TokenUsage usage, long start) {
        try {
            if (!SystemConfiguration.getInstances().isLlmAuditEnable()) {
                return;
            }
            audit.setPromptTokens(usage.getPromptTokens());
            audit.setCompletionTokens(usage.getCompletionTokens());
            audit.setDurationMs(System.currentTimeMillis() - start);
            audit.setCreateTime(LocalDateTime.now());
            aiChatLogService.record(audit);
        } catch (Exception e) {
            log.warn("Record AI chat audit failed: {}", e.getMessage());
        }
    }

    private void mergeUsage(TokenUsage target, TokenUsage delta) {
        if (delta == null) {
            return;
        }
        target.setPromptTokens(target.getPromptTokens() + delta.getPromptTokens());
        target.setCompletionTokens(target.getCompletionTokens() + delta.getCompletionTokens());
    }

    /**
     * 下发一个 SSE 帧。
     *
     * <p><b>必须按 JSON 帧下发</b>：若直接下发纯文本，Spring 会把文本中的换行拆成多个
     * <code>data:</code> 行，前端逐行拼接后换行丢失（多行 SQL 会被压成一行），因此这里统一序列化为
     * JSON（换行被转义为 <code>\n</code>），由前端解析还原。
     *
     * @param type 帧类型：content（正文）/ reasoning（思考过程）/ error（错误）
     */
    private void sendFrame(SseEmitter emitter, String type, String text) {
        if (StrUtil.isEmpty(text)) {
            return;
        }
        try {
            emitter.send(
                    SseEmitter.event().data(new JSONObject().set(type, text).toString()));
        } catch (Exception e) {
            log.warn("Send SSE message failed: {}", e.getMessage());
        }
    }

    /** 下发一个值为 JSON 对象的 SSE 帧（用于结构化数据，如校验结果） */
    private void sendJsonFrame(SseEmitter emitter, String type, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            emitter.send(
                    SseEmitter.event().data(new JSONObject().set(type, payload).toString()));
        } catch (Exception e) {
            log.warn("Send SSE message failed: {}", e.getMessage());
        }
    }

    /**
     * 写语句执行前的二次确认（阶段 2c-0）：下发确认请求帧并<b>挂起等待</b>用户拍板。
     *
     * <p>前端收到 {@code confirmRequest} 帧后弹出确认框，经 {@code /api/aiChat/confirm} 投递结果；
     * 后端在此阻塞等待，超时按拒绝处理。<b>未确认一律不执行。</b>
     *
     * @return true = 用户确认执行；false = 拒绝或超时
     */
    private boolean requestWriteConfirmation(
            SseEmitter emitter, String runId, AiChatRunRegistry.RunContext run, String sql, ChangeRisk risk) {
        JSONObject payload = new JSONObject();
        payload.set("runId", runId);
        payload.set("sql", sql);
        payload.set("sqlType", risk == null ? "UNKNOWN" : StrUtil.nullToEmpty(risk.getSqlType()));
        payload.set("timeoutSeconds", WRITE_CONFIRM_TIMEOUT_SECONDS);
        // 阶段 2c-1：风险信息随确认框下发（语句类型 / 是否 DDL / 目标对象 / 模型估计影响）
        if (risk != null) {
            payload.set("risk", risk.toJson());
        }
        sendJsonFrame(emitter, "confirmRequest", payload);
        Boolean approved = run.awaitConfirm(WRITE_CONFIRM_TIMEOUT_SECONDS);
        if (approved == null) {
            log.info("Write confirmation timed out, runId={}, risk={}", runId, risk == null ? null : risk.toSummary());
        }
        return Boolean.TRUE.equals(approved);
    }

    @Override
    public boolean confirmRun(AiChatConfirmRequest request) {
        if (request == null || StrUtil.isBlank(request.getRunId())) {
            return false;
        }
        boolean approved = Boolean.TRUE.equals(request.getApprove());
        return runRegistry.submitConfirm(request.getRunId(), request.getUserId(), approved);
    }

    @Override
    public boolean cancelRun(AiChatConfirmRequest request) {
        if (request == null || StrUtil.isBlank(request.getRunId())) {
            return false;
        }
        return runRegistry.cancel(request.getRunId(), request.getUserId());
    }

    /** 组装发送给大模型的消息：system（首轮含 schema）+ 历史 + 本轮 user */
    private List<AiChatMessage> buildMessages(AiChatRequest request, String schemaContext) {
        String action = StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                .trim()
                .toUpperCase();
        boolean isExplain = ACTION_EXPLAIN.equals(action);
        // 阶段 2b：局部改写（Fix / Rewrite）——目标是「用户选中的片段」，单轮产出、不执行
        boolean isFix = ACTION_FIX_SQL.equals(action);
        boolean isRewrite = ACTION_REWRITE_SQL.equals(action);
        // 首轮才携带 schema 上下文，后续轮次由会话历史承载上下文（省 token、降延迟）
        boolean firstTurn = StrUtil.isBlank(request.getSessionId());

        // 「选中片段优先」的目标 SQL：EXPLAIN / Fix / Rewrite 三处共用
        // —— 有选中则针对选中片段，无选中回退全文（阶段 2b 体验优化：解释也遵循选中优先）
        String selectedOrFull = StrUtil.blankToDefault(request.getSelectedSql(), request.getSql());

        Map<String, String> params = new HashMap<>(4);
        params.put(PromptStore.PLACEHOLDER_SCHEMA, firstTurn ? schemaContext : "(schema 已在首轮提供，请沿用)");
        params.put(PromptStore.PLACEHOLDER_DIALECT, StrUtil.blankToDefault(request.getDialect(), "SQL"));
        params.put(
                PromptStore.PLACEHOLDER_SQL,
                StrUtil.nullToEmpty((isExplain || isFix || isRewrite) ? selectedOrFull : request.getSql()));
        // Fix 需要数据源返回的真实报错原文
        params.put(PromptStore.PLACEHOLDER_ERROR, isFix ? resolveLatestJobError(request) : "");
        // 阶段 1.0「作业上下文绑定」：编辑区内容（EXPLAIN 时 SQL 已在用户消息中给出，无需重复注入）
        // + 当前作业最近一次执行报错（排障场景）；局部改写已有专门模板，无需再注入编辑区全文
        params.put(
                PromptStore.PLACEHOLDER_EDITOR_SQL,
                (isExplain || isFix || isRewrite) ? "" : buildEditorContext(request));
        params.put(PromptStore.PLACEHOLDER_JOB_CONTEXT, (isFix || isRewrite) ? "" : buildJobContext(request));

        // 阶段 2：Craft 仅当「管理员已开启 + 本轮显式请求」时生效，否则一律回落 Ask
        boolean isCraft = !isExplain && !isFix && !isRewrite && isCraftMode(request);
        String systemPrompt;
        if (isExplain) {
            systemPrompt = PromptStore.render(PromptStore.EXPLAIN, params);
        } else if (isFix) {
            systemPrompt = PromptStore.render(PromptStore.SQL_FIX, params);
        } else if (isRewrite) {
            systemPrompt = PromptStore.render(PromptStore.SQL_REWRITE, params);
        } else if (isCraft) {
            systemPrompt = PromptStore.render(PromptStore.CRAFT, params);
        } else {
            systemPrompt = PromptStore.render(PromptStore.TEXT_TO_SQL, params);
        }

        List<AiChatMessage> messages = new ArrayList<>();
        messages.add(AiChatMessage.of("system", systemPrompt));

        if (CollUtil.isNotEmpty(request.getHistory())) {
            List<AiChatMessage> history = request.getHistory();
            List<AiChatMessage> recent = history.size() > MAX_HISTORY_MESSAGES
                    ? history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size())
                    : history;
            messages.addAll(recent);
        }

        StringBuilder userContent = new StringBuilder();
        if (isExplain) {
            userContent
                    .append("请解释以下 SQL：\n```sql\n")
                    .append(StrUtil.nullToEmpty(selectedOrFull))
                    .append("\n```");
            if (StrUtil.isNotBlank(request.getMessage())) {
                userContent.append("\n补充要求：").append(request.getMessage());
            }
        } else {
            userContent.append(StrUtil.nullToEmpty(request.getMessage()));
        }
        messages.add(AiChatMessage.of("user", userContent.toString()));
        return messages;
    }

    /**
     * 判定本轮是否为 Craft 模式（阶段 2：Ask / Craft 双模式）。
     *
     * <p><b>双重条件，缺一不可</b>：① 管理员已开启 {@code llm.craftModeEnable}（默认 false）；
     * ② 本轮请求显式携带 {@code mode=craft}。只满足其一都回落 Ask——
     * <b>安全性不依赖前端是否隐藏控件</b>：即使前端被绕过，未开启配置的请求也进不了 Craft 分支。
     */
    private boolean isCraftMode(AiChatRequest request) {
        if (!SystemConfiguration.getInstances().isLlmCraftModeEnable()) {
            return false;
        }
        return MODE_CRAFT.equalsIgnoreCase(StrUtil.trimToEmpty(request.getMode()));
    }

    /**
     * 构建「当前编辑区内容」区块（阶段 1.0 作业上下文绑定）。
     *
     * <p>早期实现只在 EXPLAIN 动作下发编辑区内容，导致用户正常追问时模型看不到他正在写的代码；
     * 现已改为全动作注入（EXPLAIN 除外，其 SQL 已在用户消息中给出）。
     *
     * @return 含标题的完整区块；编辑区为空时返回空串
     */
    private String buildEditorContext(AiChatRequest request) {
        int maxChars = SystemConfiguration.getInstances().getLlmEditorSqlMaxChars();
        // 阶段 1a（1.0.4）：选中片段优先——用户选中某段 SQL 提问，意图就是问这一段。
        // 此时再下发全文既浪费预算，又会把无关 SQL 混进上下文干扰模型（业界一致做法）。
        String selected = StrUtil.trimToNull(request.getSelectedSql());
        if (selected != null) {
            if (selected.length() > maxChars) {
                selected = selected.substring(0, maxChars) + "\n... (选中片段过长，已截断)";
            }
            return "## 用户在编辑器中选中的片段（本次提问针对该片段）\n" + "```sql\n" + selected + "\n```\n\n";
        }
        String sql = StrUtil.trimToNull(request.getSql());
        if (sql == null) {
            return "";
        }
        if (sql.length() > maxChars) {
            sql = sql.substring(0, maxChars) + "\n... (编辑区内容过长，已截断)";
        }
        return "## 当前编辑区内容（用户正在 Dinky 数据开发编辑器中编写的代码）\n" + "```sql\n" + sql + "\n```\n\n";
    }

    /**
     * 构建「用户 {@code @} 显式引用」区块（阶段 1a：1.4）。
     *
     * <p>显式引用是<b>最高优先级</b>上下文：先于表清单注入，且<b>不参与</b>后续字段预算的裁剪判定。
     * 依据：中文问题配英文表名时自动召回基本失效（2026-09-26 UAT 实测），用户手动指定是唯一可靠兜底。
     */
    private String buildMentionContext(AiChatRequest request, Integer databaseId, String schemaName) {
        List<AiChatMention> mentions = request.getMentions();
        if (CollUtil.isEmpty(mentions)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 用户显式指定的上下文（@ 引用，优先级最高）\n");
        for (AiChatMention mention : mentions) {
            if (mention == null) {
                continue;
            }
            if ("table".equalsIgnoreCase(mention.getType())) {
                String tableSchema = StrUtil.isNotBlank(mention.getSchemaName()) ? mention.getSchemaName() : schemaName;
                sb.append("- 表 ")
                        .append(tableSchema)
                        .append(".")
                        .append(StrUtil.nullToEmpty(mention.getName()))
                        .append("\n");
                appendTableDetail(sb, databaseId, tableSchema, mention.getName());
            } else if ("column".equalsIgnoreCase(mention.getType())) {
                // 阶段 2 前置：字段级引用——只给该字段的类型/注释，不给整表，省预算
                String tableSchema = StrUtil.isNotBlank(mention.getSchemaName()) ? mention.getSchemaName() : schemaName;
                String table = StrUtil.nullToEmpty(mention.getName());
                String column = StrUtil.nullToEmpty(mention.getColumnName());
                sb.append("- 字段 ")
                        .append(tableSchema)
                        .append(".")
                        .append(table)
                        .append(".")
                        .append(column)
                        .append("\n");
                appendColumnDetail(sb, databaseId, tableSchema, table, column);
            } else if (StrUtil.isNotBlank(mention.getContent())) {
                // selection / job：片段正文（仅编辑器文本，不含业务数据行）
                String content = mention.getContent().trim();
                int maxChars = SystemConfiguration.getInstances().getLlmEditorSqlMaxChars();
                if (content.length() > maxChars) {
                    content = content.substring(0, maxChars) + "\n... (片段过长，已截断)";
                }
                sb.append("- ")
                        .append(StrUtil.nullToEmpty(mention.getName()))
                        .append("\n```sql\n")
                        .append(content)
                        .append("\n```\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 按用户选择的档位过滤表范围（阶段 1a：1.1 Context 三档）。
     *
     * <p>档位缺少对应选择（custom 未勾选表 / current 未选中表）时<b>退化为 all 并照实输出</b>，
     * 避免「什么都没给」的静默失败——这比给得不准更糟。
     */
    private List<Table> applyContextScope(List<Table> tables, AiChatRequest request) {
        if (CollUtil.isEmpty(tables)) {
            return tables;
        }
        String scope =
                StrUtil.blankToDefault(request.getContextScope(), "all").trim().toLowerCase();
        if (!"custom".equals(scope)) {
            // current 由上游 tableName 分支处理；此处保持全量（未选中表时退化为 all）
            return tables;
        }
        List<String> picked = request.getCustomTables();
        if (CollUtil.isEmpty(picked)) {
            return tables;
        }
        List<Table> filtered = new ArrayList<>();
        for (Table table : tables) {
            if (table != null && picked.contains(table.getName())) {
                filtered.add(table);
            }
        }
        return filtered;
    }

    /**
     * 构建「当前作业最近一次执行情况」区块（阶段 1.0，用于「为什么跑挂了」类排障提问）。
     *
     * <p>只取 {@link JobInstance} 的状态与报错等<b>元信息</b>，不含业务数据行。
     * 仅在确实存在报错时注入，避免成功场景下白白占用上下文预算。
     *
     * @return 含标题的完整区块；无报错信息时返回空串
     */
    private String buildJobContext(AiChatRequest request) {
        Integer taskId = request.getTaskId();
        if (taskId == null) {
            return "";
        }
        try {
            JobInstance jobInstance = jobInstanceService.getJobInstanceByTaskId(taskId);
            if (jobInstance == null || StrUtil.isBlank(jobInstance.getError())) {
                return "";
            }
            String error = jobInstance.getError().trim();
            if (error.length() > MAX_JOB_ERROR_CHARS) {
                error = error.substring(0, MAX_JOB_ERROR_CHARS) + "\n... (报错过长，已截断)";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 当前作业最近一次执行情况（用于排障，非业务数据）\n");
            sb.append("- 作业 id：").append(taskId).append("\n");
            sb.append("- 状态：")
                    .append(StrUtil.nullToEmpty(jobInstance.getStatus()))
                    .append("\n");
            if (jobInstance.getStep() != null) {
                sb.append("- 执行步骤(step)：").append(jobInstance.getStep()).append("\n");
            }
            sb.append("- 报错原文：\n```\n").append(error).append("\n```\n\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("Build job context failed, taskId: {}", taskId, e);
            return "";
        }
    }

    /**
     * 取当前作业「最近一次执行报错」的<b>纯报错原文</b>（阶段 2b：Fix SQL 用）。
     *
     * <p>与 {@link #buildJobContext} 的区别：后者输出含状态/步骤的整块文本（用于"为什么跑挂了"排障问答），
     * 这里只需喂给 {@link PromptStore#SQL_FIX} 模板的报错原文本身。
     *
     * @return 报错原文（已截断）；无报错或取数失败时返回空串
     */
    private String resolveLatestJobError(AiChatRequest request) {
        // ① 最高优先：前端捕获并随请求下发的执行报错
        //    （编辑器内执行 SQL 的报错不落后端库，尤其 MySQL 等直连数据源）
        String error = StrUtil.trimToEmpty(request.getExecutionError());
        Integer taskId = request.getTaskId();
        // ② 后端历史：dinky_history.error（Flink 作业执行失败）
        if (StrUtil.isBlank(error) && taskId != null) {
            error = latestHistoryError(taskId);
        }
        // ③ 兜底：JobInstance.error（已部署作业的运行时报错）
        if (StrUtil.isBlank(error) && taskId != null) {
            error = jobInstanceError(taskId);
        }
        if (StrUtil.isBlank(error)) {
            return "";
        }
        error = error.trim();
        if (error.length() > MAX_JOB_ERROR_CHARS) {
            error = error.substring(0, MAX_JOB_ERROR_CHARS) + "\n... (报错过长，已截断)";
        }
        return error;
    }

    /** 取最近一条执行历史（dinky_history）的报错原文；无记录 / 无报错时返回空串 */
    private String latestHistoryError(Integer taskId) {
        try {
            History history = historyService.getLatestHistoryById(taskId);
            return history == null ? "" : history.getError();
        } catch (Exception e) {
            log.warn("Resolve latest history error failed, taskId: {}", taskId, e);
            return "";
        }
    }

    /** 取已部署作业（JobInstance）的运行时报错原文；无记录 / 无报错时返回空串 */
    private String jobInstanceError(Integer taskId) {
        try {
            JobInstance jobInstance = jobInstanceService.getJobInstanceByTaskId(taskId);
            return jobInstance == null ? "" : jobInstance.getError();
        } catch (Exception e) {
            log.warn("Resolve job instance error failed, taskId: {}", taskId, e);
            return "";
        }
    }

    /**
     * 构建元数据上下文（<b>只含元数据，绝不含数据行</b>）。
     *
     * <ul>
     *   <li>选中了具体表：输出该表的列（PK 标记）与外键上下游</li>
     *   <li>未选中表：输出当前 schema 下的表清单（限量）及其列</li>
     * </ul>
     */
    private String buildSchemaContext(AiChatRequest request) {
        if (request.getDatabaseId() == null) {
            return "(未绑定数据源，无可用 schema 信息)";
        }
        Integer databaseId = request.getDatabaseId();
        String schemaName = StrUtil.nullToEmpty(request.getSchemaName());
        SystemConfiguration config = SystemConfiguration.getInstances();
        // 阶段 1a：上下文预算改为可配置（原硬编码 24000 / 20000），可按模型窗口与
        // 「准确率 / p95 延迟 / 成本」实测结果调档（见阶段 1 计划 §3.4.5）
        int schemaMaxChars = config.getLlmSchemaMaxChars();
        int columnBudgetChars = config.getLlmColumnBudgetChars();
        StringBuilder sb = new StringBuilder();

        // 阶段 1a（1.4）：@ 显式引用优先级最高，先于表清单注入
        sb.append(buildMentionContext(request, databaseId, schemaName));

        if (StrUtil.isNotBlank(request.getTableName())) {
            appendTableDetail(sb, databaseId, schemaName, request.getTableName());
        } else {
            try {
                // 阶段 1a（1.1）：按用户选择的档位过滤表范围
                List<Table> tables = applyContextScope(dataBaseService.getTables(databaseId, schemaName), request);
                if (CollUtil.isEmpty(tables)) {
                    return "(schema 下未获取到表信息)";
                }
                int threshold = config.getLlmSchemaTableDetailThreshold();
                // 阶段 1.5（预算自适应）：小库直接按原顺序全给；仅大库才先按与问题的相关度排序，
                // 再由下方字符预算决定到底给出多少张表的字段。
                // ——取代原先“召回后固定只给前 N 张字段”的做法：固定张数裁剪会把真正需要的表砍掉，
                //   此问题在 2026-09-27 UAT 中已实测暴露（见阶段 0 计划 §9.8）。
                List<Table> ordered =
                        tables.size() > threshold ? TableSelector.rank(tables, request.getMessage()) : tables;

                sb.append("Schema: ")
                        .append(schemaName)
                        .append(" (共 ")
                        .append(tables.size())
                        .append(" 张表");
                if (tables.size() > threshold) {
                    sb.append("，已按与问题的相关度排序");
                }
                sb.append(")\n");
                sb.append("Tables:\n");
                int limit = Math.min(ordered.size(), MAX_TABLE_LIST);
                for (int i = 0; i < limit; i++) {
                    Table table = ordered.get(i);
                    sb.append("  - ").append(table.getName());
                    if (StrUtil.isNotBlank(table.getComment())) {
                        sb.append(" -- ").append(table.getComment());
                    }
                    sb.append("\n");
                }
                // 字段详情：按上述顺序逐表装填，直到上下文预算用尽为止
                sb.append("\nColumns per table:\n");
                int detailGiven = 0;
                for (int i = 0; i < limit; i++) {
                    StringBuilder piece = new StringBuilder();
                    appendTableDetail(
                            piece, databaseId, schemaName, ordered.get(i).getName());
                    if (sb.length() + piece.length() > columnBudgetChars) {
                        // 阶段 1a（1.5）：裁剪必须「明示」，且给出可执行的补救动作（@ 指定）
                        sb.append("  ... (上下文预算已用尽，剩余 ")
                                .append(limit - detailGiven)
                                .append(" 张表只给出了表名。如需其中某张表的字段，可在输入框用 @表名 显式指定。)\n");
                        break;
                    }
                    sb.append(piece);
                    detailGiven++;
                }
            } catch (Exception e) {
                log.warn("Build schema context failed, databaseId: {}, schema: {}", databaseId, schemaName, e);
                return "(获取表信息失败：" + e.getMessage() + ")";
            }
        }

        String result = sb.toString();
        if (result.length() > schemaMaxChars) {
            result = result.substring(0, schemaMaxChars) + "\n... (schema 过长，已截断)";
        }
        return result;
    }

    /** 追加单表的列信息与外键关系（元数据） */
    /**
     * 把单表的列详情写入 {@code target}。
     *
     * <p>写入前先在临时缓冲中拼装，便于调用方在<b>拼装完成后</b>再决定是否纳入context（预算控制）。
     *
     * @return true 表示成功写入；false 表示获取列失败
     */
    /**
     * 把单表的<b>指定字段</b>详情写入 {@code target}（阶段 2 前置：字段级 {@code @} 引用）。
     *
     * <p>字段查不到时<b>退化为整表</b>并照实说明——沿用 {@link #applyContextScope} 的既有原则：
     * 「什么都没给」的静默失败，比给得不准更糟。
     */
    private void appendColumnDetail(
            StringBuilder target, Integer databaseId, String schemaName, String tableName, String columnName) {
        if (StrUtil.isBlank(columnName) || StrUtil.isBlank(tableName)) {
            return;
        }
        try {
            List<Column> columns = dataBaseService.listColumns(databaseId, schemaName, tableName);
            Column hit = null;
            if (CollUtil.isNotEmpty(columns)) {
                for (Column column : columns) {
                    if (column != null && columnName.equalsIgnoreCase(column.getName())) {
                        hit = column;
                        break;
                    }
                }
            }
            if (hit == null) {
                target.append("  (未找到字段 ").append(columnName).append("，改为给出整表结构)\n");
                appendTableDetail(target, databaseId, schemaName, tableName);
                return;
            }
            target.append("  Column: ")
                    .append(tableName)
                    .append(".")
                    .append(hit.getName())
                    .append(" ")
                    .append(StrUtil.nullToEmpty(hit.getType()));
            if (hit.isKeyFlag()) {
                target.append(" [PK]");
            }
            if (StrUtil.isNotBlank(hit.getComment())) {
                target.append(" -- ").append(hit.getComment());
            }
            target.append("\n");
        } catch (Exception e) {
            // 脱敏：原始异常可能含 JDBC URL / 内网地址，绝不能写进 prompt
            log.warn(
                    "Append column detail failed, databaseId: {}, table: {}, column: {}",
                    databaseId,
                    tableName,
                    columnName,
                    e);
            target.append("  (读取字段信息失败)\n");
        }
    }

    private boolean appendTableDetail(StringBuilder target, Integer databaseId, String schemaName, String tableName) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Column> columns = dataBaseService.listColumns(databaseId, schemaName, tableName);
            sb.append("Table: ").append(tableName);
            if (StrUtil.isNotBlank(schemaName)) {
                sb.append(" (schema: ").append(schemaName).append(")");
            }
            sb.append("\n");
            if (CollUtil.isEmpty(columns)) {
                sb.append("  (无列信息)\n");
            } else {
                sb.append("  Columns:\n");
                int limit = Math.min(columns.size(), MAX_COLUMNS_PER_TABLE);
                for (int i = 0; i < limit; i++) {
                    Column column = columns.get(i);
                    sb.append("    - ")
                            .append(column.getName())
                            .append(" ")
                            .append(StrUtil.nullToEmpty(column.getType()));
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
            }
            appendForeignKeys(sb, databaseId, schemaName, tableName);
            target.append(sb);
            return true;
        } catch (Exception e) {
            log.warn("List columns failed, table: {}", tableName, e);
            target.append("Table: ").append(tableName).append("\n  (获取列信息失败)\n");
            return false;
        }
    }

    /** 追加外键关系（上游 foreignKeys + 下游 referencedBy） */
    private void appendForeignKeys(StringBuilder sb, Integer databaseId, String schemaName, String tableName) {
        try {
            TableRelations relations = dataBaseService.getTableRelations(databaseId, schemaName, tableName);
            if (relations == null
                    || (CollUtil.isEmpty(relations.getForeignKeys())
                            && CollUtil.isEmpty(relations.getReferencedBy()))) {
                return;
            }
            sb.append("  Relations:\n");
            // 上游：本表作为子表，引用别表
            for (ForeignKey fk : relations.getForeignKeys()) {
                sb.append("    - FK ")
                        .append(tableName)
                        .append(".")
                        .append(String.join(",", nullToEmpty(fk.getColumns())))
                        .append(" -> ")
                        .append(fk.getRefTableName())
                        .append(".")
                        .append(String.join(",", nullToEmpty(fk.getRefColumns())))
                        .append("\n");
            }
            // 下游：别表引用本表
            for (ForeignKey fk : relations.getReferencedBy()) {
                sb.append("    - FK ")
                        .append(fk.getTableName())
                        .append(".")
                        .append(String.join(",", nullToEmpty(fk.getColumns())))
                        .append(" -> ")
                        .append(tableName)
                        .append(".")
                        .append(String.join(",", nullToEmpty(fk.getRefColumns())))
                        .append("\n");
            }
        } catch (Exception e) {
            // 部分数据源不支持外键元数据（如 Hive/Trino），此处静默降级，不影响主流程
            log.debug("Get table relations failed, table: {}, message: {}", tableName, e.getMessage());
        }
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? java.util.Collections.emptyList() : values;
    }

    @PreDestroy
    public void destroy() {
        chatExecutor.shutdownNow();
    }
}
