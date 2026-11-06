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
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * check 器的原始判定结果（供引擎组装成 {@link RuleViolation}）。
 *
 * @since 2026/11/02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleOutcome {

    private RuleStatus status;

    /** 判定细节（命中位置 / 实际取到的值），会拼进报告，便于排障 */
    private String detail;

    public static RuleOutcome pass() {
        return new RuleOutcome(RuleStatus.PASS, null);
    }

    public static RuleOutcome pass(String detail) {
        return new RuleOutcome(RuleStatus.PASS, detail);
    }

    public static RuleOutcome fail(String detail) {
        return new RuleOutcome(RuleStatus.FAIL, detail);
    }

    public static RuleOutcome unknown(String detail) {
        return new RuleOutcome(RuleStatus.UNKNOWN, detail);
    }

    public static RuleOutcome notApplicable(String detail) {
        return new RuleOutcome(RuleStatus.NOT_APPLICABLE, detail);
    }
}
