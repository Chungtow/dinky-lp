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

package org.dinky.service;

import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.vo.AiChatConfig;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI Chat 服务。
 *
 * <p>上下文只包含<strong>元数据</strong>（库/表/列/类型/注释/外键），绝不携带业务数据行。
 *
 * @since 2026/09/26
 */
public interface AiChatService {

    /**
     * 发起一次对话，以 SSE 流式返回大模型输出。
     *
     * @param request 对话请求（含上下文定位信息，仅元数据）
     * @return {@link SseEmitter}
     */
    SseEmitter chat(AiChatRequest request);

    /**
     * 获取前端可用的 AI 配置状态（不含密钥）。
     *
     * @return {@link AiChatConfig}
     */
    AiChatConfig getConfig();
}
