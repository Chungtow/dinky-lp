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
 * {@code ddl.doris_agg_key}：按天聚合落 Doris 必须 {@code UNIQUE KEY(stat_date)} + Merge-on-Write。
 *
 * <p>判定输入是目标库建表 DDL（四段式 {@code ddl} 段）；缺失 DDL 时返回 {@code UNKNOWN}，
 * 不判 pass（S0-F4）。
 *
 * @since 2026/11/02
 */
@Component
public class DorisAggKeyCheck implements RuleCheck {

    @Override
    public String kind() {
        return "ddl.doris_agg_key";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String ddl = context.ddlText();
        if (StrUtil.isBlank(ddl)) {
            return RuleOutcome.unknown("缺少目标库 DDL（四段式 ddl 段），无法判断建表键定义");
        }
        List<String> triggerColumns = CheckParams.strings(rule, "when_ddl_columns_contain");
        boolean triggered = false;
        for (String column : triggerColumns) {
            if (StrUtil.isNotBlank(column)
                    && ddl.toLowerCase().contains(column.trim().toLowerCase())) {
                triggered = true;
                break;
            }
        }
        if (!triggered) {
            return RuleOutcome.notApplicable("DDL 中未出现 " + String.join("/", triggerColumns));
        }
        List<String> missing = new ArrayList<>();
        for (String regex : CheckParams.strings(rule, "require")) {
            if (CheckParams.firstMatch(java.util.Collections.singletonList(regex), ddl) == null) {
                missing.add(regex);
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("按天聚合表缺少 " + String.join(" 与 ", missing));
        }
        return RuleOutcome.pass();
    }
}
