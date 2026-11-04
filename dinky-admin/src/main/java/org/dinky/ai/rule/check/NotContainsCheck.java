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

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.not_contains}：SQL 中不得出现某些字面量（按正则）。
 *
 * <p>支持条件化：{@code only_when_window_aggregate: true} 表示<b>仅当</b> SQL 含窗口聚合时才判
 * （S0-F3：changelog format 只在窗口聚合 / insert-only sink 处炸，无界 GROUP BY 是允许的）。
 *
 * @since 2026/11/02
 */
@Component
public class NotContainsCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.not_contains";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql)) {
            return RuleOutcome.notApplicable("草稿没有 SQL");
        }
        if (CheckParams.bool(rule, "only_when_window_aggregate", false)
                && !context.facts().windowAggregation()) {
            return RuleOutcome.notApplicable("未使用窗口聚合，该约束不适用");
        }
        List<String> patterns = CheckParams.strings(rule, "patterns");
        String hit = CheckParams.firstMatch(patterns, sql);
        if (hit != null) {
            return RuleOutcome.fail("命中禁止字面量 /" + hit + "/");
        }
        return RuleOutcome.pass();
    }
}
