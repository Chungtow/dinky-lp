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
 * 规则执行时机（三档）。
 *
 * <p>{@code DRAFT} 只读 SQL 与任务字段（不调接口），因此可进提示词；{@code PRECHECK} 必须查环境，
 * 不进提示词（省 token）；{@code RUNTIME} 在提交后断言，与"写入前校验"无关。
 *
 * @since 2026/11/02
 */
public enum RulePhase {
    /** 生成提示 + 写入前静态校验 */
    DRAFT,
    /** 写入前需调接口/查库 */
    PRECHECK,
    /** 提交后断言 */
    RUNTIME;

    public static RulePhase of(String value) {
        if (value == null) {
            return DRAFT;
        }
        String v = value.trim().toLowerCase();
        if ("precheck".equals(v)) {
            return PRECHECK;
        }
        if ("runtime".equals(v)) {
            return RUNTIME;
        }
        return DRAFT;
    }
}
