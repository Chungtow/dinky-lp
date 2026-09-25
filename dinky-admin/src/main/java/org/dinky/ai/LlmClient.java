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
import org.dinky.data.model.SystemConfiguration;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容的大模型流式客户端。
 *
 * <p>仅依赖 OpenAI 兼容的 <code>/chat/completions</code> 协议（<code>stream=true</code>），
 * 因此 DeepSeek / 通义 / 本地 Ollama 等只需在配置中心改 base-url 与模型名即可切换。
 *
 * <p><b>NOTE:</b> API Key 只在本类内使用，不会出现在任何对外返回或日志中。
 *
 * @since 2026/09/26
 */
@Slf4j
@Component
public class LlmClient {

    private static final String DATA_PREFIX = "data:";
    private static final String STREAM_DONE = "[DONE]";
    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    /**
     * 发起流式对话。
     *
     * @param messages 完整消息列表（system / history / user）
     * @param onDelta 每个文本片段的回调
     */
    public void streamChat(List<AiChatMessage> messages, Consumer<String> onDelta) {
        SystemConfiguration config = SystemConfiguration.getInstances();
        String baseUrl = StrUtil.trimToEmpty(config.getLlmBaseUrl());
        String apiKey = StrUtil.trimToEmpty(config.getLlmApiKey());
        String model = StrUtil.trimToEmpty(config.getLlmModel());
        String completionsPath = StrUtil.trimToEmpty(config.getLlmCompletionsPath());
        int timeoutSeconds = Math.max(config.getLlmTimeout(), 1);
        int maxTokens = Math.max(config.getLlmMaxTokens(), 1);

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

        JSONObject body = new JSONObject();
        body.set("model", model);
        body.set("stream", true);
        body.set("max_tokens", maxTokens);
        body.set("temperature", 0.2);
        JSONArray messageArray = new JSONArray();
        if (messages != null) {
            for (AiChatMessage message : messages) {
                messageArray.add(new JSONObject()
                        .set("role", StrUtil.nullToEmpty(message.getRole()))
                        .set("content", StrUtil.nullToEmpty(message.getContent())));
            }
        }
        body.set("messages", messageArray);
        post.setEntity(new StringEntity(body.toString(), StandardCharsets.UTF_8));

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
                String line;
                while ((line = reader.readLine()) != null) {
                    if (StrUtil.isBlank(line) || !line.startsWith(DATA_PREFIX)) {
                        continue;
                    }
                    String data = StrUtil.trim(line.substring(DATA_PREFIX.length()));
                    if (StrUtil.isBlank(data) || STREAM_DONE.equals(data)) {
                        break;
                    }
                    String content = extractDelta(data);
                    if (StrUtil.isNotEmpty(content)) {
                        onDelta.accept(content);
                    }
                }
            }
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

    /** 从 SSE 的 data 行中取出增量文本（兼容 delta / message 两种返回结构） */
    private String extractDelta(String data) {
        try {
            JSONObject json = JSONUtil.parseObj(data);
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject delta = first.getJSONObject("delta");
            if (delta != null && StrUtil.isNotEmpty(delta.getStr("content"))) {
                return delta.getStr("content");
            }
            JSONObject message = first.getJSONObject("message");
            if (message != null) {
                return message.getStr("content");
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse LLM stream chunk: {}", StrUtil.sub(data, 0, 200));
            return null;
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Close LLM http client failed", e);
        }
    }
}
