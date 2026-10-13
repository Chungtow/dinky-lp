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

package org.dinky.interceptor;

import org.dinky.assertion.Asserts;
import org.dinky.context.TenantContextHolder;
import org.dinky.context.UserInfoContextHolder;
import org.dinky.data.dto.UserDTO;
import org.dinky.data.model.rbac.Tenant;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.lang.Opt;
import lombok.extern.slf4j.Slf4j;

/**
 * tenant interceptor
 *
 * <p>租户标识的解析优先级统一由 {@link TenantResolver} 提供（请求头 → 分槽 Cookie → 旧名 Cookie）。
 *
 * <p>本次（2026-10-13 双槽 Cookie 交叉污染修复）一并修复的既有缺陷：
 *
 * <ul>
 *   <li>原先只识别硬编码的 Cookie 名，导致测试槽（token 名 {@code dinky-lp-token}）的分支永不命中；
 *   <li>原先 {@code isPass} 赋值后从未参与返回，使 {@code preHandle} 恒返回 true，与校验结果脱节；
 *   <li>原先无 {@code afterCompletion}，{@link TenantContextHolder} 上下文在线程池复用下可能残留。
 * </ul>
 */
@Slf4j
public class TenantInterceptor implements AsyncHandlerInterceptor {

    /** 请求属性：标记本次请求的租户上下文由本拦截器设置，用于 afterCompletion 精确清理 */
    private static final String TENANT_CONTEXT_SET_ATTR = TenantInterceptor.class.getName() + ".TENANT_CONTEXT_SET";

    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler)
            throws Exception {
        // 未登录请求交由先注册的 Sa-Token 拦截器处理
        if (!StpUtil.isLogin()) {
            return AsyncHandlerInterceptor.super.preHandle(request, response, handler);
        }

        Integer tenantId = TenantResolver.resolve(request);
        if (tenantId == null) {
            // 未携带租户标识：单租户用户自动落入其唯一租户，多租户用户保持无租户上下文（需显式选择）
            tenantId = resolveSingleTenantId();
            if (tenantId == null) {
                log.warn("No tenantId carried by header or cookie, uri: {}", request.getRequestURI());
                return AsyncHandlerInterceptor.super.preHandle(request, response, handler);
            }
        }

        UserDTO userInfo = UserInfoContextHolder.get(StpUtil.getLoginIdAsInt());
        if (Asserts.isNull(userInfo)) {
            StpUtil.logout(StpUtil.getLoginIdAsInt());
            return false;
        }

        int finalTenantId = tenantId;
        List<Tenant> tenants = Opt.ofNullable(userInfo.getTenantList()).orElse(new ArrayList<>()).stream()
                .filter(t -> t.getId() == finalTenantId)
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(tenants)) {
            log.warn(
                    "User {} requested tenant {} which is not bound, force logout. uri: {}",
                    StpUtil.getLoginIdAsInt(),
                    finalTenantId,
                    request.getRequestURI());
            StpUtil.logout(StpUtil.getLoginIdAsInt());
            return false;
        }

        TenantContextHolder.set(finalTenantId);
        request.setAttribute(TENANT_CONTEXT_SET_ATTR, Boolean.TRUE);
        return AsyncHandlerInterceptor.super.preHandle(request, response, handler);
    }

    @Override
    public void afterCompletion(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull Object handler,
            Exception ex) {
        // 精确清理：仅当租户上下文由本拦截器设置时清理，避免线程复用导致的租户上下文残留
        if (Boolean.TRUE.equals(request.getAttribute(TENANT_CONTEXT_SET_ATTR))) {
            TenantContextHolder.clear();
            request.removeAttribute(TENANT_CONTEXT_SET_ATTR);
        }
    }

    /**
     * 回退：用户仅绑定一个租户时直接采用该租户
     *
     * @return tenant id, {@code null} when the user is bound to multiple tenants or none
     */
    private Integer resolveSingleTenantId() {
        UserDTO userInfo = UserInfoContextHolder.get(StpUtil.getLoginIdAsInt());
        if (Asserts.isNull(userInfo) || CollectionUtils.isEmpty(userInfo.getTenantList())) {
            return null;
        }
        List<Tenant> tenantList = userInfo.getTenantList();
        return tenantList.size() == 1 ? tenantList.get(0).getId() : null;
    }
}
