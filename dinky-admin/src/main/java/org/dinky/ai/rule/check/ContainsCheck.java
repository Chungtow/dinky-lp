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

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.contains}：SQL 出现某特征时，必须同时满足后置条件。
 *
 * <p>两个后置条件：
 * <ul>
 *   <li>{@code require_set} —— 必须 SET 某 key 为某值（TZ-001：TO_TIMESTAMP 必须声明作业时区）</li>
 *   <li>{@code require_patterns} —— 必须命中其中之一（TZ-002：日志侧时间要 +8h）</li>
 * </ul>
 *
 * @since 2026/11/02
 */
@Component
public class ContainsCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.contains";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql)) {
            return RuleOutcome.notApplicable("草稿没有 SQL");
        }
        List<String> patterns = CheckParams.strings(rule, "patterns");
        if (CheckParams.firstMatch(patterns, sql) == null) {
            return RuleOutcome.notApplicable("未命中触发特征");
        }
        Map<String, Object> requireSet = CheckParams.child(rule, "require_set");
        if (!requireSet.isEmpty()) {
            String key = String.valueOf(requireSet.get("key"));
            String expected = requireSet.get("value") == null ? null : String.valueOf(requireSet.get("value"));
            String actual = context.facts().set(key);
            if (actual == null) {
                return RuleOutcome.fail("命中触发特征但缺少 SET '" + key + "' = '" + expected + "'");
            }
            if (expected != null && !expected.equalsIgnoreCase(actual.trim())) {
                return RuleOutcome.fail("SET '" + key + "' = '" + actual + "'（期望 '" + expected + "'）");
            }
        }
        List<String> requirePatterns = CheckParams.strings(rule, "require_patterns");
        if (!requirePatterns.isEmpty() && CheckParams.firstMatch(requirePatterns, sql) == null) {
            return RuleOutcome.fail("命中触发特征但缺少必需写法：" + String.join(" 或 ", requirePatterns));
        }
        return RuleOutcome.pass();
    }
}
