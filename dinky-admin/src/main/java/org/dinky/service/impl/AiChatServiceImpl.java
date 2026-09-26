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

import org.dinky.ai.LlmClient;
import org.dinky.ai.PromptStore;
import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.model.Column;
import org.dinky.data.model.ForeignKey;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.data.model.TableRelations;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatService;
import org.dinky.service.DataBaseService;
import org.dinky.sse.SseEmitterUTF8;

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
    /** 每张表最多包含的列数量 */
    private static final int MAX_COLUMNS_PER_TABLE = 40;
    /** schema 上下文的最大字符数，超出即截断（避免 token 爆炸） */
    private static final int MAX_SCHEMA_CHARS = 24000;
    /** 最多携带的历史对话轮次（一问一答算一轮，此处按消息条数算） */
    private static final int MAX_HISTORY_MESSAGES = 10;

    private final DataBaseService dataBaseService;
    private final LlmClient llmClient;

    private final ExecutorService chatExecutor = Executors.newCachedThreadPool();

    @Override
    public SseEmitter chat(AiChatRequest request) {
        int timeoutSeconds = Math.max(SystemConfiguration.getInstances().getLlmTimeout(), 1);
        SseEmitter emitter = new SseEmitterUTF8((timeoutSeconds + 30) * 1000L);
        chatExecutor.execute(() -> doChat(request, emitter));
        return emitter;
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
    private void doChat(AiChatRequest request, SseEmitter emitter) {
        try {
            if (!SystemConfiguration.getInstances().isLlmEnable()) {
                sendFrame(emitter, "error", "AI 能力未启用：请先在【配置中心 - 全局设置 - LLM 配置】中开启并配置模型服务。");
                emitter.complete();
                return;
            }
            if (request == null) {
                sendFrame(emitter, "error", "请求参数为空。");
                emitter.complete();
                return;
            }
            List<AiChatMessage> messages = buildMessages(request);
            llmClient.streamChat(
                    messages,
                    delta -> sendFrame(emitter, "content", delta),
                    delta -> sendFrame(emitter, "reasoning", delta));
            emitter.complete();
        } catch (Exception e) {
            log.error("AI chat failed", e);
            sendFrame(emitter, "error", e.getMessage());
            emitter.completeWithError(e);
        }
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

    /** 组装发送给大模型的消息：system（首轮含 schema）+ 历史 + 本轮 user */
    private List<AiChatMessage> buildMessages(AiChatRequest request) {
        String action = StrUtil.blankToDefault(request.getAction(), ACTION_TEXT_TO_SQL)
                .trim()
                .toUpperCase();
        boolean isExplain = ACTION_EXPLAIN.equals(action);
        // 首轮才携带 schema 上下文，后续轮次由会话历史承载上下文（省 token、降延迟）
        boolean firstTurn = StrUtil.isBlank(request.getSessionId());

        Map<String, String> params = new HashMap<>(4);
        params.put(PromptStore.PLACEHOLDER_SCHEMA, firstTurn ? buildSchemaContext(request) : "(schema 已在首轮提供，请沿用)");
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
                sb.append("Schema: ")
                        .append(schemaName)
                        .append(" (共 ")
                        .append(tables.size())
                        .append(" 张表)\n");
                sb.append("Tables:\n");
                int limit = Math.min(tables.size(), MAX_TABLE_LIST);
                for (int i = 0; i < limit; i++) {
                    Table table = tables.get(i);
                    sb.append("  - ").append(table.getName());
                    if (StrUtil.isNotBlank(table.getComment())) {
                        sb.append(" -- ").append(table.getComment());
                    }
                    sb.append("\n");
                }
                if (tables.size() > limit) {
                    sb.append("  ... (表过多，仅列出前 ").append(limit).append(" 张)\n");
                }
                if (tables.size() <= COLUMN_DETAIL_THRESHOLD) {
                    sb.append("\nColumns per table:\n");
                    for (int i = 0; i < limit; i++) {
                        appendTableDetail(
                                sb, databaseId, schemaName, tables.get(i).getName());
                    }
                } else {
                    // 表多时逐表拉字段成本高且易超长：由模型基于表名作答，必要时引导查元数据表
                    sb.append("\n(表数量较多，未逐表列出字段。需要某表字段时：让用户在该面板选中具体表，"
                            + "或使用 information_schema / SHOW COLUMNS 等元数据查询语句。)\n");
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
