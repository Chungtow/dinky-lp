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

/**
 * 规则严重级。
 *
 * <p>只有两档，刻意不引入 {@code info}：规则库的职责是"拦不拦得住"，
 * 三档以上会让"到底能不能写"变成需要解释的问题（见 {@code config/dinky-lp/ai/README.md} §5）。
 *
 * @since 2026/11/02
 */
public enum RuleSeverity {
    /** 阻断写入 */
    ERROR,
    /** 只提示，允许继续 */
    WARN;

    public static RuleSeverity of(String value) {
        if (value == null) {
            return WARN;
        }
        return "error".equalsIgnoreCase(value.trim()) ? ERROR : WARN;
    }
}
