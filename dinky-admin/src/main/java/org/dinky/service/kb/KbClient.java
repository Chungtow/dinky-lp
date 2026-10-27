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

package org.dinky.service.kb;

import org.dinky.data.model.SystemConfiguration;

import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * kb-service 客户端（知识库 {@code @kb} 接入 v1.0）。
 *
 * <p><b>本类是唯一知道 kb-service 地址与密钥的地方</b>：{@code kb.base-url} / {@code kb.api-key}
 * 只从服务端配置读取，<b>绝不下发前端、绝不写日志、绝不回显</b>（实施计划 D-K5 / U-16）。
 *
 * <p><b>为什么不用 RestTemplate / WebClient</b>：本仓库没有任何此类封装（全仓无 RestTemplate），
 * 既有对外调用（{@link org.dinky.ai.LlmClient}、{@code HttpUtils}）一律用 Apache HttpClient。
 * 新增客户端沿用它，不引入新依赖，也保持超时配置写法一致。
 *
 * <p><b>超时语义</b>：检索走 15 s（向量检索 + 快照读取，冷缓存最慢），探活走 5 s。
 * 超时<b>不重试</b>——重试只会让用户多等一倍时间，而检索失败时模型应当明确说"不可用"
 * 而不是让用户以为知识库里没有。
 *
 * @since 2026/10/27
 */
@Slf4j
@Component
public class KbClient {

    /** 探活结果的本地缓存时长：避免每次对话都打一次 /healthz（embedding 探活是付费的） */
    private static final long PROBE_TTL_MS = 60_000L;

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final int PROBE_TIMEOUT_MS = 5_000;

    /** 检索超时（与工具 timeoutSeconds 对齐） */
    public static final int SEARCH_TIMEOUT_SECONDS = 15;

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    private volatile long lastProbeAt = 0L;

    private volatile boolean lastProbeOk = false;

