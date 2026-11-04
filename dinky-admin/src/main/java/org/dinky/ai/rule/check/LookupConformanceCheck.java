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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.lookup_join_conformance}：lookup join 三要件齐备。
 *
 * <p>三要件缺任一项的失败表现各不相同（B3 实测各踩一次）：
 * 缺 hint → 退化成普通流 join；挂事件时间 → 被规划成 versioned temporal join 并报错；
 * 维表键类型与源库不一致 → 运行时 ClassCastException。
 *
 * <p><b>正则不要假设空格</b>（S0 发现）：B3 的写法是 {@code /*+ LOOKUP('table' = 'd') *}{@code /}，
 * {@code =} 两侧带空格。
 *
 * @since 2026/11/02
 */
@Component
public class LookupConformanceCheck implements RuleCheck {

    private static final Pattern LOOKUP_TRIGGER =
            Pattern.compile("(?i)(for\\s+system_time\\s+as\\s+of|/\\*\\+\\s*lookup\\s*\\()");

    @Override
    public String kind() {
        return "sql.lookup_join_conformance";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql) || !LOOKUP_TRIGGER.matcher(sql).find()) {
            return RuleOutcome.notApplicable("未使用 lookup / temporal join");
        }
        List<Map<String, Object>> requirements = CheckParams.children(rule, "require");
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> requirement : requirements) {
            if (requirement == null) {
                continue;
            }
            Object hint = firstNonNull(requirement, "hint_pattern");
            if (hint != null) {
                Pattern pattern = CheckParams.pattern(String.valueOf(hint));
                if (pattern == null || !pattern.matcher(sql).find()) {
                    problems.add("缺少 /*+ LOOKUP(...) */ 提示（会退化成普通流 join）");
                }
                continue;
            }
            Object temporal = firstNonNull(requirement, "temporal_key_pattern");
            if (temporal != null) {
                Pattern pattern = CheckParams.pattern(String.valueOf(temporal));
                if (pattern == null || !pattern.matcher(sql).find()) {
                    problems.add("FOR SYSTEM_TIME AS OF 未挂在处理时间列（proc_time）上");
                }
                continue;
            }
            Object keyType = firstNonNull(requirement, "dim_key_type");
            if (keyType != null) {
                if (!hasColumnOfType(sql, String.valueOf(keyType))) {
                    problems.add("维表键类型与源库不一致（期望 " + keyType + "）");
                }
            }
        }
        if (!problems.isEmpty()) {
            return RuleOutcome.fail(String.join("；", problems));
        }
        return RuleOutcome.pass();
    }

    private static Object firstNonNull(Map<String, Object> map, String key) {
        return map.get(key);
    }

    /** 维表 DDL 里是否存在声明为该类型的键列（如 {@code `id` INT}） */
    private static boolean hasColumnOfType(String sql, String type) {
        Pattern pattern = Pattern.compile("(?i)[`\\w]+\\s+" + Pattern.quote(type) + "\\b");
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String hit = matcher.group();
            if (hit.toLowerCase().contains(" bigint") || hit.toLowerCase().contains(" varchar")) {
                continue;
            }
            return true;
        }
        return false;
    }
}
