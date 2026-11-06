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

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * 上下文 7 段自检 + 协议常量自包含。
 *
 * <p>这两个东西是"上下文充分性"的机器判据：前者防止装配悄悄少一段，
 * 后者防止补发提示又被改回"只说有协议、不给骨架"（那正是 2026-11-03 补发二次失败的根因）。
 *
 * @since 2026/11/03
 */
public class FlinkContextSelfCheckTest {

    /** 一份"框架事实齐全"的最小上下文（不含作业绑定，对应新建 / 未保存 tab） */
    private static String frameworkOnlyContext() {
        return "## FlinkSQL 运行环境（AI 自动注入，仅供本次回答使用）\n"
                + "\n### 当前作业\n"
                + "- **未绑定具体作业**（新建 / 未保存的 tab）\n"
                + "\n### 集群能力（L1：探测自 Flink lib，**白名单之外一律不可用**）\n"
                + "- 可用 connector: `kafka`、`jdbc`\n"
                + "\n### 外部资源清单（L3：注册中心数据源）\n"
                + "- 已启用的数据源: id=34 `clickhouse-traccar` (ClickHouse)\n"
                + "\n### 平台默认配置（L2：配置中心）\n"
                + "- Kafka 接入地址: `kafka03:29092`\n"
                + "- 默认 sink 数据源: id=34 `clickhouse-traccar`\n"
                + "\n### 平台链路约定（人工维护）\n"
                + "- 标准链路：Kafka（源，raw/canal-json）→ ClickHouse（jdbc sink）\n"
                + "- Top-N 不落库：Top5 在查询端 `ORDER BY ... LIMIT 5`\n"
                + "\n### FlinkSQL 作业红线（规则库自动渲染）\n"
                + "- 不要把 min-pause 写进 SET\n"
                + FlinkJobProtocol.PROMPT;
    }

    @Test
    public void frameworkOnlyContextPassesAllSevenItems() {
        // 新建 / 未保存 tab 也必须是 7 段全绿：这正是 M1-01 的场景，
        // 也是 2026-11-03 那次"上下文整块为空 → 模型编 doris/占位符"的回归点。
        List<FlinkContextSelfCheck.Item> items = FlinkContextSelfCheck.check(frameworkOnlyContext());
        Assert.assertEquals(FlinkContextSelfCheck.EXPECTED_ITEMS, items.size());
        Assert.assertTrue(
                "框架事实齐全时必须 7 段全绿，实际失败: " + FlinkContextSelfCheck.describeFailures(items),
                FlinkContextSelfCheck.allPass(items));
    }

    @Test
    public void emptyContextFailsAllSevenItems() {
        List<FlinkContextSelfCheck.Item> items = FlinkContextSelfCheck.check("");
        Assert.assertEquals(FlinkContextSelfCheck.EXPECTED_ITEMS, items.size());
        Assert.assertFalse(FlinkContextSelfCheck.allPass(items));
        for (FlinkContextSelfCheck.Item item : items) {
            Assert.assertEquals("空上下文下每一项都必须是 fail", FlinkContextSelfCheck.FAIL, item.getStatus());
            Assert.assertFalse("fail 必须给出缺什么", item.getEvidence().isEmpty());
        }
    }

    @Test
    public void missingProtocolIsReportedById() {
        String withoutProtocol = frameworkOnlyContext().replace(FlinkJobProtocol.PROMPT, "");
        List<FlinkContextSelfCheck.Item> items = FlinkContextSelfCheck.check(withoutProtocol);
        Assert.assertFalse(FlinkContextSelfCheck.allPass(items));
        String failures = FlinkContextSelfCheck.describeFailures(items);
        Assert.assertTrue("必须点出 protocol 这一项，实际: " + failures, failures.contains("protocol"));
        Assert.assertFalse("不该误伤其它项，实际: " + failures, failures.contains("capability"));
    }

    @Test
    public void repairPromptIsSelfContained() {
        // 补发提示必须自带协议骨架，否则上下文缺失时补发必然二次失败。
        String prompt = FlinkJobProtocol.REPAIR_PROMPT;
        Assert.assertTrue(prompt.contains(FlinkJobProtocol.FENCE_TAG));
        Assert.assertTrue(prompt.contains("sql"));
        Assert.assertTrue(prompt.contains("customConfig"));
        Assert.assertTrue(prompt.contains("ddl"));
        Assert.assertTrue(prompt.contains("precheck"));
        Assert.assertTrue("必须内联围栏骨架", prompt.contains(FlinkJobProtocol.JSON_SKELETON));
        Assert.assertTrue("必须内联协议标题", prompt.contains("### 输出协议"));
    }

    @Test
    public void protocolConstantSatisfiesItsOwnCheck() {
        // 协议常量被改动（比如把围栏 tag 写错）时，自检第 7 项要能发现。
        Assert.assertTrue(FlinkContextSelfCheck.check(FlinkJobProtocol.PROMPT).stream()
                .filter(item -> "protocol".equals(item.getId()))
                .findFirst()
                .map(item -> FlinkContextSelfCheck.PASS.equals(item.getStatus()))
                .orElse(false));
    }
}
