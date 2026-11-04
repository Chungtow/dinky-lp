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

import org.dinky.ai.context.RequestSnapshot;
import org.dinky.ai.draft.FlinkJobDraft;
import org.dinky.ai.draft.RuleReport;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 规则校验的统一入口：**模型工具与前端直连接口共用**。
 *
 * <p><b>为什么要抽出来</b>：同一个校验逻辑存在两个调用方——① 模型调用
 * {@code validate_task_draft}；② 前端「校验当前 SQL」按钮（不经过模型）。
 * 两者必须给出一模一样的报告，否则"按钮说通过、模型说违规"这种矛盾会让人不敢信任何一个。
 *
 * <p><b>为什么日志要打规则编号</b>：UAT 时只有 {@code passed=false error=1} 这种计数，
 * 根本不知道是哪条规则命中（2026-11-03 踩过：只能靠翻 SQL 第 45 行才定位到
 * {@code FLINK-PARAM-001}）。这里把 fail 的 id 一并打出来。
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleValidationService {

    private final FlinkSqlRuleEngine ruleEngine;

    /** 校验草稿（无租户上下文时按"未知租户"处理） */
    public JSONObject validate(FlinkJobDraft draft, Integer tenantId) {
        return validate(draft, tenantId, null);
    }

    /**
     * 校验草稿并返回对外 JSON {@code {passed, counts, violations[]}}。
     *
     * @param draft    作业草稿（四段式）
     * @param tenantId 当前租户（用于跨租户资源校验；为空表示不限）
     * @param snapshot 请求快照（编辑区/绑定上下文，可为空）
     * @return 与 {@code validate_task_draft} 工具完全一致的报告结构
     */
    public JSONObject validate(FlinkJobDraft draft, Integer tenantId, RequestSnapshot snapshot) {
        if (draft == null || !draft.hasSql()) {
            JSONObject empty = RuleReport.build(new ArrayList<>());
            empty.set("message", "缺少 sql（完整 FlinkSQL 作业）");
            return empty;
        }
        RuleContext context = draft.toRuleContext();
        if (tenantId != null) {
            context.setTenantId(tenantId);
        }
        if (snapshot != null) {
            context.setRequestSnapshot(snapshot);
        }
        List<RuleViolation> violations = ruleEngine.validate(context);
        ruleEngine.sort(violations);
        List<RuleViolation> meaningful = RuleReport.meaningful(violations);
        JSONObject report = RuleReport.build(meaningful);
        JSONObject counts = report.getJSONObject("counts");
        log.info(
                "rule validate: passed={} error={} warn={} unknown={} fails={}",
                report.getBool("passed"),
                counts.getInt("error"),
                counts.getInt("warn"),
                counts.getInt("unknown"),
                describe(meaningful));
        return report;
    }

    /** {@code FLINK-PARAM-001(error), FLINK-SINK-002(unknown)}；无命中时返回 {@code -} */
    private static String describe(List<RuleViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (RuleViolation violation : violations) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(violation.getId())
                    .append('(')
                    .append(
                            violation.getStatus() == null
                                    ? "unknown"
                                    : violation.getStatus().name().toLowerCase())
                    .append(')');
        }
        return sb.toString();
    }
}
