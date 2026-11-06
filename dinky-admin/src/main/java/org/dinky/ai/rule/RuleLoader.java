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

package org.dinky.ai.rule;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 规则加载器：内置基线（classpath）∪ 部署层覆盖（外部文件），按 id 合并。
 *
 * <p><b>三层容错</b>（缺一层就会变成"配置写坏 = AI 全拦"）：
 * <ol>
 *   <li>外部文件缺失 → WARN，用基线（全新部署什么都不配也能跑）</li>
 *   <li>外部文件语法错 → WARN，<b>整体</b>回退基线（不做部分合并，避免"半套规则"）</li>
 *   <li>规则含未知 kind → 由引擎忽略该条 + WARN（不放开、也不炸）</li>
 * </ol>
 *
 * <p><b>不可覆盖</b>：{@code check.kind} 永远以基线为准 —— 那是代码契约，
 * 配置里写个不存在的 kind 必须无效而不是"静默全放行"。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
public class RuleLoader {

    public static final String BASELINE_RESOURCE = "ai/flinksql-rules-default.yaml";

    @Value("${dinky.ai.rules.path:}")
    private String externalPath;

    private volatile RuleSet cached;

    /** 当前生效规则集（首次调用时加载，之后复用内存） */
    public RuleSet ruleSet() {
        RuleSet local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load(externalPath);
                    cached = local;
                }
            }
        }
        return local;
    }

    /** 重新加载（规则文件改动后由运维重启容器触发；此处保留手工刷新入口） */
    public RuleSet reload() {
        synchronized (this) {
            cached = load(externalPath);
            return cached;
        }
    }

    public String externalPath() {
        return externalPath;
    }

    /** 供单测/装配直接指定外部文件的加载入口 */
    public RuleSet load(String path) {
        Map<String, Rule> base = loadResourceRules();
        if (base.isEmpty()) {
            log.warn("AI rule baseline missing: {}", BASELINE_RESOURCE);
        }
        if (StrUtil.isBlank(path)) {
            log.info("AI rule overlay not configured (dinky.ai.rules.path empty), use baseline: {} rules", base.size());
            return new RuleSet(new ArrayList<>(base.values()));
        }
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) {
            log.warn("AI rule overlay not found: {} (fallback to baseline, {} rules)", path, base.size());
            return new RuleSet(new ArrayList<>(base.values()));
        }
        Map<String, Rule> overlay;
        try {
            overlay = parseRules(readFile(file));
        } catch (Exception e) {
            log.warn("AI rule overlay parse failed: {} (whole overlay ignored, fallback to baseline)", path, e);
            return new RuleSet(new ArrayList<>(base.values()));
        }
        Map<String, Rule> merged = new LinkedHashMap<>(base);
        int overridden = 0;
        for (Map.Entry<String, Rule> entry : overlay.entrySet()) {
            Rule baseRule = merged.get(entry.getKey());
            merged.put(entry.getKey(), baseRule == null ? entry.getValue() : merge(baseRule, entry.getValue()));
            overridden++;
        }
        log.info(
                "AI rule set loaded: baseline={} overlay={} merged={} (path: {})",
                base.size(),
                overridden,
                merged.size(),
                path);
        return new RuleSet(new ArrayList<>(merged.values()));
    }

    private Map<String, Rule> loadResourceRules() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            return parseRules(IoUtil.readUtf8(in));
        } catch (Exception e) {
            log.warn("AI rule baseline load failed: {}", BASELINE_RESOURCE, e);
            return Collections.emptyMap();
        }
    }

    private static String readFile(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 解析 YAML 正文为「id → Rule」 */
    @SuppressWarnings("unchecked")
    public Map<String, Rule> parseRules(String yamlText) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(yamlText);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("rule file is not a YAML map");
        }
        Map<String, Object> root = (Map<String, Object>) loaded;
        Object schemaVersion = root.get("schema_version");
        if (schemaVersion != null && Convert.toInt(schemaVersion, 1) != 1) {
            throw new IllegalArgumentException("unsupported schema_version: " + schemaVersion);
        }
        Map<String, Object> defaults = asMap(root.get("defaults"));
        Object rulesNode = root.get("rules");
        if (!(rulesNode instanceof List)) {
            throw new IllegalArgumentException("rule file has no rules list");
        }
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (Object item : (List<Object>) rulesNode) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> node = (Map<String, Object>) item;
            String id = Convert.toStr(node.get("id"));
            if (StrUtil.isBlank(id)) {
                log.warn("AI rule without id ignored");
                continue;
            }
            rules.put(id, toRule(node, defaults));
        }
        return rules;
    }

    private Rule toRule(Map<String, Object> node, Map<String, Object> defaults) {
        Map<String, Object> check = new LinkedHashMap<>(asMap(node.get("check")));
        return Rule.builder()
                .id(Convert.toStr(node.get("id")))
                .title(Convert.toStr(node.get("title")))
                .severity(RuleSeverity.of(StrUtil.blankToDefault(
                        Convert.toStr(node.get("severity")), Convert.toStr(defaults.get("severity"), "warn"))))
                .phase(RulePhase.of(StrUtil.blankToDefault(
                        Convert.toStr(node.get("phase")), Convert.toStr(defaults.get("phase"), "draft"))))
                .tier(StrUtil.blankToDefault(
                        Convert.toStr(node.get("tier")), Convert.toStr(defaults.get("tier"), "primary")))
                .enabled(Convert.toBool(node.get("enabled"), Convert.toBool(defaults.get("enabled"), true)))
                .appliesWhen(toAppliesWhen(node.get("applies_when")))
                .check(check)
                .message(Convert.toStr(node.get("message"), ""))
                .fix(Convert.toStr(node.get("fix"), ""))
                .evidence(Convert.toStr(node.get("evidence"), ""))
                .prompt(Convert.toStr(node.get("prompt"), ""))
                .build();
    }

    /** 合并：覆盖可配置字段；{@code check.kind} 永远以基线为准 */
    private Rule merge(Rule base, Rule overlay) {
        Map<String, Object> check =
                new LinkedHashMap<>(overlay.getCheck() == null ? Collections.emptyMap() : overlay.getCheck());
        check.put("kind", base.kind());
        return Rule.builder()
                .id(base.getId())
                .title(StrUtil.isBlank(overlay.getTitle()) ? base.getTitle() : overlay.getTitle())
                .severity(overlay.getSeverity() == null ? base.getSeverity() : overlay.getSeverity())
                .phase(base.getPhase())
                .tier(StrUtil.isBlank(overlay.getTier()) ? base.getTier() : overlay.getTier())
                .enabled(overlay.isEnabled())
                .appliesWhen(
                        overlay.getAppliesWhen() == null
                                        || overlay.getAppliesWhen().isEmpty()
                                ? base.getAppliesWhen()
                                : overlay.getAppliesWhen())
                .check(check)
                .message(StrUtil.isBlank(overlay.getMessage()) ? base.getMessage() : overlay.getMessage())
                .fix(StrUtil.isBlank(overlay.getFix()) ? base.getFix() : overlay.getFix())
                .evidence(StrUtil.isBlank(overlay.getEvidence()) ? base.getEvidence() : overlay.getEvidence())
                .prompt(overlay.getPrompt() == null ? base.getPrompt() : overlay.getPrompt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> toAppliesWhen(Object value) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
            Object v = entry.getValue();
            List<String> values = new ArrayList<>();
            if (v instanceof List) {
                for (Object o : (List<Object>) v) {
                    values.add(String.valueOf(o));
                }
            } else if (v != null) {
                values.add(String.valueOf(v));
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }
}
