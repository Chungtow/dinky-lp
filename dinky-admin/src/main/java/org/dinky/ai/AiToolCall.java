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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型发起的一次工具调用请求。
 *
 * <p>流式响应下 {@code arguments} 是<b>逐片下发</b>的字符串，必须由本轮最后一块
 * （{@code finish_reason=tool_calls}）之前的所有分片按 {@code index} 累积拼成完整 JSON，
 * 直接取首帧会拿到不完整的参数（2026-09-28 探针实测，见调研报告 §7.8）。
 *
 * @since 2026/09/28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiToolCall {

    /** 调用 id，回灌 tool 结果消息时用于配对 */
    private String id;

    /** 工具名 */
    private String name;

    /** 参数 JSON 字符串（累积后的完整内容） */
    private String arguments;
}
