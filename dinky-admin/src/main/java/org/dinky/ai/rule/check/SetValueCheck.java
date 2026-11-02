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
 * {@code sql.set_value}：某个 SET 的取值不得落在禁止清单里。
 *
 * <p>典型：{@code table.exec.mini-batch.size = -1} 表示无上限，会把批次攒到内存压力极限才吐，
 * 表现为 sink 长时间无写入、checkpoint 反复超时。
 *
 * @since 2026/11/02
 */
@Component
public class SetValueCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.set_value";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String key = CheckParams.str(rule, "key");
        if (StrUtil.isBlank(key)) {
            return RuleOutcome.unknown("规则缺少 check.key");
        }
        String value = context.facts().set(key);
        if (value == null) {
            return RuleOutcome.notApplicable("未设置 " + key);
        }
        List<String> forbid = CheckParams.strings(rule, "forbid_values");
        for (String item : forbid) {
            if (item != null && item.trim().equalsIgnoreCase(value.trim())) {
                return RuleOutcome.fail(key + " = " + value + "（禁止取值）");
            }
        }
        return RuleOutcome.pass(key + " = " + value);
    }
}
