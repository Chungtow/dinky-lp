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

package org.dinky.ai.draft;

import org.dinky.ai.rule.RuleSeverity;
import org.dinky.ai.rule.RuleStatus;
import org.dinky.ai.rule.RuleViolation;

import java.util.ArrayList;
import java.util.List;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * 校验结论 → 对外 JSON（工具返回值 / 接口返回值共用一套结构）。
 *
 * <p>结构固定为 {@code {passed, counts, violations[]}}：前端与模型都按这个契约读，
 * 不各自解析文案。{@code unknown} 单独计数，严禁混进 {@code passed}。
 *
 * @since 2026/11/02
 */
public final class RuleReport {

    private RuleReport() {}

    public static JSONObject build(List<RuleViolation> violations) {
        JSONObject result = new JSONObject();
        int errors = 0;
        int warns = 0;
        int unknowns = 0;
        JSONArray array = new JSONArray();
        if (violations != null) {
            for (RuleViolation violation : violations) {
                if (violation.getStatus() == RuleStatus.FAIL) {
                    if (violation.getSeverity() == RuleSeverity.ERROR) {
                        errors++;
                    } else {
                        warns++;
                    }
                } else if (violation.getStatus() == RuleStatus.UNKNOWN) {
                    unknowns++;
                } else {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.set("id", violation.getId());
                item.set("title", violation.getTitle());
                item.set(
                        "severity",
                        violation.getSeverity() == null
                                ? "warn"
                                : violation.getSeverity().name().toLowerCase());
                item.set(
                        "phase",
                        violation.getPhase() == null
                                ? "draft"
                                : violation.getPhase().name().toLowerCase());
                item.set("status", FlinkSqlStatus.of(violation.getStatus()));
                item.set("message", violation.getMessage());
                item.set("fix", violation.getFix());
                if (violation.getDetail() != null) {
                    item.set("detail", violation.getDetail());
                }
                array.add(item);
            }
        }
        JSONObject counts = new JSONObject();
        counts.set("error", errors);
        counts.set("warn", warns);
        counts.set("unknown", unknowns);
        result.set("passed", errors == 0);
        result.set("counts", counts);
        result.set("violations", array);
        return result;
    }

    /** 只保留需要人看的条目（PASS / NOT_APPLICABLE 不进报告） */
    public static List<RuleViolation> meaningful(List<RuleViolation> violations) {
        List<RuleViolation> list = new ArrayList<>();
        if (violations == null) {
            return list;
        }
        for (RuleViolation violation : violations) {
            if (violation.getStatus() == RuleStatus.FAIL || violation.getStatus() == RuleStatus.UNKNOWN) {
                list.add(violation);
            }
        }
        return list;
    }

    /** 状态文案（与前端契约一致） */
    private static final class FlinkSqlStatus {

        private FlinkSqlStatus() {}

        static String of(RuleStatus status) {
            if (status == null) {
                return "unknown";
            }
            switch (status) {
                case PASS:
                    return "pass";
                case FAIL:
                    return "fail";
                case UNKNOWN:
                    return "unknown";
                default:
                    return "not_applicable";
            }
        }
    }
}
