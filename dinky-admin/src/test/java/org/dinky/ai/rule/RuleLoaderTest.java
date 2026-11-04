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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/**
 * 规则加载三层容错（缺文件 / 语法错 / 覆盖合并）。
 *
 * <p>这三条是"配置写坏不至于把 AI 变成哑巴"的底线：任何一条退化成抛异常，
 * 生产上改个 YAML 就会让整个 AI 对话不可用。
 *
 * @since 2026/11/02
 */
public class RuleLoaderTest {

    /**
     * 内置基线规则条数（`ai/flinksql-rules-default.yaml`）。
     *
     * <p>写死而不是"读出来比较"：这样"少加载了几条"（比如某条 id 写错被去重）能被发现。
     * 2026-11-03 由 21 更新为 22：规则库新增了一条（基线已含 22 条），旧断言随之失真。
     * <b>以后新增/删除基线规则必须同步改这里</b>。
     */
    private static final int BASELINE_RULE_COUNT = 22;

    @Test
    public void missingOverlayFallsBackToBaseline() {
        RuleSet ruleSet = new RuleLoader().load("/tmp/definitely-not-exists-flinksql-rules.yaml");
        Assert.assertEquals(BASELINE_RULE_COUNT, ruleSet.size());
    }

    @Test
    public void brokenOverlayFallsBackToBaseline() throws Exception {
        File file = File.createTempFile("flinksql-rules-broken", ".yaml");
        file.deleteOnExit();
        Files.write(file.toPath(), "rules: [ this is : not : valid".getBytes(StandardCharsets.UTF_8));
        RuleSet ruleSet = new RuleLoader().load(file.getAbsolutePath());
        Assert.assertEquals(BASELINE_RULE_COUNT, ruleSet.size());
    }

    @Test
    public void overlayOverridesConfigurableFieldsButNotKind() throws Exception {
        File file = File.createTempFile("flinksql-rules-overlay", ".yaml");
        file.deleteOnExit();
        String overlay = "schema_version: 1\n"
                + "rules:\n"
                + "  - id: FLINK-SINK-001\n"
                + "    severity: warn\n"
                + "    enabled: true\n"
                + "    message: 自定义文案\n"
                + "    check:\n"
                + "      kind: sql.not_contains\n";
        Files.write(file.toPath(), overlay.getBytes(StandardCharsets.UTF_8));
        RuleSet ruleSet = new RuleLoader().load(file.getAbsolutePath());
        Rule rule = ruleSet.get("FLINK-SINK-001");
        Assert.assertNotNull(rule);
        Assert.assertEquals(BASELINE_RULE_COUNT, ruleSet.size());
        Assert.assertEquals(RuleSeverity.WARN, rule.getSeverity());
        Assert.assertEquals("自定义文案", rule.getMessage());
        // 判定逻辑不可被配置篡改
        Assert.assertEquals("query.append_only", rule.kind());
    }

    @Test
    public void promptRenderingCoversPrimaryDraftRules() {
        RuleSet ruleSet = new RuleLoader().load(null);
        String prompt = ruleSet.renderPrompt();
        Assert.assertTrue(prompt.contains("min-pause"));
        Assert.assertTrue(prompt.contains("mini-batch"));
        // precheck / runtime 规则不进提示词（省 token，且它们本就需要查接口）
        Assert.assertFalse(prompt.contains("properties.group.id"));
        Assert.assertFalse(ruleSet.promptedRules().isEmpty());
    }
}
