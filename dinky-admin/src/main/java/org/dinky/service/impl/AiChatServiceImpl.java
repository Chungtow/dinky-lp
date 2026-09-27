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
import org.dinky.ai.LlmClient;
import org.dinky.ai.PromptStore;
import org.dinky.ai.SqlVerifier;
import org.dinky.ai.TableSelector;
import org.dinky.ai.TokenUsage;
import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.model.AiChatLog;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.ForeignKey;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.data.model.TableRelations;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatLogService;
import org.dinky.service.AiChatService;
import org.dinky.service.DataBaseService;
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

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
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
    /** 表数量不超过该阈值时，才逐表附带字段详情（避免 token 爆炸） */
    private static final int COLUMN_DETAIL_THRESHOLD = 8;
    /** 走召回时，最多附带字段详情的表数量（召回结果通常已较精准，但仍需限量） */
    private static final int RECALL_COLUMN_DETAIL_TABLES = 5;
    /** 每张表最多包含的列数量 */
    private static final int MAX_COLUMNS_PER_TABLE = 40;
    /** schema 上下文的最大字符数，超出即截断（避免 token 爆炸） */
    private static final int MAX_SCHEMA_CHARS = 24000;
    /** 最多携带的历史对话轮次（一问一答算一轮，此处按消息条数算） */
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final DataBaseService dataBaseService;
    private final LlmClient llmClient;
    private final SqlVerifier sqlVerifier;
    private final AiChatRateLimiter rateLimiter;
    private final AiChatLogService aiChatLogService;

    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    @Override
    public SseEmitter chat(AiChatRequest request) {
        int timeoutSeconds = Math.max(SystemConfiguration.getInstances().getLlmTimeout(), 1);
        SseEmitter emitter = new SseEmitterUTF8((timeoutSeconds + 30) * 1000L);
        // 数据源必须在请求线程中解析：对话跑在异步线程，此时租户上下文（ThreadLocal）已丢失，
        // 再按 id 查询会因租户过滤而查不到数据源（此前表现为"数据源不存在"）。
        DataBase dataBase = resolveDataBase(request);
        chatExecutor.execute(() -> doChat(request, emitter, dataBase));
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
    private void doChat(AiChatRequest request, SseEmitter emitter, DataBase dataBase) {
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

            boolean isExplain = ACTION_EXPLAIN.equals(
                    StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                            .trim()
                            .toUpperCase());
            String schemaContext = buildSchemaContext(request);
            List<AiChatMessage> messages = buildMessages(request, schemaContext);

            StringBuilder answer = new StringBuilder();
            mergeUsage(totalUsage, generate(messages, emitter, answer));

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
                                buildRepairPrompt(
                                        sql, verifyResult.getError(), schemaContext, request.getDialect())));
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
        params.put(
                PromptStore.PLACEHOLDER_SCHEMA,
                firstTurn ? schemaContext : "(schema 已在首轮提供，请沿用)");
        params.put(PromptStore.PLACEHOLDER_DIALECT, StrUtil.blankToDefault(request.getDialect(), "SQL"));
        params.put(PromptStore.PLACEHOLDER_SQL, StrUtil.nullToEmpty(request.getSql()));

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
        StringBuilder sb = new StringBuilder();

        if (StrUtil.isNotBlank(request.getTableName())) {
            appendTableDetail(sb, databaseId, schemaName, request.getTableName());
        } else {
            try {
                List<Table> tables = dataBaseService.getTables(databaseId, schemaName);
                if (CollUtil.isEmpty(tables)) {
                    return "(schema 下未获取到表信息)";
                }
                SystemConfiguration config = SystemConfiguration.getInstances();
                int threshold = config.getLlmSchemaTableDetailThreshold();
                int recallTopN = config.getLlmSchemaRecallTopN();
                // 大库场景：按问题关键词召回相关表，避免把上千张表全塞进 context
                List<Table> candidates = tables;
                boolean recalled = tables.size() > threshold;
                if (recalled) {
                    candidates = TableSelector.select(tables, request.getMessage(), recallTopN);
                }

                sb.append("Schema: ")
                        .append(schemaName)
                        .append(" (共 ")
                        .append(tables.size())
                        .append(" 张表");
                if (recalled) {
                    sb.append("，已按问题关键词召回 ")
                            .append(candidates.size())
                            .append(" 张相关表");
                }
                sb.append(")\n");
                sb.append("Tables:\n");
                int limit = Math.min(candidates.size(), MAX_TABLE_LIST);
                for (int i = 0; i < limit; i++) {
                    Table table = candidates.get(i);
                    sb.append("  - ").append(table.getName());
                    if (StrUtil.isNotBlank(table.getComment())) {
                        sb.append(" -- ").append(table.getComment());
                    }
                    sb.append("\n");
                }
                if (recalled || candidates.size() <= COLUMN_DETAIL_THRESHOLD) {
                    int detailLimit = Math.min(
                            candidates.size(), recalled ? RECALL_COLUMN_DETAIL_TABLES : COLUMN_DETAIL_THRESHOLD);
                    sb.append("\nColumns per table:\n");
                    for (int i = 0; i < detailLimit; i++) {
                        appendTableDetail(sb, databaseId, schemaName, candidates.get(i).getName());
                    }
                    if (detailLimit < candidates.size()) {
                        sb.append("  ... (仅列出前 ")
                                .append(detailLimit)
                                .append(" 张表的字段，其余表只有表名)\n");
                    }
                } else {
                    // 表多时逐表拉字段成本高且易超长：由模型基于表名作答，必要时引导查元数据表
                    sb.append("\n(表数量较多，未逐表列出字段。需要某表字段时：请引导用户在左侧 Catalog 选中具体表后"
                            + "再提问，不要自行编写 information_schema 等元数据探测 SQL。)\n");
                }
            } catch (Exception e) {
                log.warn("Build schema context failed, databaseId: {}, schema: {}", databaseId, schemaName, e);
                return "(获取表信息失败：" + e.getMessage() + ")";
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_SCHEMA_CHARS) {
            result = result.substring(0, MAX_SCHEMA_CHARS) + "\n... (schema 过长，已截断)";
        }
        return result;
    }

    /** 追加单表的列信息与外键关系（元数据） */
    private void appendTableDetail(StringBuilder sb, Integer databaseId, String schemaName, String tableName) {
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
        } catch (Exception e) {
            log.warn("List columns failed, table: {}", tableName, e);
            sb.append("  (获取列信息失败)\n");
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
