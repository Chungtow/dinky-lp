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

import org.dinky.ai.tools.ExecSqlTool;
import org.dinky.data.dto.AiChatMessage;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 自研的工具循环（"思考 → 调工具 → 观察 → 再思考"）。
 *
 * <p>与阶段 0 已有的「生成 → 校验 → 报错回灌 → 再生成」修复重试循环结构同构，差别在于这里的
 * "观察"来自模型主动索取的工具，而不是固定的 SQL 校验器。
 *
 * <p><b>为什么自研</b>：dinky 运行在 Java 8，Spring AI / LangChain4j / MCP SDK 均要求 JDK 17+，
 * 详见《AI Chat 执行层选型调研报告》§3.1。自研的代价是下面的守卫要自己写——框架默认都提供
 * （调研报告 §7.3）。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolLoop {

    /**
     * 「本轮/累计工具调用上限」已改为可配置项（20261025）。
     *
     * <p>原为两个硬编码常量（单轮 6 / 累计 40），大规模调研场景下一次问十来张表就会触顶；
     * 更严重的是旧实现<b>只执行前 N 个却把全量 tool_calls 写回 assistant 消息</b>，造成
     * 「N 个调用、M 条 tool 响应」违反协议 → 服务端 400 → 异常被吞 → 静默空回复。
     * 现读取 {@code sys.llm.settings.toolCallMaxPerRound} / {@code ...toolCallMaxTotal}，
     * 且未执行的调用一律补占位响应（见 {@link #SKIPPED_TOOL_NOTICE}）。
     */

    /**
     * 未执行调用的占位响应文案。
     *
     * <p><b>协议要求</b>：assistant 消息中的每个 {@code tool_calls.id} 都必须在随后出现一条
     * 对应的 {@code role=tool} 消息，否则 OpenAI 兼容服务端直接 400。因此"因上限未执行"不能
     * 静默丢弃，必须回一条可读的 tool 响应——顺便让模型知道下一步该怎么做。
     */
    private static final String SKIPPED_TOOL_NOTICE =
            "（本轮未执行：已达到工具调用上限，本轮未获取到该对象的任何信息。" + "如需继续，请重新请求本工具，或改用 describe_tables 一次获取多张表结构）";

    /** 单轮失败的重试次数（瞬时 5xx / 网络抖动可自愈；400 这类确定性错误重试也无意义，由内容为空判定） */
    private static final int LLM_ROUND_MAX_ATTEMPTS = 2;
    /** 审计明细里参数与错误的字符上限 */
    private static final int LOG_TEXT_CHARS = 200;

    /** 阶段 2c-2：执行类工具（exec_sql）审计参数（语句原文）的字符上限——要能还原"执行了什么" */
    private static final int SQL_ARGS_LOG_CHARS = 2000;

    private static final String ROUND_LIMIT_NOTICE = "工具调用已达上限，请基于已经获取到的信息直接回答；" + "如果仍然缺少必要信息，请明确说明缺少什么，不要再尝试调用工具。";
    /** 阶段 2c-0：收到服务端中断请求时随正文下发的提示 */
    private static final String CANCELLED_NOTICE = "\n\n> 已中断本次运行。\n";

    /** 阶段 2c-2：自动纠错达到上限后追加的停止提示（回灌给模型，要求收尾而非继续重试） */
    private static final String REPAIR_EXHAUSTED_NOTICE =
            "\n\n> 自动纠错已达上限，已停止重试。" + "请基于已有信息向用户说明失败原因与下一步建议，**不要**再次尝试执行相同的语句。\n";

    private final LlmClient llmClient;
    private final AiToolRegistry registry;
    private final AiToolExecutor executor;

    /**
     * 运行工具循环，直到模型不再请求工具或达到守卫上限。
     *
     * <p><b>超限不抛异常</b>：把"已达上限"回灌给模型让它继续作答。直接中断会留下没有答案的空白回复
     * （调研报告 §7.3）。
     *
     * @param messages 会被<b>就地追加</b>工具轮次消息（调用方后续还要用它做修复重试）
     * @param context 工具上下文（含已解析的数据源）
     * @param onDelta 正文片段回调（实时推 SSE）
     * @param onReasoning 思考过程回调（实时推 SSE）
     * @param listener 工具过程回调（推 toolCall / toolResult 帧）
     */
    public AiToolRunResult run(
            List<AiChatMessage> messages,
            AiToolContext context,
            LlmProfile profile,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            Listener listener,
            BooleanSupplier cancelled) {
        AiToolRunResult result = new AiToolRunResult();
        SystemConfiguration config = SystemConfiguration.getInstances();
        int maxRounds = Math.max(config.getLlmToolCallMaxRounds(), 1);
        int maxPerRound = Math.max(config.getLlmToolCallMaxPerRound(), 1);
        int maxTotal = Math.max(config.getLlmToolCallMaxTotal(), 1);
        // 工具轮的 thinking 默认关闭：探针实测它吃掉约 75% 生成预算（调研报告 §7.9）
        Boolean thinkingEnabled = Boolean.valueOf(config.isLlmToolThinkingEnabled());
        // 阶段 3：该实例声明「不支持 function calling」时禁用工具循环、退化为纯问答——
        // 现状对"模型不支持 tools"没有任何兜底（HTTP 400 会被统一转成异常、单轮异常又会被吞掉），
        // 若不显式降级，工具链会静默失效（探针场景：本地 Ollama 等小模型）。
        List<AiToolSpec> specs = profile != null && !profile.isSupportsTools()
                ? Collections.<AiToolSpec>emptyList()
                // 阶段 4b 修复（UAT B4）：未绑定数据源时只下发不依赖数据源的工具。
                // 否则模型会先试 list_tables（必然失败）而白白耗尽工具调用额度，
                // 导致真正要做的 skill 写入排不上号、只能让用户手工粘贴。
                : registry.specs(config, context != null && context.getDataBase() != null);

        if (specs.isEmpty()) {
            // 无工具可用：退化为普通一轮生成，行为与阶段 1a 完全一致
            result.appendAnswer(finalRound(messages, profile, onDelta, onReasoning, thinkingEnabled, result));
            return result;
        }

        for (int round = 1; round <= maxRounds; round++) {
            // 阶段 2c-0：服务端中断——每轮开始前检查取消标记，命中即停止并给出提示
            if (cancelled != null && cancelled.getAsBoolean()) {
                result.appendAnswer(CANCELLED_NOTICE);
                return result;
            }
            // 20261025：累计预算已耗尽时不再发起工具轮（省一次请求），直接转入收尾生成
            if (result.getToolCallCount() >= maxTotal) {
                break;
            }
            StringBuilder content = new StringBuilder();
            AiChatRound chatRound =
                    callLlm(messages, specs, profile, content, onDelta, onReasoning, thinkingEnabled, result);
            if (!chatRound.hasToolCalls()) {
                result.appendAnswer(content.toString());
                return result;
            }
            // 🔴 探针约束 1：回灌的 assistant 消息必须携带 reasoning_content
            // 20261025：这里必须回灌【全量】tool_calls（模型的原始请求），未执行的靠占位响应对齐，
            // 否则会出现「N 个调用、M 条响应」→ 服务端 400（2026-10-24 事故根因）。
            List<AiToolCall> allCalls = chatRound.getToolCalls();
            messages.add(AiChatMessage.assistantToolCalls(
                    chatRound.getContent(), chatRound.getReasoningContent(), allCalls));
            result.appendAnswer(content.toString());

            // 本轮真正执行几个：单轮上限 ∩ 累计预算剩余
            int executableCount =
                    planExecutableCount(allCalls.size(), maxPerRound, maxTotal, result.getToolCallCount());
            executeToolCalls(allCalls, executableCount, config, context, messages, result, listener);
            // 累计预算耗尽 → 收尾（此时本轮未执行的调用已补占位响应，协议仍然完整）
            if (result.getToolCallCount() >= maxTotal) {
                break;
            }
            if (listener != null) {
                listener.onRoundCompleted(round, maxRounds);
            }
        }
        // 走完所有轮次仍在要工具：回灌提示后，再给一次「不带工具」的机会生成最终答案
        messages.add(AiChatMessage.of("user", ROUND_LIMIT_NOTICE));
        result.appendAnswer(finalRound(messages, profile, onDelta, onReasoning, thinkingEnabled, result));
        return result;
    }

    /**
     * 顺序执行一轮里的工具调用。
     *
     * <p>探针已证实模型会一次返回多个 tool_calls，这里<b>串行</b>执行：1b 的工具都是毫秒级的元数据
     * 查询，并行收益不大，串行换来确定性顺序与可读日志。
     *
     * @param executableCount 本轮真正执行的个数（前缀）；其余一律补占位 tool 响应
     */
    private void executeToolCalls(
            List<AiToolCall> calls,
            int executableCount,
            SystemConfiguration config,
            AiToolContext context,
            List<AiChatMessage> messages,
            AiToolRunResult result,
            Listener listener) {
        int limit = Math.max(0, Math.min(executableCount, calls.size()));
        for (int i = 0; i < calls.size(); i++) {
            AiToolCall call = calls.get(i);
            if (i >= limit) {
                // 🔴 协议不变量：每个 tool_calls.id 都必须有对应的 tool 消息，
                // 因此"没执行"也必须回一条占位（否则下一轮必 400）。
                messages.add(AiChatMessage.toolResult(call.getId(), call.getName(), SKIPPED_TOOL_NOTICE));
                log.warn("Tool call skipped by limit, tool: {}, id: {}", call.getName(), call.getId());
                continue;
            }
            if (listener != null) {
                listener.onToolCall(call);
            }
            AiTool tool = registry.find(call.getName());
            AiToolResult toolResult;
            if (tool == null || !tool.isEnabled(config)) {
                toolResult = AiToolResult.failure("工具 " + call.getName() + " 不存在或当前不可用，请直接基于已有信息回答", 0L);
            } else {
                toolResult = executor.execute(tool, call, context);
            }
            result.setToolCallCount(result.getToolCallCount() + 1);
            if (!toolResult.isSuccess()) {
                result.setToolFailed(true);
            }
            // 阶段 2c-2：汇总「已走过写路径」与「最近一次尝试的语句」，
            // 供 doChat 做闭环收敛（避免同一写语句被执行两次）与审计落语句原文
            if (toolResult.isWriteAttempted()) {
                result.setWriteAttempted(true);
            }
            if (StrUtil.isNotBlank(toolResult.getSqlText())) {
                result.setLastExecutedSql(toolResult.getSqlText());
            }
            result.getToolCallLogs().add(toLogEntry(call, toolResult));
            if (listener != null) {
                listener.onToolResult(call, toolResult);
            }
            messages.add(AiChatMessage.toolResult(
                    call.getId(),
                    call.getName(),
                    toolResult.isSuccess()
                            ? StrUtil.nullToEmpty(toolResult.getContent())
                            : buildToolFailureText(toolResult, context)));
        }
    }

    /** 最后一次「不带工具」的生成：保证循环结束时一定有一段正文 */
    private String finalRound(
            List<AiChatMessage> messages,
            LlmProfile profile,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            Boolean thinkingEnabled,
            AiToolRunResult result) {
        StringBuilder content = new StringBuilder();
        callLlm(messages, null, profile, content, onDelta, onReasoning, thinkingEnabled, result);
        return content.toString();
    }

    private AiChatRound callLlm(
            List<AiChatMessage> messages,
            List<AiToolSpec> specs,
            LlmProfile profile,
            StringBuilder content,
            Consumer<String> onDelta,
            Consumer<String> onReasoning,
            Boolean thinkingEnabled,
            AiToolRunResult result) {
        // 20261025：发请求前校验协议不变量（assistant.tool_calls 与 tool 消息一一对应），
        // 任何上游路径留下的 dangling tool_calls 都会在这里被补齐/清理，而不是变成必然的 HTTP 400。
        assertToolProtocol(messages);
        Exception last = null;
        for (int attempt = 1; attempt <= LLM_ROUND_MAX_ATTEMPTS; attempt++) {
            try {
                AiChatRound chatRound = llmClient.streamChatWithTools(
                        messages,
                        specs,
                        profile,
                        delta -> {
                            content.append(delta);
                            if (onDelta != null) {
                                onDelta.accept(delta);
                            }
                        },
                        onReasoning,
                        thinkingEnabled);
                if (chatRound != null && chatRound.getUsage() != null) {
                    TokenUsage usage = result.getUsage();
                    usage.setPromptTokens(
                            usage.getPromptTokens() + chatRound.getUsage().getPromptTokens());
                    usage.setCompletionTokens(
                            usage.getCompletionTokens() + chatRound.getUsage().getCompletionTokens());
                }
                return chatRound;
            } catch (Exception e) {
                last = e;
                log.warn(
                        "Tool loop LLM round failed (attempt {}/{}): {}",
                        attempt,
                        LLM_ROUND_MAX_ATTEMPTS,
                        e.getMessage());
                // 已经向 SSE 推过正文时不再重试：重试会把同一段正文重复推给用户
                if (content.length() > 0) {
                    break;
                }
            }
        }
        // 20261025：不再把失败吞成 null（那会让整个循环静默返回空正文）。
        // 标记到结果里供审计，并抛给 doChat —— 由它统一下发 error 帧。
        String reason = last == null ? "未知原因" : StrUtil.nullToEmpty(last.getMessage());
        result.setAborted(true);
        result.setFailureReason(reason);
        throw new BusException("工具轮请求失败：" + reason);
    }

    /**
     * 本轮真正执行的工具调用个数。
     *
     * <p>取三层上限的较小值：模型本轮请求数、单轮上限、累计预算剩余。返回 0 表示本轮一个都不执行
     * （仍需为每个调用补占位响应，见 {@link #SKIPPED_TOOL_NOTICE}）。
     */
    private int planExecutableCount(int requested, int maxPerRound, int maxTotal, int executedSoFar) {
        int remaining = maxTotal - executedSoFar;
        if (remaining <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(requested, Math.min(maxPerRound, remaining)));
    }

    /**
     * 协议不变量自检：{@code assistant.tool_calls} 的 id 全集必须与紧随其后的 tool 消息一一对应。
     *
     * <p>两类修复：
     *
     * <ul>
     *   <li><b>缺失</b>：补一条占位 tool 消息（内容说明未执行），插在该工具块末尾；
     *   <li><b>孤儿</b>：找不到宿主 assistant 的 tool 消息直接移除（部分服务端会因此报错）。
     * </ul>
     *
     * <p>正常路径不该命中；命中即说明新增了会产生 dangling tool_calls 的代码路径，需一并整改。
     */
    private void assertToolProtocol(List<AiChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            AiChatMessage message = messages.get(i);
            if (message == null || !message.hasToolCalls()) {
                continue;
            }
            Set<String> answered = new LinkedHashSet<>();
            int cursor = i + 1;
            while (cursor < messages.size()
                    && messages.get(cursor) != null
                    && "tool".equals(messages.get(cursor).getRole())) {
                String repliedId = messages.get(cursor).getToolCallId();
                if (StrUtil.isNotBlank(repliedId)) {
                    answered.add(repliedId);
                }
                cursor++;
            }
            List<AiChatMessage> patch = new ArrayList<>();
            for (AiToolCall call : message.getToolCalls()) {
                if (call == null || StrUtil.isBlank(call.getId()) || answered.contains(call.getId())) {
                    continue;
                }
                patch.add(AiChatMessage.toolResult(call.getId(), call.getName(), SKIPPED_TOOL_NOTICE));
            }
            if (!patch.isEmpty()) {
                log.warn(
                        "Tool protocol repaired: {} dangling tool_calls at message #{}, patched with placeholder",
                        patch.size(),
                        i);
                try {
                    messages.addAll(cursor, patch);
                    i = cursor + patch.size() - 1;
                } catch (Exception e) {
                    // 只读列表（如评测脚本传入的固定长度集合）：自检降级为"仅告警"，不阻断请求
                    log.warn("Tool protocol patch skipped (messages not mutable): {}", e.getMessage());
                }
            }
        }
        // 孤儿 tool 消息：向上回溯最近的 assistant，若其 tool_calls 不含该 id 则移除
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessage message = messages.get(i);
            if (message == null || !"tool".equals(message.getRole())) {
                continue;
            }
            int owner = i - 1;
            while (owner >= 0
                    && messages.get(owner) != null
                    && "tool".equals(messages.get(owner).getRole())) {
                owner--;
            }
            boolean valid = false;
            if (owner >= 0 && messages.get(owner) != null && messages.get(owner).hasToolCalls()) {
                for (AiToolCall call : messages.get(owner).getToolCalls()) {
                    if (call != null && StrUtil.equals(call.getId(), message.getToolCallId())) {
                        valid = true;
                        break;
                    }
                }
            }
            if (!valid) {
                log.warn("Tool protocol repaired: drop orphan tool message #{} (id={})", i, message.getToolCallId());
                try {
                    messages.remove(i);
                } catch (Exception e) {
                    log.warn("Tool protocol drop skipped (messages not mutable): {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 工具失败的回灌文本（阶段 2c-2：自动纠错闭环）。
     *
     * <p>在「调用失败：&lt;脱敏错误&gt;」之后追加纠错指引（{@link PromptStore#TOOL_REPAIR}，含「第 N/M 次」），
     * 引导模型改稿后再次调用工具；到达上限时改为追加停止提示，避免无脑重试。
     */
    private String buildToolFailureText(AiToolResult toolResult, AiToolContext context) {
        StringBuilder sb = new StringBuilder("调用失败：");
        sb.append(StrUtil.nullToEmpty(toolResult.getErrorMessage()));
        if (toolResult.isRepairExhausted()) {
            sb.append(REPAIR_EXHAUSTED_NOTICE);
            return sb.toString();
        }
        if (toolResult.getAttempt() > 0) {
            Map<String, String> params = new HashMap<>(2);
            params.put(PromptStore.PLACEHOLDER_ATTEMPT, String.valueOf(toolResult.getAttempt()));
            params.put(
                    PromptStore.PLACEHOLDER_MAX_ATTEMPTS,
                    String.valueOf(Math.max(context == null ? 0 : context.getMaxRepairAttempts(), 1)));
            sb.append(PromptStore.render(PromptStore.TOOL_REPAIR, params));
        }
        return sb.toString();
    }

    /** 审计明细：只记工具名 / 参数摘要 / 成败 / 耗时，不记完整返回值 */
    private JSONObject toLogEntry(AiToolCall call, AiToolResult toolResult) {
        JSONObject entry = new JSONObject();
        entry.set("tool", call.getName());
        // 阶段 2c-2：执行类工具的语句原文放宽截断（审计要能还原"到底执行了什么"）
        int argsLimit = ExecSqlTool.NAME.equals(call.getName()) ? SQL_ARGS_LOG_CHARS : LOG_TEXT_CHARS;
        entry.set("args", StrUtil.sub(StrUtil.nullToEmpty(call.getArguments()), 0, argsLimit));
        entry.set("success", toolResult.isSuccess());
        entry.set("costMs", toolResult.getCostMs());
        // 阶段 2c-1：写类工具的风险摘要与受影响行数入审计（只读工具无此值，不记录）
        if (StrUtil.isNotBlank(toolResult.getRiskSummary())) {
            entry.set("risk", StrUtil.sub(toolResult.getRiskSummary(), 0, LOG_TEXT_CHARS));
        }
        if (toolResult.getAffectedRows() != null) {
            entry.set("affectedRows", toolResult.getAffectedRows());
        }
        if (!toolResult.isSuccess()) {
            entry.set("error", StrUtil.sub(StrUtil.nullToEmpty(toolResult.getErrorMessage()), 0, LOG_TEXT_CHARS));
        }
        return entry;
    }

    /** 工具过程回调：向前端推送进展，同时是 SseEmitter 心跳的挂载点 */
    public interface Listener {

        /** 模型决定调用某个工具 */
        void onToolCall(AiToolCall call);

        /** 工具执行结束（成功或失败） */
        void onToolResult(AiToolCall call, AiToolResult result);

        /** 一轮工具执行完毕：用于心跳保活与轮次展示 */
        void onRoundCompleted(int round, int maxRounds);
    }
}
