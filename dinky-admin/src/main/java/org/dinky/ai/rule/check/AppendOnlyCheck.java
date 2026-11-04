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
import org.dinky.ai.rule.SqlKit;

import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code query.append_only}：SQL 必须产出 append-only 流（无回撤）。
 *
 * <p>两条判据：
 * <ol>
 *   <li>命中 {@code forbid_patterns}（ROW_NUMBER / RANK 等 Top-N 写法）</li>
 *   <li>{@code forbid_group_by_without_window} 为真时，存在<b>未被窗口包裹</b>的 {@code GROUP BY}</li>
 * </ol>
 *
 * <p><b>F2 阻断级约束</b>：窗口聚合的 {@code GROUP BY window_start, window_end} 是合法的
 * append-only 形态（B2/B4），必须先判窗口再判 GROUP BY，否则合格作业会被误拦。
 *
 * @since 2026/11/02
 */
@Component
public class AppendOnlyCheck implements RuleCheck {

    @Override
    public String kind() {
        return "query.append_only";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql)) {
            return RuleOutcome.notApplicable("草稿没有 SQL");
        }
        List<String> forbid = CheckParams.strings(rule, "forbid_patterns");
        String hit = CheckParams.firstMatch(forbid, sql);
        if (hit != null) {
            return RuleOutcome.fail("命中回撤写法 /" + hit + "/（Top-N 排名类算子）");
        }
        if (CheckParams.bool(rule, "forbid_group_by_without_window", false)
                && SqlKit.hasGroupBy(sql)
                && !context.facts().windowAggregation()) {
            return RuleOutcome.fail("存在未被窗口（TUMBLE/HOP/CUMULATE/SESSION）包裹的 GROUP BY，会产生更新流");
        }
        return RuleOutcome.pass(context.facts().windowAggregation() ? "窗口聚合（append-only）" : null);
    }
}
