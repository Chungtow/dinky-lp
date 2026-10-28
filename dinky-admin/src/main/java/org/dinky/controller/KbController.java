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

import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.annotations.Log;
import org.dinky.data.constant.PermissionConstants;
import org.dinky.data.enums.BusinessType;
import org.dinky.data.exception.BusException;
import org.dinky.data.result.Result;
import org.dinky.data.vo.KbCollectionStatVO;
import org.dinky.data.vo.KbDocumentContentVO;
import org.dinky.data.vo.KbDocumentPageVO;
import org.dinky.data.vo.KbDocumentVO;
import org.dinky.service.KbCollectionService;
import org.dinky.service.kb.KbClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库（{@code @kb}）接口（知识库 @kb 接入 v1.0）。
 *
 * <p><b>端点分三批落地</b>：
 * <ul>
 *   <li>批次 A：库列表（{@code @} 候选与范围声明用）与连通性；</li>
 *   <li>批次 B（本版）：{@code /documents}、{@code /documents/{id}}、{@code /documents/{id}/content}
 *       ——<b>全部只读</b>，可见性在代理层收窄；</li>
 *   <li>批次 C/D：{@code POST /documents}（推送，需 {@code registration:kb:write} 且校验目标库写权限）、
 *       {@code PATCH /documents/{id}}（改归属）与授权 CRUD。</li>
 * </ul>
 *
 * <p><b>安全红线</b>：本类<b>不得</b>返回 kb-service 的地址或 API Key。{@code /health} 只回一个布尔，
 * 不回 base-url（实施计划 D-K5 / U-16）。
 *
 * @since 2026/10/27
 */
@Slf4j
@RestController
@Api(tags = "KB")
@RequestMapping("/api/kb")
@SaCheckLogin
@RequiredArgsConstructor
public class KbController {

    private final KbCollectionService kbCollectionService;

    private final KbClient kbClient;

    /**
     * 当前用户可见的知识库列表（{@code @} 候选的数据来源）。
     *
     * <p>只返回编码 / 名称 / 说明三样：这三样刚好够前端展示候选并插入句柄，
     * 多带归属人、属主 id、租户只会扩大泄漏面。
     */
    @Log(title = "KB Collection", businessType = BusinessType.QUERY)
    @GetMapping("/collections")
    @ApiOperation("List visible knowledge base collections")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<List<KbCollectionBrief>> listVisibleCollections() {
        List<KbCollectionBrief> briefs = kbCollectionService.listVisibleBriefs();
        // 可见集是排障第一现场：谁、看到几个、分别是哪几个（只记 code，不记名称与归属）。
        // 缺了这行，出问题只能靠猜——"候选里少了一个库"既可能是可见性算错、也可能是前端过滤。
        log.info(
                "KB visible collections: user={}, size={}, codes={}",
                currentUserIdQuietly(),
                briefs.size(),
                briefs.stream().map(KbCollectionBrief::getCode).collect(Collectors.toList()));
        return Result.succeed(briefs);
    }

