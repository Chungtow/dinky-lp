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

package org.dinky.service;

import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.model.KbCollection;
import org.dinky.mybatis.service.ISuperService;

import java.util.List;

/**
 * 知识库（{@code @kb}）服务（知识库 @kb 接入 v1.0）。
 *
 * <p><b>权限一律走显式条件</b>（{@code owner_id} / {@code is_public} /
 * {@code dinky_kb_collection_grant}），
 * 不依赖 MyBatis-Plus 租户插件、也不依赖 {@code TenantContextHolder} 做过滤——与
 * {@link SkillService} 同一口径（AI 对话跑在异步线程，那里租户上下文会丢）。
 *
 * <p><b>两种取法都必须存在</b>：
 * <ul>
 *   <li>无参版本：请求线程用（内部取 {@code StpUtil} / {@code TenantContextHolder}）；</li>
 *   <li>显式 {@code actorId}/{@code tenantId} 版本：<b>异步线程</b>或"请求线程替别人算"时用——
 *       此时 {@code StpUtil} 已不可用，传值进去是唯一正确做法。</li>
 * </ul>
 *
 * @since 2026/10/27
 */
public interface KbCollectionService extends ISuperService<KbCollection> {

    /** 当前登录用户<b>可见</b>的知识库（仅 {@code enabled=1}），按更新时间倒序 */
    List<KbCollection> listVisible();

    /**
     * 指定用户可见的知识库（<b>显式条件</b>，不读 ThreadLocal）。
     *
     * @param actorId 操作者 id（不可为 null）
     * @param tenantId 租户 id（可为 null：无租户上下文时只放行 private/public）
     */
    List<KbCollection> listVisible(Integer actorId, Integer tenantId);

    /** 当前登录用户可见知识库的<b>轻量摘要</b>（注入 {@code @kb} 与工具快照用） */
    List<KbCollectionBrief> listVisibleBriefs();

    /** 指定用户可见知识库的轻量摘要（<b>异步线程安全</b>：不读 ThreadLocal） */
    List<KbCollectionBrief> listVisibleBriefs(Integer actorId, Integer tenantId);

    /** 按编码取（不做可见性校验；仅内部与运维用） */
    KbCollection getByCode(String code);

    /**
     * 按编码取<b>可见</b>的知识库；不存在 / 不可见 / 已停用一律返回 {@code null}。
     *
     * <p>返回 {@code null} 而不抛异常，是为了让调用方（{@code @kb} 注入器、代理层）自己决定
     * 措辞——注入器要回一句"你无权访问"，代理层要返回 403/404。
     */
    KbCollection getVisibleByCode(String code);

    /** 该编码是否在当前用户可见集内（{@code @kb} 越权判定的唯一入口） */
    boolean isVisible(String code);
}
