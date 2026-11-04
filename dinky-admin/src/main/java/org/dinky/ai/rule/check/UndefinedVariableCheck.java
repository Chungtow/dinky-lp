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
import org.dinky.data.model.FragmentVariable;
import org.dinky.service.FragmentVariableService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.undefined_variable}：SQL 里引用的 {@code ${...}} 必须是**已定义的全局变量**。
 *
 * <p><b>为什么必须拦</b>：{@code ${...}} 是 Dinky 的全局变量 / JEXL 语法，不是 Flink 语法。
 * 未定义时：
 * <ul>
 *   <li>作业<b>没开</b>「全局变量」开关 → 占位符原样下发 → Flink 报 {@code Encountered "$"}</li>
 *   <li>作业<b>开了</b>开关但变量不存在 → {@code The variable name or jexl key of sql "${x}" does not exist}</li>
 * </ul>
 * 两种都是<b>提交阶段</b>失败，而草稿看上去"字段、connector、表名全对"，排查成本极高。
 *
 * <p>2026-11-04 实测：模型产出 {@code ${kafka.bootstrap.servers}} / {@code ${clickhouse.jdbc.url}} /
 * {@code ${clickhouse.username}} 等一整套臆造变量——上下文里明明给了真实地址，还给了"已定义的全局变量"清单
 * （当时清单为空，正确行为是<b>直接写实值或不写这两个选项</b>）。仅靠 prompt 约束不住，必须有硬规则。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
public class UndefinedVariableCheck implements RuleCheck {

    /**
     * 只匹配"看起来像变量名"的占位符：{@code ${字母开头，可含 . _ - 与数字}}。
     *
     * <p>JEXL 里的运算表达式（{@code ${1+1}}、{@code ${a == b}}）不在此列——它们不是变量引用，
     * 拦了会误伤；真写错的话提交阶段会给出更具体的报错。
     */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*}");

    /** 命中数上限：一个作业里出现几十个臆造变量时，报告没必要全列 */
    private static final int MAX_REPORTED = 8;

    private final FragmentVariableService fragmentVariableService;

    public UndefinedVariableCheck() {
        this(null);
    }

    @Autowired
    public UndefinedVariableCheck(FragmentVariableService fragmentVariableService) {
        this.fragmentVariableService = fragmentVariableService;
    }

    @Override
    public String kind() {
        return "sql.undefined_variable";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sql = context.facts().stripped();
        if (StrUtil.isBlank(sql)) {
            return RuleOutcome.notApplicable("草稿没有 SQL");
        }
        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(sql);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        if (used.isEmpty()) {
            return RuleOutcome.notApplicable("SQL 未使用 ${...} 占位符");
        }
        Set<String> defined = loadDefinedNames(context.getTenantId());
        if (defined == null) {
            return RuleOutcome.unknown("无法读取全局变量清单（变量服务不可用），跳过占位符校验");
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String name : used) {
            if (!defined.contains(name.toLowerCase(Locale.ROOT))) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("引用了未定义的全局变量: " + join(missing)
                    + "（当前租户已定义的变量: " + (defined.isEmpty() ? "无" : join(defined)) + "）"
                    + "——未定义变量会在**提交阶段**抛 does not exist");
        }
        return RuleOutcome.pass("${...} 均指向已定义的全局变量: " + join(used));
    }

    /** @return 已定义变量名（小写，便于大小写不敏感比对）；服务不可用时返回 null 表示"判不了" */
    private Set<String> loadDefinedNames(Integer tenantId) {
        if (fragmentVariableService == null) {
            return null;
        }
        try {
            List<FragmentVariable> variables = fragmentVariableService.listEnabledAll();
            Set<String> names = new LinkedHashSet<>();
            if (variables == null) {
                return names;
            }
            String currentTenant = tenantId == null ? null : String.valueOf(tenantId);
            for (FragmentVariable variable : variables) {
                if (variable == null || StrUtil.isBlank(variable.getName())) {
                    continue;
                }
                // 变量表按 name 唯一、跨租户同名会互相覆盖，这里显式按租户过滤
                if (currentTenant != null
                        && variable.getTenantId() != null
                        && !currentTenant.equals(String.valueOf(variable.getTenantId()))) {
                    continue;
                }
                names.add(variable.getName().trim().toLowerCase(Locale.ROOT));
            }
            return names;
        } catch (Exception e) {
            log.debug("undefined variable check: load variables failed", e);
            return null;
        }
    }

    private static String join(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown >= MAX_REPORTED) {
                sb.append(" …");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append('`').append(name).append('`');
            shown++;
        }
        return sb.toString();
    }
}