    /** 取当前用户 id，仅用于日志（不抛异常，避免影响主流程）。 */
    private Object currentUserIdQuietly() {
        try {
            return StpUtil.getLoginIdDefaultNull();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * kb-service 是否可用（运维与 UAT 用）。
     *
     * <p>只回 {@code true/false}，<b>不回</b>地址、密钥、错误原文——那些信息足以让攻击者
     * 直接绕过 Dinky 访问 kb-service。
     */
    @Log(title = "KB Health", businessType = BusinessType.QUERY)
    @GetMapping("/health")
    @ApiOperation("Check whether kb-service is reachable")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<Boolean> health() {
        return Result.succeed(kbClient.isReachable());
    }

    // ------------------------------------------------------------------ 批次 B：只读浏览代理层

    /**
     * 可见库 + 计数（左侧栏「知识库」tab 的上半栏）。
     *
     * <p>数据来自两侧：<b>库目录与名称</b>取 Dinky 表（{@code listVisibleBriefs}，可见性的唯一口径），
     * <b>篇数/切片数/最近入库</b>取 kb-service 的 {@code /v1/collections}，按 {@code code} 对齐。
     *
     * <p><b>kb-service 不可用时降级为"库还在、计数为 0"</b>，不整块报错：库目录是本地的，
     * 没有理由因为内容侧挂了就让用户看不到自己有哪些库。计数为 0 且 {@code lastTs=0}，
     * 前端显示"暂无文档"——与"真·空库"无法区分，但比"知识库不可用、什么都不显示"更有用。
     */
    @Log(title = "KB Collection Stat", businessType = BusinessType.QUERY)
    @GetMapping("/collections/stats")
    @ApiOperation("List visible collections with document counters")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<List<KbCollectionStatVO>> listVisibleCollectionStats() {
        List<KbCollectionBrief> briefs = kbCollectionService.listVisibleBriefs();
        Map<String, KbCollectionStatVO> counters = new HashMap<>();
        try {
            JSONObject body = kbClient.listCollections();
            JSONArray items = body.getJSONArray("items");
            if (items != null) {
                for (Object item : items) {
                    if (!(item instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject json = (JSONObject) item;
                    String code = json.getStr("collection");
                    if (StrUtil.isBlank(code)) {
                        continue;
                    }
                    KbCollectionStatVO vo = new KbCollectionStatVO();
                    vo.setCode(code);
                    vo.setDocs(json.getLong("docs", 0L));
                    vo.setChunks(json.getLong("chunks", 0L));
                    vo.setLastTs(json.getLong("last_ts", 0L));
                    counters.put(code, vo);
                }
            }
        } catch (RuntimeException e) {
            // 内容侧挂了不影响"我有哪些库"：只记一条 warn，计数留 0
            log.warn("KB collection counters unavailable: {}", e.getMessage());
        }

        List<KbCollectionStatVO> list = new ArrayList<>();
        for (KbCollectionBrief brief : briefs) {
            KbCollectionStatVO vo = counters.get(brief.getCode());
            KbCollectionStatVO merged = new KbCollectionStatVO();
            merged.setCode(brief.getCode());
            merged.setName(brief.getName());
            merged.setDescription(brief.getDescription());
            merged.setDocs(vo == null ? 0L : vo.getDocs());
            merged.setChunks(vo == null ? 0L : vo.getChunks());
            merged.setLastTs(vo == null ? 0L : vo.getLastTs());
            list.add(merged);
        }
        // 最近有入库的排前面；空库自然沉底
        list.sort((a, b) -> Long.compare(b.getLastTs(), a.getLastTs()));
        return Result.succeed(list);
    }

    /**
     * 库内文档列表（左侧栏「知识库」tab 的下半栏）。
     *
     * <p><b>代理层必须自己滤可见性</b>：`collection` 不在当前用户可见集内就直接拒绝——
     * 前端隐藏不够，直接改请求参数就能绕过（实施计划 §4.5 / U-16~U-18）。
     *
     * <p>{@code sort} 缺省补 {@code updated_desc}（D-K6：kb-service 默认 {@code doc_id}，
     * 不传就是"按哈希乱序"，用户会以为最近推的没进去）。
     */
    @Log(title = "KB Document", businessType = BusinessType.QUERY)
    @GetMapping("/documents")
    @ApiOperation("List documents of a knowledge base collection")
    @ApiImplicitParams({
        @ApiImplicitParam(
                name = "collection",
                value = "Collection Code",
                required = true,
                dataType = "String",
                paramType = "query"),
        @ApiImplicitParam(
                name = "q",
                value = "Filename / doc_id prefix filter",
                dataType = "String",
                paramType = "query"),
        @ApiImplicitParam(
                name = "sort",
                value = "updated_desc (default) | updated_asc | doc_id",
                dataType = "String",
                paramType = "query"),
        @ApiImplicitParam(
                name = "limit",
                value = "Page size (1~100, default 20)",
                dataType = "Integer",
                paramType = "query"),
        @ApiImplicitParam(name = "offset", value = "Offset (default 0)", dataType = "Integer", paramType = "query")
    })
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<KbDocumentPageVO> listDocuments(
            @RequestParam String collection,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "20") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {
        requireVisibleCollection(collection);
        int pageSize = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        int pageOffset = Math.max(0, offset == null ? 0 : offset);
        String order = StrUtil.blankToDefault(sort, "updated_desc");
        JSONObject body = proxied(() -> kbClient.documents(collection, q, order, pageSize, pageOffset));

        KbDocumentPageVO page = new KbDocumentPageVO();
        page.setCollection(collection);
        page.setTotal(body.getLong("total", 0L));
        page.setLimit(pageSize);
        page.setOffset(pageOffset);
        page.setItems(toDocuments(body.getJSONArray("items")));
        return Result.succeed(page);
    }

    /**
     * 单文档元信息（推送后轮询状态 / 打开前的存在性校验）。
     *
     * <p>判定顺序：先问 kb-service 这条文档归属哪些库，再与我的可见集求交——
     * <b>不能只凭 docId 放行</b>，否则拿到别人的 docId 就能读原文。
     */
    @Log(title = "KB Document Detail", businessType = BusinessType.QUERY)
    @GetMapping("/documents/{docId}")
    @ApiOperation("Get a knowledge base document metadata")
    @ApiImplicitParam(name = "docId", value = "Document Id (sha256)", required = true, dataType = "String")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<KbDocumentVO> documentDetail(@PathVariable String docId) {
        JSONObject detail = requireVisibleDocument(docId);
        return Result.succeed(toDocument(detail));
    }

    /**
     * 文档原文（编辑区只读 tab 的数据源）。
     *
     * <p>{@code 404} 不是"服务坏了"——原文可能已被 {@code keep_raw=0} 清理，此时要给
     * **可读提示**而不是 500（实施计划 §4.5 / 先行记录 §10.1）。
     */
    @Log(title = "KB Document Content", businessType = BusinessType.QUERY)
    @GetMapping("/documents/{docId}/content")
    @ApiOperation("Read the raw text of a knowledge base document (read only)")
    @ApiImplicitParam(name = "docId", value = "Document Id (sha256)", required = true, dataType = "String")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_KB_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<KbDocumentContentVO> documentContent(@PathVariable String docId) {
        JSONObject detail = requireVisibleDocument(docId);
        KbClient.KbDocumentContent content = proxied(() -> kbClient.documentContent(docId));

        KbDocumentContentVO vo = new KbDocumentContentVO();
        vo.setDocId(docId);
        // 文件名以 detail 的 filename 为准：Content-Disposition 在某些代理链路上会被重写
        vo.setFilename(StrUtil.blankToDefault(detail.getStr("filename"), content.getFilename()));
        vo.setContentType(StrUtil.blankToDefault(content.getContentType(), detail.getStr("content_type")));
        vo.setContent(content.getText());
        vo.setSize(content.getText() == null ? 0 : content.getText().length());
        vo.setCollections(strList(detail.getJSONArray("collection")));
        return Result.succeed(vo);
    }

    // ------------------------------------------------------------------ 内部：可见性与字段映射

    /** 库编码必须在当前用户可见集内，否则直接拒绝（不静默降级成"可见集并集"） */
    private void requireVisibleCollection(String code) {
        if (StrUtil.isBlank(code)) {
            throw BusException.of("未指定知识库编码");
        }
        if (!kbCollectionService.isVisible(code)) {
            log.warn("KB denied: user={}, collection={}", currentUserIdQuietly(), code);
            throw BusException.of("无权访问该知识库：" + code);
        }
    }

    /**
     * 取文档元信息并校验可见性。
     *
     * <p>「文档所属库 ∩ 我的可见集」为空即拒绝：一条文档可以多归属（A 侧 dedup 合并策略），
     * 只要命中任意一个我有权看的库就放行。
     */
    private JSONObject requireVisibleDocument(String docId) {
        if (StrUtil.isBlank(docId)) {
            throw BusException.of("未指定文档 id");
        }
        JSONObject detail = proxied(() -> kbClient.documentDetail(docId));
        Set<String> mine = kbCollectionService.listVisibleBriefs().stream()
                .map(KbCollectionBrief::getCode)
                .collect(Collectors.toSet());
        List<String> owners = strList(detail.getJSONArray("collection"));
        boolean allowed = owners.stream().anyMatch(mine::contains);
        if (!allowed) {
            log.warn("KB denied: user={}, doc={}, collections={}", currentUserIdQuietly(), docId, owners);
            throw BusException.of("无权访问该文档（其所属知识库不在你的可见范围内）");
        }
        return detail;
    }

    /**
     * 把 kb-service 的调用异常转成业务异常（{@code Result.code != 0} + 可读 msg）。
     *
     * <p>不直接把 {@code RuntimeException} 抛给前端：那会变成 500 + 堆栈，
     * 而这里的每一种失败都有明确的用户动作（等一会儿再试 / 找管理员要授权 / 去 MinIO 看原文）。
     */
    private <T> T proxied(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (KbClient.KbNotFoundException e) {
            throw BusException.of("该文档原文未保留，无法在编辑区打开（可能已被清理）：" + e.getMessage());
        } catch (KbClient.KbUnavailableException | KbClient.KbBadRequestException e) {
            throw BusException.of(e.getMessage());
        }
    }

    private List<KbDocumentVO> toDocuments(JSONArray items) {
        List<KbDocumentVO> list = new ArrayList<>();
        if (items == null) {
            return list;
        }
        for (Object item : items) {
            if (item instanceof JSONObject) {
                list.add(toDocument((JSONObject) item));
            }
        }
        return list;
    }

    private KbDocumentVO toDocument(JSONObject json) {
        KbDocumentVO vo = new KbDocumentVO();
        vo.setDocId(json.getStr("doc_id"));
        vo.setFilename(json.getStr("filename"));
        vo.setSize(json.getLong("size"));
        vo.setContentType(json.getStr("content_type"));
        vo.setStatus(json.getStr("status"));
        vo.setChunks(json.getInt("chunks"));
        vo.setUpdatedAt(json.getLong("updated_at"));
        vo.setError(json.getStr("error"));
        vo.setCollections(strList(json.getJSONArray("collection")));
        vo.setOpenable(isOpenable(json.getStr("filename"), json.getStr("content_type")));
        return vo;
    }

    private List<String> strList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null) {
            return list;
        }
        for (Object item : array) {
            if (item != null) {
                list.add(String.valueOf(item));
            }
        }
        return list;
    }

    /**
     * 是否可在编辑区只读打开：首期<b>仅 Markdown / 代码类文本</b>，PDF / DOCX 置灰。
     *
     * <p>两重判据（任一满足即可）：MIME 是 {@code text/*}，或扩展名在文本白名单内。
     * 只看扩展名会把 {@code text/plain} 的无后缀文件（如 {@code Dockerfile}）误判为不可打开。
     */
    private boolean isOpenable(String filename, String contentType) {
        if (StrUtil.isNotBlank(contentType) && contentType.toLowerCase(Locale.ROOT).startsWith("text/")) {
            return true;
        }
        String name = StrUtil.nullToEmpty(filename).toLowerCase(Locale.ROOT);
        Set<String> textExt = new HashSet<>(java.util.Arrays.asList(
                "md", "markdown", "txt", "sql", "yaml", "yml", "py", "java", "scala", "json", "xml", "sh", "conf",
                "properties", "csv", "log", "kt", "js", "ts", "go", "rs", "c", "cpp", "h", "ini", "toml"));
        int dot = name.lastIndexOf('.');
        return dot >= 0 && textExt.contains(name.substring(dot + 1));
    }
}
