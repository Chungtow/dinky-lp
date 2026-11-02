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
import org.dinky.ai.rule.RuntimeGateway;

import java.util.Locale;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * {@code runtime.plan_contains}：Flink 作业计划里必须出现某算子。
 *
 * <p>典型：lookup join 退化成普通流 join 时，作业照样 RUNNING、也在出数，
 * 但计划里只有 {@code Join(InnerJoin)} 而没有 {@code LookupJoin}（B3 实测）。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class PlanContainsCheck implements RuleCheck {

    private final RuntimeGateway gateway;

    @Override
    public String kind() {
        return "runtime.plan_contains";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        if (StrUtil.isBlank(context.getJobId())) {
            return RuleOutcome.notApplicable("尚无 Flink 作业 id（未提交）");
        }
        String plan = gateway.jobPlan(context.getJobId());
        if (plan == null) {
            return RuleOutcome.unknown("Flink 计划获取失败: " + context.getJobId());
        }
        String operator = CheckParams.str(rule, "must_contain_operator", "LookupJoin");
        if (!plan.toLowerCase(Locale.ROOT).contains(operator.toLowerCase(Locale.ROOT))) {
            return RuleOutcome.fail("作业计划中未出现算子 " + operator + "（可能已退化）");
        }
        return RuleOutcome.pass("计划含 " + operator);
    }
}
