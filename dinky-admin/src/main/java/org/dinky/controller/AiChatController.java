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

import org.dinky.ai.context.NameRegistryService;
import org.dinky.data.dto.AiChatConfirmRequest;
import org.dinky.data.dto.AiChatRequest;
import org.dinky.data.dto.AiChatWriteAuditRequest;
import org.dinky.data.result.Result;
import org.dinky.data.vo.AiChatConfig;
import org.dinky.service.AiChatService;

import java.util.List;

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

    /** P1-A：{@code @topic/} 候选来自命名名册（零连接 Kafka） */
    private final NameRegistryService nameRegistryService;

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
     * 查询 {@code @topic/} 的候选列表（P1-A · 名册版）。
     *
     * <p><b>为什么需要这个接口</b>：前端「{@code @} 候选浮层」只能拿到它已知的数据（当前面板数据源的库表、
     * 已打开的作业、skill 资产…），而 topic 清单只存在于服务端的名册里。不给接口就只能靠手打，
     * 用户体验上就是“输入 {@code @topic/} 什么都不出”。
     *
     * <p><b>为什么还是零连接</b>：取值全部来自命名名册（{@code dinky_task} 里本租户
     * {@code FlinkSql}/{@code FlinkSqlEnv} 作业的 DDL），不提交任何 Kafka 请求。因此候选只覆盖
     * <b>已被作业使用</b>的 topic；“平台里存在但无任何作业消费”的 topic 需等批次 2 的
     * AdminClient（{@code list_topics}）。
     *
     * <p>这里故意<b>不做</b> {@code mentionSourceEnable} 开关门控：该开关的口径是“{@code @source/} +
     * {@code describe_source} / {@code check_name_conflict}”，而 {@code @topic/} 在批次 1 一直可用（同其渲染器）。
     *
     * @return 已占用（即已被作业引用）的 topic 名清单（本租户）
     */
    @GetMapping("/mentionTopics")
    @ApiOperation("List topic candidates for @topic mentions (from name registry, no Kafka connection)")
    public Result<List<String>> listMentionTopics() {
        return Result.succeed(nameRegistryService.getSnapshot().get("kafkaTopic"));
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
