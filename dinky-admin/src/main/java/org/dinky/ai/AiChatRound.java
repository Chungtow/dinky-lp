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

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一轮大模型调用的完整产出。
 *
 * <p>相比只回 usage，这里额外保留 {@link #content} 与 {@link #reasoningContent}：工具循环需要把
 * 它们连同 {@code tool_calls} 一起回灌给模型，否则下一轮会 400（探针实测，调研报告 §7.7）。
 *
 * @since 2026/09/28
 */
@Data
public class AiChatRound {

    private TokenUsage usage = new TokenUsage();

    /** 本轮累积的正文（流式 delta 拼接结果） */
    private String content = "";

    /** 本轮累积的思考过程（回灌必需） */
    private String reasoningContent = "";

    /** 本轮模型要求调用的工具（已按 index 拼装完整参数） */
    private List<AiToolCall> toolCalls = new ArrayList<>();

    /** 服务端返回的结束原因：stop / tool_calls / length / ... */
    private String finishReason;

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
