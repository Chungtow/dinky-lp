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
 * 单条规则的判定结果（四态）。
 *
 * <p><b>{@link #UNKNOWN} 与 {@link #NOT_APPLICABLE} 必须分开</b>：
 * 前者是"该判但没判成"（接口超时、数据源不可达），后者是"规则本就不适用"（sink 不是 ClickHouse）。
 * 把 unknown 当成 pass 是本项目明令禁止的降级方式（计划 §4.2）。
 *
 * @since 2026/11/02
 */
public enum RuleStatus {
    /** 通过 */
    PASS,
    /** 命中（按 severity 阻断或提示） */
    FAIL,
    /** 无法判定（接口超时 / 缺输入），不得视为通过 */
    UNKNOWN,
    /** 规则不适用于本次草稿 */
    NOT_APPLICABLE
}
