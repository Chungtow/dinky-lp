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

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.doris_upsert_conformance}：回撤流写 Doris 必须声明 PK 且 {@code sink.parallelism=1}。
 *
 * <p>与 ClickHouse 的结论<b>相反</b>（CH insert-only 禁回撤，Doris 允许但要 PK）——
 * 这是"按目标库能力分型"而不是重复规则（决策 K7）。
 *
 * @since 2026/11/02
 */
@Component
public class DorisUpsertCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.doris_upsert_conformance";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql)) {
            return RuleOutcome.notApplicable("草稿没有 SQL");
        }
        List<String> retractPatterns = CheckParams.strings(rule, "only_when_retract");
        boolean retract = retractPatterns.isEmpty() || CheckParams.firstMatch(retractPatterns, sql) != null;
        if (!retract) {
            return RuleOutcome.notApplicable("纯 append 写法，不受 PK / sink.parallelism 约束");
        }
        List<String> require = CheckParams.strings(rule, "require");
        List<String> missing = new ArrayList<>();
        for (String regex : require) {
            if (CheckParams.firstMatch(java.util.Collections.singletonList(regex), sql) == null) {
                missing.add(regex.replace("\\s+", " ").replace("\\\\", ""));
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("Doris 回撤流缺少必要声明: " + String.join(" 与 ", missing));
        }
        return RuleOutcome.pass();
    }
}
