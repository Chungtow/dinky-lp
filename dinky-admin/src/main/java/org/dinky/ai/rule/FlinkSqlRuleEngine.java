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

package org.dinky.ai.rule;

import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FlinkSQL 规则引擎（M1-A 的执行入口）。
 *
 * <p>职责边界很窄：<b>选规则 → 调 check 器 → 组装报告</b>。三件事都不做也不该做：
 * <ul>
 *   <li>不解析 SQL（前置工序在 {@link SqlKit} / {@link RuleContext#facts()}，只算一次）</li>
 *   <li>不实现判定（在 {@code ai/rule/check/*}，一个 kind 一个类）</li>
 *   <li>不读写业务数据（precheck / runtime 通过网关拿事实，失败一律 {@code UNKNOWN}）</li>
 * </ul>
 *
 * <p><b>未知 kind 的处理</b>：忽略该条 + WARN（不放开也不炸）—— 这是"配置写个不存在的 kind
 * 就静默全放行"的防线。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkSqlRuleEngine {

    private final RuleLoader ruleLoader;

    private final List<RuleCheck> checks;

    private final Map<String, RuleCheck> checkIndex = new ConcurrentHashMap<>();

    private final Set<String> warnedKinds = ConcurrentHashMap.newKeySet();

    public RuleSet ruleSet() {
        return ruleLoader.ruleSet();
    }

    /** 渲染进 prompt 的红线区块（S8 起替代 {@code FlinkContextProvider.RULES}） */
    public String renderPrompt() {
        return ruleSet().renderPrompt();
    }

    /** 规则引擎总开关（紧急回滚用；配置中心不可用时按"开启"处理，不能因为读不到开关就静默不校验） */
    public boolean enabled() {
        try {
            return SystemConfiguration.getInstances().isLlmRuleEngineEnable();
        } catch (Throwable t) {
            log.debug("rule engine switch unavailable, assume enabled", t);
            return true;
        }
    }

    /** 写入前静态校验（draft 档） */
    public List<RuleViolation> validateDraft(RuleContext context) {
        return run(RulePhase.DRAFT, context);
    }

    /** 写入前环境校验（precheck 档；接口失败 → UNKNOWN） */
    public List<RuleViolation> validatePrecheck(RuleContext context) {
        return run(RulePhase.PRECHECK, context);
    }

    /** 完整写入前校验（draft + precheck） */
    public List<RuleViolation> validate(RuleContext context) {
        List<RuleViolation> all = new ArrayList<>(validateDraft(context));
        all.addAll(validatePrecheck(context));
        return all;
    }

    /** 提交后断言（runtime 档） */
    public List<RuleViolation> assertRuntime(RuleContext context) {
        return run(RulePhase.RUNTIME, context);
    }

    public boolean hasBlocking(List<RuleViolation> violations) {
        if (violations == null) {
            return false;
        }
        for (RuleViolation violation : violations) {
            if (violation.blocking()) {
                return true;
            }
        }
        return false;
    }

    private List<RuleViolation> run(RulePhase phase, RuleContext context) {
        List<RuleViolation> violations = new ArrayList<>();
        if (context == null || !enabled()) {
            return violations;
        }
        for (Rule rule : ruleSet().rules()) {
            if (rule.getPhase() != phase || !rule.isEnabled()) {
                continue;
            }
            if (!applicable(rule, context, violations)) {
                continue;
            }
            RuleCheck check = resolve(rule);
            if (check == null) {
                continue;
            }
            RuleOutcome outcome;
            try {
                outcome = check.check(rule, context);
            } catch (Exception e) {
                log.warn("AI rule {} check failed", rule.getId(), e);
                outcome = RuleOutcome.unknown("规则执行异常: " + StrUtil.maxLength(e.getMessage(), 120));
            }
            violations.add(toViolation(rule, outcome, context));
        }
        return violations;
    }

    /**
     * applies_when 求值。
     *
     * <p>当前支持 {@code sink.connector}（F7：{@code jdbc} + CH url 已归一成 {@code clickhouse}）。
     * 无法确定目标库时整条跳过并记 {@code NOT_APPLICABLE}，不冒判。
     */
    private boolean applicable(Rule rule, RuleContext context, List<RuleViolation> violations) {
        Map<String, List<String>> conditions = rule.appliesWhenSafe();
        if (conditions.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> entry : conditions.entrySet()) {
            if (!"sink.connector".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            String actual = context.sinkConnector();
            if (actual == null) {
                violations.add(
                        toViolation(rule, RuleOutcome.notApplicable("无法确定 sink 目标库类型（未解析到 WITH connector）"), context));
                return false;
            }
            boolean matched = false;
            for (String expected : entry.getValue()) {
                if (expected != null && expected.equalsIgnoreCase(actual)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private RuleCheck resolve(Rule rule) {
        String kind = rule.kind();
        if (StrUtil.isBlank(kind)) {
            warnOnce("(missing)", rule.getId());
            return null;
        }
        RuleCheck cached = checkIndex.get(kind);
        if (cached != null) {
            return cached;
        }
        for (RuleCheck check : checks) {
            if (check != null && kind.equals(check.kind())) {
                checkIndex.put(kind, check);
                return check;
            }
        }
        warnOnce(kind, rule.getId());
        return null;
    }

    private void warnOnce(String kind, String ruleId) {
        if (warnedKinds.add(kind + "#" + ruleId)) {
            log.warn("AI rule {} uses unknown check kind '{}', rule ignored", ruleId, kind);
        }
    }

    private RuleViolation toViolation(Rule rule, RuleOutcome outcome, RuleContext context) {
        return RuleViolation.builder()
                .id(rule.getId())
                .title(rule.getTitle())
                .severity(rule.getSeverity())
                .phase(rule.getPhase())
                .status(outcome.getStatus())
                .message(render(rule.getMessage(), context))
                .fix(render(rule.getFix(), context))
                .evidence(StrUtil.maxLength(StrUtil.nullToEmpty(rule.getEvidence()), 600))
                .detail(outcome.getDetail())
                .build();
    }

    /** 占位符替换：{{sink.table}} / {{sink.connector}} / {{task.name}} / {{task.parallelism}} / {{cluster.id}} / {{env.id}} */
    private String render(String template, RuleContext context) {
        if (StrUtil.isBlank(template)) {
            return "";
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{{sink.table}}", StrUtil.nullToEmpty(context.facts().sinkTable()));
        values.put("{{sink.connector}}", StrUtil.nullToEmpty(context.sinkConnector()));
        values.put("{{task.name}}", StrUtil.nullToEmpty(context.getTaskName()));
        values.put(
                "{{task.parallelism}}",
                context.getParallelism() == null ? "" : String.valueOf(context.getParallelism()));
        values.put("{{cluster.id}}", context.getClusterId() == null ? "" : String.valueOf(context.getClusterId()));
        values.put("{{env.id}}", context.getEnvId() == null ? "" : String.valueOf(context.getEnvId()));
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 报告排序：error 在前、按 phase（draft → precheck → runtime）、secondary 置底 */
    public void sort(List<RuleViolation> violations) {
        violations.sort((left, right) -> {
            int bySeverity =
                    left.getSeverity() == right.getSeverity() ? 0 : (left.getSeverity() == RuleSeverity.ERROR ? -1 : 1);
            if (bySeverity != 0) {
                return bySeverity;
            }
            return Integer.compare(phaseOrder(left.getPhase()), phaseOrder(right.getPhase()));
        });
    }

    private static int phaseOrder(RulePhase phase) {
        if (phase == null) {
            return 3;
        }
        switch (phase) {
            case DRAFT:
                return 0;
            case PRECHECK:
                return 1;
            default:
                return 2;
        }
    }

    /** 把状态转成对外文案（UNKNOWN 明确标"未能判定"，不得显示成功） */
    public static String statusLabel(RuleStatus status) {
        if (status == null) {
            return "unknown";
        }
        return status.name().toLowerCase(Locale.ROOT);
    }
}
