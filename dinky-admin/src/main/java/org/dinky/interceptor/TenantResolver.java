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

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import cn.dev33.satoken.SaManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 租户标识解析器
 *
 * <p>统一「当前租户」的来源优先级，供 {@link TenantInterceptor} 与 {@code AdminController#getCurrentUserInfo}
 * 共用，避免两处解析链不一致：
 *
 * <ol>
 *   <li>请求头 {@code tenantId} —— 主通道，由前端从 localStorage 取值注入（localStorage 按 origin 隔离，双槽天然独立）
 *   <li>Cookie {@code <slot>-tenantId} —— 分槽 Cookie 新名，槽前缀取自 {@code sa-token.token-name}
 *   <li>Cookie {@code tenantId} —— 旧名兼容通道，保留一个发布周期
 * </ol>
 */
@Slf4j
public final class TenantResolver {

    /** 请求头中的租户标识（主通道） */
    public static final String TENANT_HEADER_NAME = "tenantId";

    /** 旧版租户 Cookie 名（兼容通道） */
    public static final String TENANT_COOKIE_NAME_LEGACY = "tenantId";

    /** Sa-Token token 名后缀，用于派生分槽 Cookie 名前缀 */
    private static final String TOKEN_NAME_SUFFIX = "-token";

    /** 分槽租户 Cookie 名后缀 */
    private static final String TENANT_COOKIE_NAME_SUFFIX = "-tenantId";

    private TenantResolver() {}

    /**
     * 按优先级解析租户标识
     *
     * @param request http request
     * @return tenant id, {@code null} when absent or illegal
     */
    public static Integer resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Integer fromHeader = parseTenantId(request.getHeader(TENANT_HEADER_NAME));
        if (fromHeader != null) {
            return fromHeader;
        }
        Integer fromSlotCookie = parseTenantId(getCookieValue(request, resolveSlotTenantCookieName()));
        if (fromSlotCookie != null) {
            return fromSlotCookie;
        }
        return parseTenantId(getCookieValue(request, TENANT_COOKIE_NAME_LEGACY));
    }

    /**
     * 由 {@code sa-token.token-name} 派生分槽租户 Cookie 名：
     *
     * <pre>
     *   dinky-token    → dinky-tenantId     （生产槽）
     *   dinky-lp-token → dinky-lp-tenantId  （测试槽）
     * </pre>
     *
     * 无法派生时返回 {@code null}，调用方跳过该级回退
     *
     * @return slot cookie name
     */
    public static String resolveSlotTenantCookieName() {
        if (SaManager.getConfig() == null) {
            return null;
        }
        String tokenName = SaManager.getConfig().getTokenName();
        if (tokenName != null && tokenName.endsWith(TOKEN_NAME_SUFFIX)) {
            return tokenName.substring(0, tokenName.length() - TOKEN_NAME_SUFFIX.length()) + TENANT_COOKIE_NAME_SUFFIX;
        }
        return null;
    }

    private static String getCookieValue(HttpServletRequest request, String cookieName) {
        if (cookieName == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static Integer parseTenantId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Illegal tenantId value: {}", value);
            return null;
        }
    }
}
