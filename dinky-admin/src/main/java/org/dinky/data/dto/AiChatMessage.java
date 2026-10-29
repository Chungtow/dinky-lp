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

package org.dinky.data.dto;

import org.dinky.ai.AiToolCall;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条 AI 对话消息。
 *
 * <p>支持 OpenAI 兼容协议的四种角色：system / user / assistant / tool。后两个字段
 * （{@link #reasoningContent}、{@link #toolCalls}）为阶段 1b「只读工具」引入。
 *
 * @since 2026/09/26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "AiChatMessage", description = "AI Chat Message")
public class AiChatMessage {

    @ApiModelProperty(value = "角色：system / user / assistant / tool", example = "user")
    private String role;

    @ApiModelProperty(value = "消息内容")
    private String content;

    /**
     * 思考过程原文。
     *
     * <p><b>回灌必填</b>：DeepSeek 系列在 thinking 模式下，凡是携带 {@code tool_calls} 的 assistant
     * 消息都<b>必须</b>同时携带该字段，否则服务端返回 400
     * {@code "The `reasoning_content` in the thinking mode must be passed back to the API."}
     * 传<b>空串</b>即可放行（2026-09-28 探针实测，见《AI Chat 执行层选型调研报告》§7.7）。
     */
    @ApiModelProperty(value = "思考过程；含 tool_calls 的 assistant 消息回灌时必须给出（空串亦可）")
    private String reasoningContent;

    @ApiModelProperty(value = "assistant 发起的工具调用列表")
    private List<AiToolCall> toolCalls;

    @ApiModelProperty(value = "role=tool 时的调用 id，用于与 assistant 的 tool_calls 对应")
    private String toolCallId;

    @ApiModelProperty(value = "role=tool 时的工具名")
    private String name;

    public static AiChatMessage of(String role, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /**
     * 构造一条「assistant 发起工具调用」的消息。
     *
     * <p>{@code toolCalls} 非空时 {@code reasoningContent} 会自动兜底为空串：缺该字段会让下一轮
     * 请求被服务端拒绝（400），宁可丢思维连贯性也要保证链路可用。
     */
    public static AiChatMessage assistantToolCalls(
            String content, String reasoningContent, List<AiToolCall> toolCalls) {
        AiChatMessage message = new AiChatMessage();
        message.setRole("assistant");
        message.setContent(content);
        message.setReasoningContent(reasoningContent == null ? "" : reasoningContent);
        message.setToolCalls(toolCalls);
        return message;
    }

    /** 构造一条「工具执行结果」的消息（role=tool） */
    public static AiChatMessage toolResult(String toolCallId, String name, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setRole("tool");
        message.setToolCallId(toolCallId);
        message.setName(name);
        message.setContent(content);
        return message;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
