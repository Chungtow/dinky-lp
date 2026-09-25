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

package org.dinky.controller;

import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.result.Result;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI Chat Controller
 *
 * <p>提供数据开发场景下的 AI 对话能力（Text-to-SQL / Explain），以 SSE 流式返回。
 *
 * @since 2026/09/26
 */
@Slf4j
@RestController
@Api(tags = "AI Chat Controller")
@RequestMapping("/api/aiChat")
@RequiredArgsConstructor
@SaCheckLogin
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * 发起对话，流式返回（SSE）。
     *
     * @param request {@link AiChatRequest}
     * @return {@link SseEmitter}
     */
    @PostMapping("/chat")
    @ApiOperation("Chat With AI (SSE Stream)")
    public SseEmitter chat(@RequestBody AiChatRequest request) {
        return aiChatService.chat(request);
    }

    /**
     * 查询 AI 能力配置状态（不含密钥）。
     *
     * @return {@link Result}<{@link AiChatConfig}>
     */
    @GetMapping("/config")
    @ApiOperation("Query AI Chat Config")
    public Result<AiChatConfig> getConfig() {
        return Result.succeed(aiChatService.getConfig());
    }
}
