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

package org.dinky.ai;

import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.exception.BusException;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容的大模型流式客户端。
 *
 * <p>仅依赖 OpenAI 兼容的 <code>/chat/completions</code> 协议（<code>stream=true</code>），
 * 因此 DeepSeek / 通义 / 本地 Ollama 等只需在配置中心改 base-url 与模型名即可切换。
 *
 * <p><b>NOTE:</b> API Key 只在本类内使用，不会出现在任何对外返回或日志中。
 *
 * <p><b>阶段 1b</b>：新增 tools 入参与 tool_calls 解析。以下两条约束来自 2026-09-28 的探针实测，
 * 详见《AI Chat 执行层选型调研报告》§7.7 / §7.8：
 *
 * <ol>
 *   <li>凡是携带 <code>tool_calls</code> 的 assistant 消息，回灌时<b>必须</b>同时携带
 *       <code>reasoning_content</code>（空串亦可），否则服务端返回 400</li>
 *   <li>流式下 <code>tool_calls.arguments</code> 是逐片下发的，必须按 <code>index</code> 累积，
 *       在流结束时统一落定</li>
 * </ol>
 *
 * @since 2026/09/26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final String DATA_PREFIX = "data:";
    private static final String STREAM_DONE = "[DONE]";
    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    /** 阶段 3：profile 解析器——仅当调用方未显式传入 profile 时用于回落「默认 profile」（健壮性兜底） */
    private final LlmProfileResolver profileResolver;

    /**
     * 发起流式对话（无工具）。
     *
     * @param messages 完整消息列表（system / history / user）
     * @param onDelta 每个正文文本片段的回调
     * @param onReasoning 每个"思考过程"片段的回调（仅推理类模型会产生，如 DeepSeek 的
     *     <code>reasoning_content</code>）
     * @return 本次调用的 token 用量（模型未返回 usage 时为 0）
     */
    public TokenUsage streamChat(
            List<AiChatMessage> messages, LlmProfile profile, Consumer<String> onDelta, Consumer<String> onReasoning) {
        AiChatRound round = streamChatWithTools(messages, null, profile, onDelta, onReasoning, null);
        return round == null ? new TokenUsage() : round.getUsage();
    }

    /**
     * 发起一轮对话（可携带工具）。
     *
     * @param messages 完整消息列表；含 <code>tool_calls</code> 的 assistant 消息会被自动补上
     *     <code>reasoning_content</code>（探针约束 1）
     * @param tools 本次暴露给模型的工具；传 null 或空表示不启用工具（比 <code>tool_choice:none</code>
     *     可靠——后者实测会退化为空回复，见调研报告 §7.7）
     * @param onDelta 正文片段回调
     * @param onReasoning 思考过程片段回调
     * @param thinkingEnabled <code>Boolean.FALSE</code> 时显式关闭 thinking
     *     （唯一有效写法是 <code>thinking:{"type":"disabled"}</code>，实测
     *     <code>enable_thinking:false</code> 与 <code>reasoning_effort:minimal</code>
     *     均不被识别）；传 null 表示保持模型默认行为
     * @return 本轮完整产出：usage / 正文 / 思考过程 / 工具调用列表
     */
    public AiChatRound streamChatWithTools(
            List<AiChatMessage> messages,
            List<AiToolSpec> tools,
            LlmProfile requestProfile,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            Boolean thinkingEnabled) {
        // 阶段 3：连接与模型信息改由入参 profile 提供（此前每次调用直读全局单例）；
        // 未显式传入时回落「默认 profile」——保证既有调用点行为完全不变。
        LlmProfile profile = requestProfile == null ? profileResolver.defaultProfile() : requestProfile;
        boolean stream = profile.isStream();
        String baseUrl = StrUtil.trimToEmpty(profile.getBaseUrl());
        String apiKey = StrUtil.trimToEmpty(profile.getApiKey());
        String model = StrUtil.trimToEmpty(profile.getModel());
        String completionsPath = StrUtil.trimToEmpty(profile.getCompletionsPath());
        int timeoutSeconds = Math.max(profile.getTimeoutSeconds(), 1);
        int maxTokens = Math.max(profile.getMaxTokens(), 1);

        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(model)) {
            throw new BusException("AI 能力未正确配置：请先在配置中心-全局设置-LLM 配置中填写模型服务地址与模型名称");
        }

        String url = baseUrl + (StrUtil.isBlank(completionsPath) ? "/chat/completions" : completionsPath);
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "text/event-stream");
        if (StrUtil.isNotBlank(apiKey)) {
            post.setHeader("Authorization", "Bearer " + apiKey);
        }
        post.setConfig(RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(timeoutSeconds * 1000)
                .build());

        JSONObject body = buildBody(model, stream, maxTokens, messages, tools, thinkingEnabled);
        post.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));

        AiChatRound round = new AiChatRound();
        StreamState state = new StreamState(onDelta, onReasoning);
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = cn.hutool.core.io.IoUtil.read(
                        new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
                log.error("LLM request failed, status: {}, body: {}", statusCode, StrUtil.sub(errorBody, 0, 500));
                throw new BusException("大模型服务返回异常（HTTP " + statusCode + "），请检查模型服务配置");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                if (stream) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (StrUtil.isBlank(line) || !line.startsWith(DATA_PREFIX)) {
                            continue;
                        }
                        String data = StrUtil.trim(line.substring(DATA_PREFIX.length()));
                        if (StrUtil.isBlank(data) || STREAM_DONE.equals(data)) {
                            // 注意：此处刻意 continue 而非 break——历史上靠 readLine() 返回 null
                            // 结束循环，改成 break 会漏掉尾部的 usage 块
                            continue;
                        }
                        mergeUsage(round.getUsage(), dispatch(data, state));
                    }
                } else {
                    // 非流式：响应体是一次性 JSON（评测脚本走此分支）
                    String whole = cn.hutool.core.io.IoUtil.read(reader);
                    mergeUsage(round.getUsage(), parseWhole(whole, state));
                }
            }
            round.setContent(state.getContent().toString());
            round.setReasoningContent(state.getReasoning().toString());
            round.setFinishReason(state.getFinishReason());
            round.setToolCalls(state.buildToolCalls());
            return round;
        } catch (BusException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            log.error("LLM request timed out", e);
            throw new BusException("大模型请求超时，请稍后重试或调大超时时间");
        } catch (Exception e) {
            log.error("LLM request error", e);
            throw new BusException("大模型请求失败：" + e.getMessage());
        }
    }

    /** 组装请求体 */
    private JSONObject buildBody(
            String model,
            boolean stream,
            int maxTokens,
            List<AiChatMessage> messages,
            List<AiToolSpec> tools,
            Boolean thinkingEnabled) {
        JSONObject body = new JSONObject();
        body.set("model", model);
        body.set("stream", stream);
        body.set("max_tokens", maxTokens);
        body.set("temperature", 0.2);
        if (stream) {
            // 流式调用默认不返回 usage，显式要求后才能统计 token（审计与配额依赖此项）
            body.set("stream_options", new JSONObject().set("include_usage", true));
        }
        if (thinkingEnabled != null && !thinkingEnabled) {
            body.set("thinking", new JSONObject().set("type", "disabled"));
        }
        if (tools != null && !tools.isEmpty()) {
            JSONArray toolsArray = new JSONArray();
            for (AiToolSpec spec : tools) {
                if (spec != null) {
                    toolsArray.add(spec.toToolsItem());
                }
            }
            if (!toolsArray.isEmpty()) {
                body.set("tools", toolsArray);
            }
        }
        JSONArray messageArray = new JSONArray();
        if (messages != null) {
            for (AiChatMessage message : messages) {
                if (message != null) {
                    messageArray.add(toRequestMessage(message));
                }
            }
        }
        body.set("messages", messageArray);
        return body;
    }

    /**
     * 把一条内部消息序列化为协议消息。
     *
     * <p><b>探针约束 1</b>：<code>tool_calls</code> 非空时无条件输出 <code>reasoning_content</code>
     * （缺失时补空串）。这是服务端校验的最小放行条件，实测缺该字段会直接 400。
     */
    private JSONObject toRequestMessage(AiChatMessage message) {
        JSONObject json = new JSONObject();
        json.set("role", StrUtil.nullToEmpty(message.getRole()));
        json.set("content", StrUtil.nullToEmpty(message.getContent()));
        if (message.hasToolCalls()) {
            json.set("reasoning_content", StrUtil.nullToEmpty(message.getReasoningContent()));
            JSONArray calls = new JSONArray();
            for (AiToolCall call : message.getToolCalls()) {
                JSONObject function = new JSONObject();
                function.set("name", StrUtil.nullToEmpty(call.getName()));
                function.set("arguments", StrUtil.nullToEmpty(call.getArguments()));
                JSONObject item = new JSONObject();
                item.set("id", StrUtil.nullToEmpty(call.getId()));
                item.set("type", "function");
                item.set("function", function);
                calls.add(item);
            }
            json.set("tool_calls", calls);
        }
        if (StrUtil.isNotBlank(message.getToolCallId())) {
            json.set("tool_call_id", message.getToolCallId());
        }
        if (StrUtil.isNotBlank(message.getName())) {
            json.set("name", message.getName());
        }
        return json;
    }

    /**
     * 解析一个 SSE 数据块，把"正文""思考过程""工具调用增量"分发出去并累积到 {@link StreamState}。
     *
     * <p>兼容三种返回结构：流式 <code>choices[].delta</code>、非流式 <code>choices[].message</code>，
     * 以及推理模型的 <code>delta.reasoning_content</code> / <code>delta.reasoning</code>。
     */
    private JSONObject dispatch(String data, StreamState state) {
        try {
            JSONObject json = JSONUtil.parseObj(data);
            JSONObject usage = json.getJSONObject("usage");
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                // 流式最后一个块通常只有 usage、没有 choices
                return usage;
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject delta = first.getJSONObject("delta");
            if (delta == null) {
                delta = first.getJSONObject("message");
            }
            collect(first, delta, state);
            return usage;
        } catch (Exception e) {
            log.warn("Failed to parse LLM stream chunk: {}", StrUtil.sub(data, 0, 200));
            return null;
        }
    }

    /** 非流式响应：一次性解析出正文、思考过程、工具调用与 usage */
    private JSONObject parseWhole(String body, StreamState state) {
        try {
            JSONObject json = JSONUtil.parseObj(body);
            JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject first = choices.getJSONObject(0);
                collect(first, first.getJSONObject("message"), state);
            }
            return json.getJSONObject("usage");
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", StrUtil.sub(body, 0, 200));
            return null;
        }
    }

    /** 从 delta / message 中提取正文、思考过程、工具调用 */
    private void collect(JSONObject first, JSONObject delta, StreamState state) {
        if (delta == null) {
            return;
        }
        String finishReason = first.getStr("finish_reason");
        if (StrUtil.isNotBlank(finishReason)) {
            state.setFinishReason(finishReason);
        }
        // 思考过程：DeepSeek-R1/V4 系列为 reasoning_content，部分网关为 reasoning
        String reasoning = StrUtil.emptyToDefault(delta.getStr("reasoning_content"), delta.getStr("reasoning"));
        if (StrUtil.isNotEmpty(reasoning)) {
            state.appendReasoning(reasoning);
            if (state.getOnReasoning() != null) {
                state.getOnReasoning().accept(reasoning);
            }
        }
        String content = delta.getStr("content");
        if (StrUtil.isNotEmpty(content)) {
            state.appendContent(content);
            if (state.getOnDelta() != null) {
                state.getOnDelta().accept(content);
            }
        }
        JSONArray callChunks = delta.getJSONArray("tool_calls");
        if (callChunks != null && !callChunks.isEmpty()) {
            // 🔴 探针约束 2：arguments 逐片下发，按 index 累积
            for (int i = 0; i < callChunks.size(); i++) {
                JSONObject chunk = callChunks.getJSONObject(i);
                if (chunk == null) {
                    continue;
                }
                int index = chunk.getInt("index", i);
                ToolCallBuffer buffer = state.toolCall(index);
                String id = chunk.getStr("id");
                if (StrUtil.isNotBlank(id)) {
                    buffer.setId(id);
                }
                JSONObject function = chunk.getJSONObject("function");
                if (function == null) {
                    continue;
                }
                String name = function.getStr("name");
                if (StrUtil.isNotBlank(name)) {
                    buffer.setName(name);
                }
                String arguments = function.getStr("arguments");
                if (arguments != null) {
                    buffer.appendArguments(arguments);
                }
            }
        }
    }

    /** 累积 token 用量（多次调用中 usage 只出现在其中一块） */
    private void mergeUsage(TokenUsage target, JSONObject usage) {
        if (target == null || usage == null) {
            return;
        }
        int prompt = usage.getInt("prompt_tokens", 0);
        int completion = usage.getInt("completion_tokens", 0);
        target.setPromptTokens(target.getPromptTokens() + prompt);
        target.setCompletionTokens(target.getCompletionTokens() + completion);
    }

    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Close LLM http client failed", e);
        }
    }

    /** 单轮流式响应的累积容器 */
    private static class StreamState {

        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolCallBuffer> toolCalls = new LinkedHashMap<>();
        private final Consumer<String> onDelta;
        private final Consumer<String> onReasoning;
        private String finishReason;

        StreamState(Consumer<String> onDelta, Consumer<String> onReasoning) {
            this.onDelta = onDelta;
            this.onReasoning = onReasoning;
        }

        Consumer<String> getOnDelta() {
            return onDelta;
        }

        Consumer<String> getOnReasoning() {
            return onReasoning;
        }

        StringBuilder getContent() {
            return content;
        }

        StringBuilder getReasoning() {
            return reasoning;
        }

        String getFinishReason() {
            return finishReason;
        }

        void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }

        void appendContent(String text) {
            content.append(text);
        }

        void appendReasoning(String text) {
            reasoning.append(text);
        }

        ToolCallBuffer toolCall(int index) {
            ToolCallBuffer buffer = toolCalls.get(index);
            if (buffer == null) {
                buffer = new ToolCallBuffer();
                toolCalls.put(index, buffer);
            }
            return buffer;
        }

        /** 按 index 顺序落定本轮的工具调用 */
        List<AiToolCall> buildToolCalls() {
            List<AiToolCall> calls = new ArrayList<>();
            for (ToolCallBuffer buffer : toolCalls.values()) {
                AiToolCall call = buffer.toCall();
                if (call != null) {
                    calls.add(call);
                }
            }
            return calls;
        }
    }

    /** 单个工具调用的增量缓冲（id / name / arguments 分别在不同帧下发） */
    private static class ToolCallBuffer {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void setId(String id) {
            this.id = id;
        }

        void setName(String name) {
            this.name = name;
        }

        void appendArguments(String text) {
            arguments.append(text);
        }

        AiToolCall toCall() {
            if (StrUtil.isBlank(name)) {
                return null;
            }
            return AiToolCall.builder()
                    .id(id)
                    .name(name)
                    .arguments(arguments.toString())
                    .build();
        }
    }
}