    /**
     * kb-service 是否可用（带 60 s 缓存）。
     *
     * <p>判据是 {@code /healthz} 的 <b>embedding 分项</b>——minio / qdrant 都 up 但 embedding
     * 鉴权过期时，检索必然 503；只看 HTTP 200 会给出"可用"的错误结论。
     */
    public boolean isReachable() {
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_TTL_MS) {
            return lastProbeOk;
        }
        synchronized (this) {
            if (now - lastProbeAt < PROBE_TTL_MS) {
                return lastProbeOk;
            }
            boolean ok = false;
            try {
                JSONObject body = get("/healthz", PROBE_TIMEOUT_MS, false);
                JSONObject checks = body == null ? null : body.getJSONObject("checks");
                String embedding = checks == null ? null : checks.getStr("embedding");
                // 兼容 up / ok / true 三种写法：只要不是 down / error 就认为可用
                ok = embedding != null
                        && !"down".equalsIgnoreCase(embedding)
                        && !"error".equalsIgnoreCase(embedding)
                        && !"false".equalsIgnoreCase(embedding);
            } catch (Exception e) {
                log.warn("kb-service healthz probe failed: {}", e.getMessage());
                ok = false;
            }
            lastProbeAt = now;
            lastProbeOk = ok;
            return ok;
        }
    }

    /** 强制下一次 {@link #isReachable()} 重新探测（配置变更后调用） */
    public void invalidateProbe() {
        lastProbeAt = 0L;
    }

    /**
     * 检索。
     *
     * @param requestBody 完整的 {@code /v1/search} 请求体（{@code query} / {@code top_k} /
     *     {@code filter} / {@code expand} / {@code expand_k}）
     * @return 响应体，含 {@code results} 数组与 {@code took_ms}
     * @throws KbUnavailableException kb-service 不可用 / 鉴权失败 / 超时
     * @throws KbBadRequestException 请求本身不合法（如 collection 格式错、expand 拼错）
     */
    public JSONObject search(JSONObject requestBody) {
        return post("/v1/search", requestBody, SEARCH_TIMEOUT_SECONDS);
    }

    /** 知识库清单与计数（{@code GET /v1/collections}） */
    public JSONObject listCollections() {
        return get("/v1/collections", SEARCH_TIMEOUT_SECONDS, true);
    }

    private JSONObject post(String path, JSONObject body, int timeoutSeconds) {
        HttpPost post = new HttpPost(urlOf(path));
        post.setEntity(new StringEntity(body == null ? "{}" : body.toString(), StandardCharsets.UTF_8));
        post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8");
        return execute(post, timeoutSeconds);
    }

    private JSONObject get(String path, int timeoutSeconds, boolean needAuth) {
        HttpGet get = new HttpGet(urlOf(path));
        return execute(get, timeoutSeconds, needAuth);
    }

    private JSONObject execute(HttpRequestBase request, int timeoutSeconds) {
        return execute(request, timeoutSeconds, true);
    }

    private JSONObject execute(HttpRequestBase request, int timeoutSeconds, boolean needAuth) {
        SystemConfiguration config = SystemConfiguration.getInstances();
        String baseUrl = config.getKbBaseUrl();
        if (StrUtil.isBlank(baseUrl)) {
            throw new KbUnavailableException("知识库服务地址未配置（配置中心 - LLM 配置 - 知识库服务地址）");
        }
        boolean timeoutSet = false;
        try {
            request.setConfig(RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                    .setSocketTimeout(Math.max(timeoutSeconds, 1) * 1000)
                    .build());
            timeoutSet = true;
            if (needAuth) {
                String apiKey = config.getKbApiKey();
                if (StrUtil.isNotBlank(apiKey)) {
                    request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                }
            }
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                String body = response.getEntity() == null
                        ? ""
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (status >= 200 && status < 300) {
                    return StrUtil.isBlank(body) ? new JSONObject() : JSONUtil.parseObj(body);
                }
                // ⚠️ 错误消息里绝不能带请求头（会泄漏 API_KEY）
                String message = extractErrorMessage(body);
                if (status == 401 || status == 403) {
                    throw new KbUnavailableException("知识库鉴权失败（API Key 不匹配或已失效）：" + message);
                }
                if (status == 400) {
                    throw new KbBadRequestException(message);
                }
                if (status == 503) {
                    throw new KbUnavailableException("知识库暂不可用（embedding 服务异常）：" + message);
                }
                throw new KbUnavailableException("知识库返回 " + status + "：" + message);
            }
        } catch (KbUnavailableException | KbBadRequestException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new KbUnavailableException("知识库请求超时（>" + timeoutSeconds + "s）");
        } catch (Exception e) {
            throw new KbUnavailableException("知识库请求失败：" + e.getMessage());
        } finally {
            request.releaseConnection();
            if (timeoutSet) {
                request.reset();
            }
        }
    }

    private String urlOf(String path) {
        String baseUrl = SystemConfiguration.getInstances().getKbBaseUrl();
        String base = StrUtil.removeSuffix(StrUtil.trimToEmpty(baseUrl), "/");
        return base + path;
    }

    /** 从 kb-service 的错误体里取可读消息（其格式为 {@code {"detail":{"error":{"code","message"}}}}） */
    private String extractErrorMessage(String body) {
        if (StrUtil.isBlank(body)) {
            return "（无错误详情）";
        }
        try {
            JSONObject json = JSONUtil.parseObj(body);
            JSONObject detail = json.getJSONObject("detail");
            if (detail != null) {
                JSONObject error = detail.getJSONObject("error");
                if (error != null && StrUtil.isNotBlank(error.getStr("message"))) {
                    return error.getStr("message");
                }
                if (StrUtil.isNotBlank(detail.getStr("message"))) {
                    return detail.getStr("message");
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体（如网关返回的 HTML）：只截断，不回显全文
        }
        return StrUtil.maxLength(body, 200);
    }

    /** kb-service 不可用（网络 / 鉴权 / 超时 / 503）：调用方应显式声明"不可用"，不得据此断言口径 */
    public static class KbUnavailableException extends RuntimeException {
        public KbUnavailableException(String message) {
            super(message);
        }
    }

    /** 请求不合法（400）：多为 collection 格式或 expand 取值错误，属可自纠错误 */
    public static class KbBadRequestException extends RuntimeException {
        public KbBadRequestException(String message) {
            super(message);
        }
    }
}
