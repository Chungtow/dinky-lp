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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条校验规则（数据侧）。
 *
 * <p><b>判定逻辑不在这里</b>：本类只是 YAML 的映射对象，{@code check.kind} 决定用哪个 check 器。
 * 之所以不允许配置里写判定表达式，是为了避免"写个不存在的 kind → 静默全放行"
 * （见 {@code config/dinky-lp/ai/README.md} §2）。
 *
 * @since 2026/11/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    private String id;

    private String title;

    private RuleSeverity severity;

    private RulePhase phase;

    /** primary = 现网主用链路（进提示词、报告置顶）；secondary = 备用链路（不进提示词、报告置底） */
    private String tier;

    private boolean enabled;

    /** 生效条件，如 {@code sink.connector: [clickhouse, jdbc]}；不满足则整条跳过 */
    private Map<String, List<String>> appliesWhen;

    /** check 器参数（kind + 各 check 器自己的键） */
    private Map<String, Object> check;

    private String message;

    private String fix;

    private String evidence;

    /** 渲染进提示词的短句；空串 = 不参与提示词 */
    private String prompt;

    public String kind() {
        Object value = check == null ? null : check.get("kind");
        return value == null ? null : String.valueOf(value).trim();
    }

    /** primary 且启用且提示词非空 → 进提示词 */
    public boolean prompted() {
        return enabled
                && phase == RulePhase.DRAFT
                && (tier == null || "primary".equalsIgnoreCase(tier))
                && prompt != null
                && !prompt.trim().isEmpty();
    }

    public Map<String, List<String>> appliesWhenSafe() {
        return appliesWhen == null ? Collections.emptyMap() : appliesWhen;
    }
}
