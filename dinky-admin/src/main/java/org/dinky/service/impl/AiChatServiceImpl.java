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
import org.dinky.ai.AiToolCall;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolLoop;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolRunResult;
import org.dinky.ai.LlmClient;
import org.dinky.ai.PromptStore;
import org.dinky.ai.SqlVerifier;
import org.dinky.ai.TableSelector;
import org.dinky.ai.TokenUsage;
import org.dinky.data.dto.AiChatMention;
import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.model.AiChatLog;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.ForeignKey;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.data.model.TableRelations;
import org.dinky.data.model.job.JobInstance;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatLogService;
import org.dinky.service.AiChatService;
import org.dinky.service.DataBaseService;
import org.dinky.service.JobInstanceService;
import org.dinky.sse.SseEmitterUTF8;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final DataBaseService dataBaseService;
    private final LlmClient llmClient;
    private final SqlVerifier sqlVerifier;
    private final AiChatRateLimiter rateLimiter;
    private final AiChatLogService aiChatLogService;
    private final JobInstanceService jobInstanceService;
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
                .build();
    }

    /** 实际对话逻辑（在异步线程中执行） */
    private void doChat(AiChatRequest request, SseEmitter emitter, DataBase dataBase, String prebuiltSchemaContext) {
        long start = System.currentTimeMillis();
        TokenUsage totalUsage = new TokenUsage();
        AiChatLog audit = new AiChatLog();
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

            boolean isExplain = ACTION_EXPLAIN.equals(StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                    .trim()
                    .toUpperCase());
            String schemaContext =
                    StrUtil.isNotEmpty(prebuiltSchemaContext) ? prebuiltSchemaContext : buildSchemaContext(request);
            List<AiChatMessage> messages = buildMessages(request, schemaContext);

            StringBuilder answer = new StringBuilder();
            AiToolRunResult toolRun = null;
            if (config.isLlmToolCallEnable()) {
                toolRun = runToolLoop(request, messages, emitter, answer, dataBase, config);
                mergeUsage(totalUsage, toolRun.getUsage());
            } else {
                mergeUsage(totalUsage, generate(messages, emitter, answer));
            }

            int retryCount = 0;
            String finalSql = null;
            String execStatus = "none";
            String execError = null;

            // 正确性闭环：生成 → 执行校验 → 报错回传 → 自动修复（最多 maxRetry 次）
            if (!isExplain && config.isLlmSqlVerifyEnable()) {
                int maxRetry = Math.max(config.getLlmSqlVerifyMaxRetry(), 0);
                String sql = sqlVerifier.extractSql(answer.toString());
                if (StrUtil.isNotBlank(sql)) {
                    sendFrame(emitter, "sql", sql);
                    for (int attempt = 0; attempt <= maxRetry; attempt++) {
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
            SystemConfiguration config) {
        AiToolContext context = AiToolContext.create(
                dataBase,
                StrUtil.nullToEmpty(request.getSchemaName()),
                request.getUserId(),
                null,
                Math.max(config.getLlmToolTimeoutSeconds(), 1),
                MAX_TOOL_RESULT_CHARS);
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
                        sendJsonFrame(
                                emitter,
                                "toolResult",
                                new JSONObject()
                                        .set("toolCallId", StrUtil.nullToEmpty(call.getId()))
                                        .set("name", StrUtil.nullToEmpty(call.getName()))
                                        // 用 status 而不是布尔 success：前端按三态渲染（进行中 / 成功 / 失败）
                                        .set("status", result.isSuccess() ? "success" : "failed")
                                        .set("costMs", result.getCostMs())
                                        .set("error", StrUtil.nullToEmpty(result.getErrorMessage())));
                    }

                    @Override
                    public void onRoundCompleted(int round, int maxRounds) {
                        // 心跳帧：既保活 SseEmitter，也让用户看到"还在查"而不是黑屏等待
                        sendJsonFrame(
                                emitter,
                                "heartbeat",
                                new JSONObject().set("round", round).set("maxRounds", maxRounds));
                    }
                });
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

    /** 组装发送给大模型的消息：system（首轮含 schema）+ 历史 + 本轮 user */
    private List<AiChatMessage> buildMessages(AiChatRequest request, String schemaContext) {
        String action = StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                .trim()
                .toUpperCase();
        boolean isExplain = ACTION_EXPLAIN.equals(action);
        // 首轮才携带 schema 上下文，后续轮次由会话历史承载上下文（省 token、降延迟）
        boolean firstTurn = StrUtil.isBlank(request.getSessionId());

        Map<String, String> params = new HashMap<>(4);
        params.put(PromptStore.PLACEHOLDER_SCHEMA, firstTurn ? schemaContext : "(schema 已在首轮提供，请沿用)");
        params.put(PromptStore.PLACEHOLDER_DIALECT, StrUtil.blankToDefault(request.getDialect(), "SQL"));
        params.put(PromptStore.PLACEHOLDER_SQL, StrUtil.nullToEmpty(request.getSql()));
        // 阶段 1.0「作业上下文绑定」：编辑区内容（EXPLAIN 时 SQL 已在用户消息中给出，无需重复注入）
        // + 当前作业最近一次执行报错（排障场景）
        params.put(PromptStore.PLACEHOLDER_EDITOR_SQL, isExplain ? "" : buildEditorContext(request));
        params.put(PromptStore.PLACEHOLDER_JOB_CONTEXT, buildJobContext(request));

        String systemPrompt = isExplain
                ? PromptStore.render(PromptStore.EXPLAIN, params)
                : PromptStore.render(PromptStore.TEXT_TO_SQL, params);

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
                    .append(StrUtil.nullToEmpty(request.getSql()))
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
