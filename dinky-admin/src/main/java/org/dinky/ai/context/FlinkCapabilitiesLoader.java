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
 * 集群能力画像加载器：优先读部署层**探测生成**的文件，缺失/损坏才回退内置基线。
 *
 * <p>与规则库同一套容错口径：<b>配置坏了不能让 AI 变哑巴</b>。
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
public class FlinkCapabilitiesLoader {

    private static final String BASELINE = "ai/flink-capabilities-default.yaml";

    @Value("${dinky.ai.capabilities.path:}")
    private String externalPath;

    private volatile FlinkCapabilities cached;

    public FlinkCapabilities capabilities() {
        FlinkCapabilities local = cached;
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

    public String externalPath() {
        return externalPath;
    }

    FlinkCapabilities load() {
        FlinkCapabilities external = readExternal();
        if (external != null && !external.isEmpty()) {
            log.info(
                    "AI capabilities loaded: {} connectors, flink {}, generated {} ({})",
                    external.allConnectors().size(),
                    external.getFlinkVersion(),
                    external.getGeneratedAt(),
                    externalPath);
            return external;
        }
        FlinkCapabilities baseline = readBaseline();
        log.warn(
                "AI capabilities fallback to baseline: {} connectors, overlay={}",
                baseline.allConnectors().size(),
                isBlank(externalPath) ? "(未配置)" : externalPath);
        return baseline;
    }

    private FlinkCapabilities readBaseline() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BASELINE)) {
            if (in == null) {
                log.warn("capabilities baseline missing: {}", BASELINE);
                return new FlinkCapabilities();
            }
            return parse(IoUtil.readUtf8(in), BASELINE);
        } catch (Exception e) {
            log.warn("capabilities baseline load failed: {}", BASELINE, e);
            return new FlinkCapabilities();
        }
    }

    private FlinkCapabilities readExternal() {
        if (isBlank(externalPath)) {
            return null;
        }
        Path path = Paths.get(externalPath.trim());
        if (!Files.isReadable(path)) {
            log.warn("capabilities file not found: {} (fallback to baseline)", path);
            return null;
        }
        try {
            return parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), path.toString());
        } catch (Exception e) {
            log.warn("capabilities file invalid: {} (fallback to baseline)", path, e);
            return null;
        }
    }

    private static FlinkCapabilities parse(String text, String source) {
        Object loaded = new Yaml().load(text);
        if (!(loaded instanceof Map)) {
            log.warn("capabilities file is not a map: {}", source);
            return new FlinkCapabilities();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) loaded;
        return FlinkCapabilities.of(raw);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
