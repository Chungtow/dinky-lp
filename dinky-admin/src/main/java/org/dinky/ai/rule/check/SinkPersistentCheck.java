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

package org.dinky.ai.rule.check;

import org.dinky.ai.context.FlinkCapabilities;
import org.dinky.ai.context.FlinkCapabilitiesLoader;
import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.sink_persistent}：交付 sink 必须是**持久化通道**，不能是调试 connector。
 *
 * <p><b>为什么单列一条</b>：{@code print} / {@code datagen} / {@code blackhole} 都在 connector 白名单内
 * （它们确实是集群已装的），所以 {@code sql.connector_whitelist} 会判 pass。
 * 但它们只把数据写到 TaskManager 日志或直接丢弃——作业 RUNNING、checkpoint 正常、sink 一行数据都没落库。
 * 2026-11-04 M1-01 实测：模型把 {@code 'connector' = 'print'} 当成交付 sink 写在结果表上，
 * 白名单校验放行，整条链路"看起来通过"。
 *
 * <p><b>白名单来源</b>：优先取部署层探测生成的能力画像里的 {@code debug_connectors}
 * （与 {@code ConnectorWhitelistCheck} 同一份事实），画像不可用时回退规则里的 {@code debug_connectors}。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
public class SinkPersistentCheck implements RuleCheck {

    private final FlinkCapabilitiesLoader capabilitiesLoader;

    public SinkPersistentCheck() {
        this(null);
    }

    @Autowired
    public SinkPersistentCheck(FlinkCapabilitiesLoader capabilitiesLoader) {
        this.capabilitiesLoader = capabilitiesLoader;
    }

    @Override
    public String kind() {
        return "sql.sink_persistent";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sinkConnector = context.sinkConnector();
        if (StrUtil.isBlank(sinkConnector)) {
            return RuleOutcome.unknown("无法确定 sink 的 connector（未解析到 INSERT INTO 的目标表或该表没有 WITH）");
        }
        String normalized = sinkConnector.trim().toLowerCase(Locale.ROOT);
        List<String> debug = loadDebugConnectors(rule);
        if (debug.isEmpty()) {
            return RuleOutcome.unknown("能力画像与规则均未给出 debug connector 清单，跳过 sink 持久化校验");
        }
        if (debug.contains(normalized)) {
            return RuleOutcome.fail("交付 sink 使用了调试 connector `" + normalized + "`（数据不会落库）" + "——调试 connector 清单: "
                    + String.join(", ", debug));
        }
        return RuleOutcome.pass("sink connector `" + normalized + "` 是持久化通道");
    }

    /** 画像优先、规则兜底：与 {@code ConnectorWhitelistCheck} 保持同一份事实来源 */
    private List<String> loadDebugConnectors(Rule rule) {
        List<String> result = new ArrayList<>();
        try {
            if (capabilitiesLoader != null) {
                FlinkCapabilities capabilities = capabilitiesLoader.capabilities();
                if (capabilities != null && !capabilities.getDebugConnectors().isEmpty()) {
                    for (String item : capabilities.getDebugConnectors()) {
                        if (StrUtil.isNotBlank(item)) {
                            result.add(item.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.debug("sink persistent: capabilities unavailable, fallback to rule config", e);
        }
        for (String item : CheckParams.strings(rule, "debug_connectors")) {
            if (StrUtil.isNotBlank(item)) {
                result.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }
}
