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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条规则的判定结论（对外报告单元）。
 *
 * <p>{@code message} / {@code fix} / {@code evidence} 来自 YAML（已做占位符替换），
 * 因此前端可以直接把三段原样展示给用户 —— 文案调整不需要改代码。
 *
 * @since 2026/11/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleViolation {

    private String id;

    private String title;

    private RuleSeverity severity;

    private RulePhase phase;

    private RuleStatus status;

    private String message;

    private String fix;

    private String evidence;

    /** 判定细节（实际取到的值 / 命中位置），用于排障，不展示给业务用户 */
    private String detail;

    /** 是否阻断（error 且 FAIL） */
    public boolean blocking() {
        return status == RuleStatus.FAIL && severity == RuleSeverity.ERROR;
    }
}
