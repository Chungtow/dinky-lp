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

import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 实例（profile）解析器（阶段 3：多 LLM 实例配置）。
 *
 * <p><b>职责</b>：把「请求里的 profileId」解析成一个不可变的 {@link LlmProfile}，并保证
 * <b>缺省 / 未命中 / 配置损坏</b> 三种情况一律<b>回落默认 profile</b>（即 {@code sys.llm.*} 单组字段），
 * 从而使评测脚本（不带 profileId）与旧客户端的行为<b>完全不变</b>。
 *
 * <p><b>为什么必须"请求级传值"</b>：{@code SystemConfiguration} 是静态单例，若用"临时改写全局值 +
 * 用完还原"的方式实现选 profile，两个并发会话会互相串号。本类只负责解析，<b>不写任何全局状态</b>。
 *
 * <p><b>安全</b>：解析结果的 {@link LlmProfile#getApiKey()} 仅用于服务端拼请求头；对外视图请走
 * {@code AiChatConfig} 中脱敏后的 profile（只含 {@code hasApiKey}）。
 *
 * @since 2026/10/04
 */
@Slf4j
@Component
public class LlmProfileResolver {

    /** 解析结果缓存时长（毫秒）：避免每轮对话重复解析；配置改动最多 5s 后生效 */
    private static final long CACHE_TTL_MS = 5000L;

    private volatile List<LlmProfile> cachedProfiles = Collections.emptyList();

    private volatile long cachedAt = 0L;

    /**
     * 解析本次请求应使用的 profile。
     *
     * @param profileId 请求携带的实例 id；为空 / {@code default} 表示使用默认 profile
     * @return 永不返回 null；未命中或配置损坏时回落默认 profile
     */
    public LlmProfile resolve(String profileId) {
        SystemConfiguration config = SystemConfiguration.getInstances();
        String wanted = StrUtil.trimToEmpty(profileId);
        if (StrUtil.isBlank(wanted) || LlmProfile.DEFAULT_ID.equalsIgnoreCase(wanted)) {
            return defaultProfile(config);
        }
        for (LlmProfile profile : listProfiles(config)) {
            if (wanted.equals(profile.getId())) {
                return profile;
            }
        }
        log.warn("LLM profile not found, fallback to default. profileId={}", wanted);
        return defaultProfile(config);
    }

    /** 全部已配置的实例（**不含**默认 profile）；未配置或 JSON 损坏时返回空列表 */
    public List<LlmProfile> listProfiles() {
        return listProfiles(SystemConfiguration.getInstances());
    }

    /** 默认 profile（由 {@code sys.llm.*} 单组字段构造） */
    public LlmProfile defaultProfile() {
        return defaultProfile(SystemConfiguration.getInstances());
    }

    private List<LlmProfile> listProfiles(SystemConfiguration config) {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_TTL_MS) {
            return cachedProfiles;
        }
        cachedProfiles = parse(config);
        cachedAt = now;
        return cachedProfiles;
    }

    private List<LlmProfile> parse(SystemConfiguration config) {
        String raw = StrUtil.trimToEmpty(config.getLlmProfiles());
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSONUtil.parseArray(raw);
            List<LlmProfile> profiles = new ArrayList<>(array.size());
            for (Object item : array) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                LlmProfile profile = fromJson(config, (JSONObject) item);
                if (profile != null) {
                    profiles.add(profile);
                }
            }
            return profiles;
        } catch (Exception e) {
            // 管理员手滑写坏 JSON 时，绝不能导致 AI Chat 整体不可用：回落默认 profile 并告警
            log.warn("Parse sys.llm.settings.profiles failed, fallback to default profile: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 由 JSON 项构造 profile；未填写的字段回落默认 profile 的同名字段 */
    private LlmProfile fromJson(SystemConfiguration config, JSONObject json) {
        LlmProfile fallback = defaultProfile(config);
        String id = StrUtil.trimToEmpty(json.getStr("id"));
        if (StrUtil.isBlank(id) || LlmProfile.DEFAULT_ID.equalsIgnoreCase(id)) {
            log.warn("Skip invalid LLM profile: id is missing or equals reserved 'default'");
            return null;
        }
        return LlmProfile.builder()
                .id(id)
                .name(StrUtil.blankToDefault(json.getStr("name"), id))
                .baseUrl(StrUtil.blankToDefault(json.getStr("baseUrl"), fallback.getBaseUrl()))
                .apiKey(StrUtil.blankToDefault(json.getStr("apiKey"), fallback.getApiKey()))
                .model(StrUtil.blankToDefault(json.getStr("model"), fallback.getModel()))
                .completionsPath(StrUtil.blankToDefault(json.getStr("completionsPath"), fallback.getCompletionsPath()))
                .timeoutSeconds(positiveOr(json.getInt("timeoutSeconds", 0), fallback.getTimeoutSeconds()))
                .maxTokens(positiveOr(json.getInt("maxTokens", 0), fallback.getMaxTokens()))
                .stream(json.getBool("stream", fallback.isStream()))
                .supportsTools(json.getBool("supportsTools", Boolean.TRUE))
                .note(StrUtil.trimToEmpty(json.getStr("note")))
                .build();
    }

    private LlmProfile defaultProfile(SystemConfiguration config) {
        return LlmProfile.builder()
                .id(LlmProfile.DEFAULT_ID)
                .name("默认")
                .baseUrl(StrUtil.trimToEmpty(config.getLlmBaseUrl()))
                .apiKey(StrUtil.trimToEmpty(config.getLlmApiKey()))
                .model(StrUtil.trimToEmpty(config.getLlmModel()))
                .completionsPath(StrUtil.trimToEmpty(config.getLlmCompletionsPath()))
                .timeoutSeconds(Math.max(config.getLlmTimeout(), 1))
                .maxTokens(Math.max(config.getLlmMaxTokens(), 1))
                .stream(config.isLlmStream())
                .supportsTools(true)
                .note("")
                .build();
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
