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
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.set_absent}：SET 里不得出现某些 key（判定"放错位置"）。
 *
 * <p>典型：{@code execution.checkpointing.min-pause} 走 SQL SET 静默无效，
 * 必须放任务自定义配置。命中时除了报错，还会检查 {@code require_custom_config}
 * 是否已在 customConfig 里给了替代写法（决定提示是"删掉 SET"还是"删掉并改配 customConfig"）。
 *
 * @since 2026/11/02
 */
@Component
public class SetAbsentCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.set_absent";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        Map<String, String> sets = context.facts().sets();
        List<String> keys = CheckParams.strings(rule, "keys");
        List<String> hits = new ArrayList<>();
        for (String key : keys) {
            if (StrUtil.isBlank(key)) {
                continue;
            }
            if (sets.containsKey(key.trim().toLowerCase(Locale.ROOT))) {
                hits.add(key.trim());
            }
        }
        if (hits.isEmpty()) {
            return RuleOutcome.pass();
        }
        Map<String, Object> requireConfig = CheckParams.child(rule, "require_custom_config");
        String configKey = requireConfig.isEmpty() ? null : String.valueOf(requireConfig.get("key"));
        boolean configured =
                configKey != null && context.customConfigSafe().containsKey(configKey.toLowerCase(Locale.ROOT));
        StringBuilder detail = new StringBuilder("SET 中出现禁止项: ").append(String.join(", ", hits));
        detail.append(configured ? "；customConfig 已配置 " + configKey : "；customConfig 缺少 " + configKey);
        return RuleOutcome.fail(detail.toString());
    }
}
