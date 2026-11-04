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
 * 规则判定器（判定逻辑的唯一归属）。
 *
 * <p>一个 check 器可服务多条规则（如三条参数规则共用 {@code sql.required_set}），
 * 差异全部由 YAML 的 {@code check} 参数表达 —— 这样规则数据可以独立演进，
 * 而"怎么判"不需要重复实现。
 *
 * @since 2026/11/02
 */
public interface RuleCheck {

    /** 对应的 {@code check.kind}，与 YAML 严格一致 */
    String kind();

    /**
     * 执行判定。
     *
     * <p><b>约定</b>：无法判定时必须返回 {@code UNKNOWN} 而不是 {@code PASS}
     * （接口超时、输入缺失都属此类）。
     */
    RuleOutcome check(Rule rule, RuleContext context);
}
