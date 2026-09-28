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

package org.dinky.ai;

import org.dinky.data.model.SystemConfiguration;

import cn.hutool.json.JSONObject;

/**
 * AI Chat 只读工具接口。
 *
 * <p>刻意保持极小：只有「声明」与「执行」两件事。将来若升级为 Java 17 并引入
 * LangChain4j / Spring AI，只需让工具类额外实现框架侧的 {@code @Tool} 契约，
 * 循环层整体可替换（调研报告 §6.3 的替换点在此）。
 *
 * <p><b>安全约定</b>：{@link #execute} 的实现<b>不得</b>把 {@link AiToolContext} 中的数据源信息
 * 写进返回值；返回值会被回灌给大模型，只能包含元数据或约定的样例数据。
 *
 * @since 2026/09/28
 */
public interface AiTool {

    /**
     * 稳定唯一的工具名。
     *
     * <p>模型在 {@code tool_calls} 里用它指定要调用的工具，因此<b>不可随意变更</b>——改名等价于
     * 对外 API 变更；确需调整应保留旧名一段时间。
     */
    String name();

    /** 工具声明（发给大模型的 tools 项） */
    AiToolSpec spec();

    /**
     * 是否启用（由配置开关控制触碰敏感能力的工具）。
     *
     * <p>默认启用；触碰业务数据行的工具覆盖为「读配置开关」。
     */
    default boolean isEnabled(SystemConfiguration config) {
        return true;
    }

    /**
     * 执行工具。
     *
     * @param args 模型给出的参数 JSON
     * @param context 执行上下文（含已解析的数据源，不含任何需要回灌给模型的内容）
     * @return 执行结果；失败时给出<b>脱敏后</b>的失败原因
     */
    AiToolResult execute(JSONObject args, AiToolContext context);
}
