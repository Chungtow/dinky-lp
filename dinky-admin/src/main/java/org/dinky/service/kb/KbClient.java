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

import org.apache.http.Header;
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

    /**
     * 库内文档列表（{@code GET /v1/documents}，批次 B）。
     *
     * <p><b>{@code sort} 必须显式传</b>：kb-service 的默认值是 {@code doc_id}（为向后兼容），
     * 不传就是"按哈希乱序"，用户会认为"最近推的没进去"（实施计划 D-K6）。
     *
     * @param collection 库编码（必填：不做"全库扫描"，前端每次只能看一个库）
     * @param q 文件名/文档 id 前缀过滤，可空
     * @param sort {@code updated_desc}（默认）/ {@code updated_asc} / {@code doc_id}
     */
    public JSONObject documents(String collection, String q, String sort, int limit, int offset) {
        StringBuilder path = new StringBuilder("/v1/documents?collection=")
                .append(urlEncode(collection))
                .append("&sort=")
                .append(urlEncode(StrUtil.blankToDefault(sort, "updated_desc")))
                .append("&limit=")
                .append(Math.max(1, Math.min(limit, 100)))
                .append("&offset=")
                .append(Math.max(0, offset));
        if (StrUtil.isNotBlank(q)) {
            path.append("&q=").append(urlEncode(q));
        }
        return get(path.toString(), SEARCH_TIMEOUT_SECONDS, true);
    }

    /**
     * 单文档元信息（{@code GET /v1/documents/{id}}）。
     *
     * <p>代理层的可见性判定要用它的 {@code collection[]}——<b>只凭 docId 无法判断归属</b>，
     * 而"传了别人的 docId 就能读原文"是这批最直接的越权面。
     */
    public JSONObject documentDetail(String docId) {
        return get("/v1/documents/" + urlEncode(docId), SEARCH_TIMEOUT_SECONDS, true);
    }

    /**
     * 文档原文（{@code GET /v1/documents/{id}/content}，批次 B 只读查看器用）。
     *
     * <p>响应是<b>纯文本</b>+ {@code Content-Disposition} 里的文件名，不是 JSON；
     * {@code 404} 表示原文已被 {@code keep_raw=0} 清理（{@code raw_purged}），
     * 调用方要给可读提示而不是 500。
     */
    public KbDocumentContent documentContent(String docId) {
        KbRawResponse raw = send(
                new HttpGet(urlOf("/v1/documents/" + urlEncode(docId) + "/content")), SEARCH_TIMEOUT_SECONDS, true);
        if (!raw.isSuccess()) {
            throw errorOf(raw);
        }
        KbDocumentContent content = new KbDocumentContent();
        content.docId = docId;
        content.filename = raw.filename;
        content.contentType = raw.contentType;
        content.text = raw.body;
        return content;
    }

    /**
     * 查询串编码：文件名里常有空格、中文与 {@code #}，不编码会被 kb-service 截断。
     *
     * <p>注意用 {@code encode(String, String)} 而不是 {@code encode(String, Charset)}——
     * 后者是 Java 10+ 的 API，本仓库编译目标是 <b>JDK 8</b>。
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(StrUtil.nullToEmpty(value), StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 一定存在，此分支不可达；退化为原值以免整条链路失败
            return StrUtil.nullToEmpty(value);
        }
    }

    /** {@code Content-Disposition}：低版本 httpclient 的 {@code HttpHeaders} 里没有该常量，用字面量 */
    private static final String HEADER_CONTENT_DISPOSITION = "Content-Disposition";

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
        KbRawResponse raw = send(request, timeoutSeconds, needAuth);
        if (raw.isSuccess()) {
            return StrUtil.isBlank(raw.body) ? new JSONObject() : JSONUtil.parseObj(raw.body);
        }
        throw errorOf(raw);
    }

    /**
     * 把 HTTP 状态映射成三类异常。
     *
     * <p>⚠️ 错误消息里<b>绝不能</b>带请求头（会泄漏 API_KEY）；只取 kb-service 错误体里的
     * {@code detail.error.message}，拿不到就截断原文。
     */
    private RuntimeException errorOf(KbRawResponse raw) {
        String message = extractErrorMessage(raw.body);
        if (raw.status == 401 || raw.status == 403) {
            return new KbUnavailableException("知识库鉴权失败（API Key 不匹配或已失效）：" + message);
        }
        if (raw.status == 400) {
            return new KbBadRequestException(message);
        }
        if (raw.status == 404) {
            return new KbNotFoundException(message);
        }
        if (raw.status == 503) {
            return new KbUnavailableException("知识库暂不可用（embedding 服务异常）：" + message);
        }
        return new KbUnavailableException("知识库返回 " + raw.status + "：" + message);
    }

    /**
     * 发请求并取回<b>原始</b>响应（不解析 JSON）。
     *
     * <p>为什么要拆出裸响应：{@code GET /v1/documents/{id}/content} 返回的是**纯文本**，
     * 用 {@code JSONUtil.parseObj} 会把 Markdown 原文当成非法 JSON 报错。
     */
    private KbRawResponse send(HttpRequestBase request, int timeoutSeconds, boolean needAuth) {
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
                KbRawResponse raw = new KbRawResponse();
                raw.status = response.getStatusLine().getStatusCode();
                raw.body = response.getEntity() == null
                        ? ""
                        : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                Header contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE);
                raw.contentType = contentType == null ? null : contentType.getValue();
                Header disposition = response.getFirstHeader(HEADER_CONTENT_DISPOSITION);
                raw.filename = filenameOf(disposition == null ? null : disposition.getValue());
                return raw;
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

    /**
     * 从 {@code Content-Disposition} 取文件名。
     *
     * <p>kb-service 下发的形如 {@code inline; filename*=UTF-8''%E5%AE%9E%E6%88%98.md}——
     * 只有 {@code filename*} 段是 UTF-8 百分号编码，{@code filename=} 段是 ASCII 退化值（中文会变问号），
     * 所以优先取 {@code filename*}。
     */
    private String filenameOf(String disposition) {
        if (StrUtil.isBlank(disposition)) {
            return null;
        }
        int star = disposition.indexOf("filename*=");
        if (star >= 0) {
            String value = disposition.substring(star + "filename*=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            value = StrUtil.unWrap(value.trim(), '"');
            // 形如 UTF-8''%E5%AE%9E...：丢掉 charset 与语言段
            int sep = value.indexOf("''");
            if (sep >= 0) {
                value = value.substring(sep + 2);
            }
            try {
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (java.io.UnsupportedEncodingException e) {
                return value;
            }
        }
        int plain = disposition.indexOf("filename=");
        if (plain >= 0) {
            String value = disposition.substring(plain + "filename=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) {
                value = value.substring(0, semicolon);
            }
            return StrUtil.unWrap(value.trim(), '"');
        }
        return null;
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

    /**
     * 资源不存在（404）：文档没入库，或原文已被 {@code keep_raw=0} 清理（{@code raw_purged}）。
     *
     * <p>与 {@link KbUnavailableException} 分开，是因为<B>它不是"服务坏了"</B>——给用户看
     * "该文档原文未保留，无法在编辑区打开"比"知识库不可用"准确得多。
     */
    public static class KbNotFoundException extends RuntimeException {
        public KbNotFoundException(String message) {
            super(message);
        }
    }

    /** 未经解析的原始响应：状态、正文、content-type 与 {@code Content-Disposition} 里的文件名 */
    private static class KbRawResponse {
        private int status;
        private String body;
        private String contentType;
        private String filename;

        private boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /** 文档原文（纯文本），带上 kb-service 声明的文件名与 content-type */
    public static class KbDocumentContent {
        private String docId;
        private String filename;
        private String contentType;
        private String text;

        public String getDocId() {
            return docId;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }

        public String getText() {
            return text;
        }
    }
}
