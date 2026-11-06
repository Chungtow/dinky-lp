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

package org.dinky.ai.context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import cn.hutool.core.io.IoUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 平台画像加载器：{@code classpath:ai/platform-profile-default.yaml} + 部署层覆盖。
 *
 * <p>容错口径与规则库完全一致（<b>配置坏了不能让 AI 变哑巴</b>）：
 * <ul>
 *   <li>覆盖文件不存在 → 用内置基线</li>
 *   <li>覆盖文件语法错 → 整体回退基线</li>
 *   <li>基线也读不到 → 返回空画像，区块渲染为空（不影响启动）</li>
 * </ul>
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
public class PlatformProfileLoader {

    private static final String BASELINE = "ai/platform-profile-default.yaml";

    @Value("${dinky.ai.profile.path:}")
    private String externalPath;

    private volatile PlatformProfile cached;

    public PlatformProfile profile() {
        PlatformProfile local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    /** 覆盖文件路径（日志用） */
    public String externalPath() {
        return externalPath;
    }

    PlatformProfile load() {
        PlatformProfile baseline = readBaseline();
        PlatformProfile overlay = readOverlay();
        PlatformProfile merged = baseline.overlay(overlay);
        log.info(
                "AI platform profile loaded: pipelines={} topics={} aliases={} overlay={}",
                merged.getPipelines().size(),
                merged.getTopics().size(),
                merged.getUrlAliases().size(),
                isBlank(externalPath) ? "(未配置)" : externalPath);
        return merged;
    }

    private PlatformProfile readBaseline() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BASELINE)) {
            if (in == null) {
                log.warn("platform profile baseline missing: {}", BASELINE);
                return new PlatformProfile();
            }
            return parse(IoUtil.readUtf8(in), BASELINE);
        } catch (Exception e) {
            log.warn("platform profile baseline load failed: {}", BASELINE, e);
            return new PlatformProfile();
        }
    }

    private PlatformProfile readOverlay() {
        if (isBlank(externalPath)) {
            return null;
        }
        Path path = Paths.get(externalPath.trim());
        if (!Files.isReadable(path)) {
            log.warn("platform profile overlay not found: {} (fallback to baseline)", path);
            return null;
        }
        try {
            return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), path.toString());
        } catch (Exception e) {
            log.warn("platform profile overlay invalid: {} (fallback to baseline)", path, e);
            return null;
        }
    }

    private static PlatformProfile parse(String text, String source) {
        Object loaded = new Yaml().load(text);
        if (!(loaded instanceof Map)) {
            log.warn("platform profile is not a map: {}", source);
            return new PlatformProfile();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) loaded;
        return PlatformProfile.of(raw);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
