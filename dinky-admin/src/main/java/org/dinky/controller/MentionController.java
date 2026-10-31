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

import org.dinky.ai.asset.AssetRef;
import org.dinky.ai.asset.MentionCandidateService;
import org.dinky.data.constant.PermissionConstants;
import org.dinky.data.result.Result;
import org.dinky.data.vo.MentionCandidateVO;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.core.util.StrUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;

/**
 * {@code @} 候选端点（{@code @} 候选升级 C1）。
 *
 * <p><b>为什么单独开一个 Controller</b>：候选是"读投影"语义，与对话/流式链路无关；
 * 放进 {@code AiChatController}（{@code /api/aiChat}）会让路径与职责都错位，
 * 也不利于后续把端点整体换成投影表实现。
 *
 * <p><b>与既有接口的关系</b>：{@code /api/skill/list}、{@code /api/semantic/published/*}、
 * {@code /api/kb/collections} 等<b>保持不动</b>（其它页面在用），只是 {@code @} 浮层不再直接调它们。
 *
 * <p><b>权限</b>：只要求 {@code aiChat:panel:use}。各类型的可见性在 provider 内部按各自口径裁剪
 * （skill 的 owner/tenant/public、kb 的 grant、语义的 project 可见性 + {@code semantic:view}），
 * 因此这里<b>不能再</b>用某一类的权限当门槛——那会让"有 A 无 B"的用户连 A 都拿不到。
 *
 * @since 2026/10/30
 */
@RestController
@Api(tags = "AI Chat Mention")
@RequestMapping("/api/ai/mention")
@SaCheckLogin
@RequiredArgsConstructor
public class MentionController {

    private final MentionCandidateService mentionCandidateService;

    /**
     * 候选列表。
     *
     * <p>无 {@code q} → 预取模式（列式全量 + ETag）；有 {@code q} → 服务端搜索（同快照过滤）。
     * 客户端回传 {@code If-None-Match} 且内容未变时返回 <b>304</b>。
     *
     * <p><b>必须声明 {@code Cache-Control: no-store}</b>（2026-10-31 修复）：候选是<b>用户态</b>数据
     * （内容由 {@code tenantId}/{@code userId} 与授权决定），本就不该进浏览器 HTTP 缓存。此前只发
     * {@code ETag} 不发缓存头，浏览器会自行做条件请求；而 {@code ETag} 曾<b>只哈希 {@code type+code}</b>，
     * 资产改名（kb 库名 / skill 描述）不改变它 → 恒 304 → 浏览器把旧响应体交回前端，表现为
     * 「改名后 `@` 候选永远是旧名、刷新/重登/重启全无效」。ETag 语义已同步修正（见
     * {@code MentionCandidateService#etagOf}），两者缺一不可。
     */
    @GetMapping("/candidates")
    @ApiOperation("List @ mention candidates (prefetch or search)")
    @SaCheckPermission(value = PermissionConstants.AI_CHAT_USE, mode = SaMode.OR)
    public ResponseEntity<MentionCandidateVO> candidates(
            @RequestParam(value = "types", required = false) String types,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MentionCandidateVO vo = mentionCandidateService.candidates(types, q, limit);
        String etag = vo.getEtag();
        if (StrUtil.isNotBlank(ifNoneMatch) && StrUtil.isNotBlank(etag)) {
            // 客户端可能带回 W/ 前缀或带引号的原值，统一去掉再比
            String client = ifNoneMatch.trim().replace("W/", "").replace("\"", "");
            if (client.equals(etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(etag)
                        .cacheControl(CacheControl.noStore())
                        .build();
            }
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noStore())
                .body(vo);
    }

    /**
     * 单条详情（副标题 / 摘要）。
     *
     * <p>预取模式为了省内存不下发 subtitle，选中后再拉这一条；点击时按 {@code code}
     * 重新做一次可见性判定，<b>不看候选缓存</b>。
     */
    @GetMapping("/detail")
    @ApiOperation("Get @ mention candidate detail")
    @SaCheckPermission(value = PermissionConstants.AI_CHAT_USE, mode = SaMode.OR)
    public Result<AssetRef> detail(@RequestParam("type") String type, @RequestParam("code") String code) {
        return Result.succeed(mentionCandidateService.detail(type, code));
    }
}
