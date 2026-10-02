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

import org.dinky.data.dto.AiChatConfirmRequest;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.dto.AiChatWriteAuditRequest;
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
import cn.dev33.satoken.stp.StpUtil;
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
        if (request != null) {
            try {
                // 同步线程中取登录用户并覆盖，避免前端伪造 userId 绕过限流/配额
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI chat failed: {}", e.getMessage());
            }
        }
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

    /**
     * 记录 Craft 写入审计（阶段 2：T2-5）。
     *
     * <p><b>只记录，不写作业</b>：写入动作已在前端完成，这里补的是"谁在什么时候让 AI 改了哪个作业"，
     * 是放开写能力后唯一的事后追溯手段。userId 由服务端覆盖，不接受前端传入。
     *
     * @param request {@link AiChatWriteAuditRequest}
     * @return {@link Result}
     */
    @PostMapping("/write-audit")
    @ApiOperation("Record AI Craft Write Audit")
    public Result<Void> writeAudit(@RequestBody AiChatWriteAuditRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI craft audit failed: {}", e.getMessage());
            }
        }
        aiChatService.recordCraftWrite(request);
        return Result.succeed();
    }

    /**
     * 二次确认：写语句（DML / DDL）是否执行（阶段 2c-0）。
     *
     * <p>AI 生成写语句时后端<b>挂起等待</b>，前端弹确认框后调用本端点投递结果；未确认不执行。
     * userId 由服务端覆盖，且仅创建该 runId 的用户可确认。
     *
     * @param request {@link AiChatConfirmRequest}
     * @return 投递是否成功
     */
    @PostMapping("/confirm")
    @ApiOperation("Confirm AI Write Execution")
    public Result<Void> confirm(@RequestBody AiChatConfirmRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI confirm failed: {}", e.getMessage());
            }
        }
        boolean accepted = aiChatService.confirmRun(request);
        return accepted ? Result.succeed() : Result.failed("确认请求已失效（运行已结束或无权限）");
    }

    /**
     * 中断本次运行（阶段 2c-0）。
     *
     * <p>与前端「停止」按钮配合：不再只是断开 SSE HTTP 流，而是让服务端工具循环/正在等待的
     * 确认真正收到取消信号。
     *
     * @param request {@link AiChatConfirmRequest}（只用 runId）
     * @return 取消是否被接受
     */
    @PostMapping("/cancel")
    @ApiOperation("Cancel AI Chat Run")
    public Result<Void> cancel(@RequestBody AiChatConfirmRequest request) {
        if (request != null) {
            try {
                request.setUserId(StpUtil.getLoginIdAsInt());
            } catch (Exception e) {
                log.warn("Resolve current user for AI cancel failed: {}", e.getMessage());
            }
        }
        boolean accepted = aiChatService.cancelRun(request);
        return accepted ? Result.succeed() : Result.failed("取消失败（运行已结束或无权限）");
    }
}
