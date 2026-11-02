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

import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.required_set}：必须显式出现某些 SET，且取值要与任务字段一致。
 *
 * <p>三种附加条件（可组合）：
 * <ul>
 *   <li>{@code when_kafka_source_count_gte} —— 源数量不到阈值则本条不适用（PARAM-003）</li>
 *   <li>{@code must_match_task_field} —— SET 值必须等于任务字段（parallelism）</li>
 *   <li>{@code equals_task_name} —— SET 值必须等于作业名（pipeline.name）</li>
 * </ul>
 *
 * @since 2026/11/02
 */
@Component
public class RequiredSetCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.required_set";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        int threshold = CheckParams.integer(rule, "when_kafka_source_count_gte", 0);
        if (threshold > 0 && context.facts().kafkaSourceCount() < threshold) {
            return RuleOutcome.notApplicable("Kafka 源数 " + context.facts().kafkaSourceCount() + " < " + threshold);
        }
        List<String> keys = CheckParams.strings(rule, "keys");
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (String key : keys) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            String value = context.facts().set(key);
            if (value == null) {
                missing.add(key);
                continue;
            }
            String expected = expectedValue(rule, context);
            if (expected != null && !expected.equalsIgnoreCase(value.trim())) {
                mismatched.add(key + "=" + value + "（期望 " + expected + "）");
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("缺少 SET: " + String.join(", ", missing));
        }
        if (!mismatched.isEmpty()) {
            return RuleOutcome.fail("SET 取值与任务字段不一致: " + String.join(", ", mismatched));
        }
        return RuleOutcome.pass();
    }

    /** 期望值：任务名字段 / 任务并行度字段；都没配则返回 null（只查"有没有"） */
    private String expectedValue(Rule rule, RuleContext context) {
        if (CheckParams.bool(rule, "equals_task_name", false)) {
            return StrUtil.trimToNull(context.getTaskName());
        }
        String field = CheckParams.str(rule, "must_match_task_field");
        if (StrUtil.isBlank(field)) {
            return null;
        }
        if ("parallelism".equalsIgnoreCase(field) && context.getParallelism() != null) {
            return Convert.toStr(context.getParallelism());
        }
        return null;
    }
}
