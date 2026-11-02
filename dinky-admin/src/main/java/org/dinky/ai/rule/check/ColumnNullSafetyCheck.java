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
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;

/**
 * {@code sql.column_null_safety}：目标表的非 Nullable 列必须有 COALESCE 兜底。
 *
 * <p><b>输入来源（S0-F4）</b>：判定依据是"目标库建表 DDL 里哪些列是 Nullable"，它<b>不在</b>
 * Flink SQL 里，而在四段式的 {@code ddl} 段。因此：
 * 缺 DDL 时返回 {@code UNKNOWN} 而不是 {@code PASS} —— 否则这条规则等于没拦。
 *
 * @since 2026/11/02
 */
@Component
public class ColumnNullSafetyCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.column_null_safety";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String nullablePattern = CheckParams.str(rule, "nullable_columns_pattern", "Nullable\\(");
        String ddl = context.ddlText();
        if (StrUtil.isBlank(ddl)) {
            return RuleOutcome.unknown("缺少目标库 DDL（四段式 ddl 段），无法判断列是否 Nullable");
        }
        Pattern nullable = CheckParams.pattern(nullablePattern);
        if (nullable == null || !nullable.matcher(ddl).find()) {
            return RuleOutcome.pass("目标表未声明 Nullable 列");
        }
        String sql = context.facts().stripped();
        List<String> required = CheckParams.strings(rule, "require_coalesce_for");
        List<String> missing = new ArrayList<>();
        for (String column : required) {
            if (StrUtil.isBlank(column)) {
                continue;
            }
            if (!declaredNullable(ddl, column)) {
                continue;
            }
            if (!hasCoalesce(sql, column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("Nullable 列缺少 COALESCE 兜底: " + String.join(", ", missing));
        }
        return RuleOutcome.pass();
    }

    private static boolean declaredNullable(String ddl, String column) {
        Pattern pattern =
                Pattern.compile("(?i)[`\"]?" + Pattern.quote(column) + "[`\"]?\\s+Nullable\\s*\\(", Pattern.DOTALL);
        return pattern.matcher(ddl).find();
    }

    private static boolean hasCoalesce(String sql, String column) {
        Pattern pattern =
                Pattern.compile("(?i)coalesce\\s*\\(\\s*[`\\w.]*" + Pattern.quote(column) + "[`\\s,)]", Pattern.DOTALL);
        return pattern.matcher(sql).find();
    }
}
