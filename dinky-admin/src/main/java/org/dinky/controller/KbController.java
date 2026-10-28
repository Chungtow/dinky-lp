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
import org.dinky.data.result.Result;
import org.dinky.service.KbCollectionService;
import org.dinky.service.kb.KbClient;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库（{@code @kb}）接口（知识库 @kb 接入 v1.0）。
 *
 * <p><b>批次 A 只有两个只读端点</b>：库列表（{@code @} 候选与范围声明用）与连通性。
 * 浏览库内文档（{@code /documents}、{@code /documents/{id}/content}）与推送在批次 B/C 落地——
 * 它们依赖 {@code KbClient} 的代理层，见实施计划 §4.5。
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
}
