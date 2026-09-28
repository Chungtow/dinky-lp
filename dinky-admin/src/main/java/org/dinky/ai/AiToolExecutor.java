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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行器：超时控制 + 字符截断 + 异常脱敏。
 *
 * <p>框架（LangChain4j 等）默认提供这三项，自研 loop 必须自己补
 * （调研报告 §7.3）——缺任何一个都可能把整个对话拖死或泄露信息。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
public class AiToolExecutor {

    /** 默认超时（秒）：配置项不可用时的兜底 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    /** 工具返回给模型的字符上限（防止单次结果把上下文撑爆） */
    private static final int DEFAULT_MAX_RESULT_CHARS = 8000;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 执行一次工具调用。
     *
     * @return 永不返回 null；失败时 {@link AiToolResult#isSuccess()} 为 false
     */
    public AiToolResult execute(AiTool tool, AiToolCall call, AiToolContext context) {
        long start = System.currentTimeMillis();
        int timeoutSeconds = context != null && context.getTimeoutSeconds() > 0
                ? context.getTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS;
        Future<AiToolResult> future = executor.submit(() -> invoke(tool, call, context));
        try {
            AiToolResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return result == null ? AiToolResult.failure("工具未返回结果", System.currentTimeMillis() - start) : result;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("AI tool timed out, tool: {}, arguments: {}", call.getName(), summarizeArgs(call.getArguments()));
            return AiToolResult.failure("工具执行超时（超过 " + timeoutSeconds + " 秒），已中断", System.currentTimeMillis() - start);
        } catch (Exception e) {
            future.cancel(true);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 完整异常只进服务端日志；给模型的是脱敏文案
            log.warn(
                    "AI tool failed, tool: {}, arguments: {}",
                    call.getName(),
                    summarizeArgs(call.getArguments()),
                    cause);
            return AiToolResult.failure("工具执行失败：" + sanitize(cause.getMessage()), System.currentTimeMillis() - start);
        }
    }

    private AiToolResult invoke(AiTool tool, AiToolCall call, AiToolContext context) {
        JSONObject args;
        try {
            args = StrUtil.isBlank(call.getArguments()) ? new JSONObject() : JSONUtil.parseObj(call.getArguments());
        } catch (Exception e) {
            return AiToolResult.failure("工具参数无法解析，请重新给出合法的 JSON 参数", 0L);
        }
        AiToolResult result = tool.execute(args, context);
        if (result == null) {
            return AiToolResult.failure("工具未返回结果", 0L);
        }
        // 结果过长时截断（告诉模型真相，避免它误以为拿到了完整清单）
        int maxChars = context != null && context.getMaxResultChars() > 0
                ? context.getMaxResultChars()
                : DEFAULT_MAX_RESULT_CHARS;
        String content = result.getContent();
        if (StrUtil.isNotBlank(content) && content.length() > maxChars) {
            result.setContent(content.substring(0, maxChars) + "\n... (结果过长，已截断)");
        }
        if (StrUtil.isNotBlank(result.getErrorMessage())) {
            result.setErrorMessage(sanitize(result.getErrorMessage()));
        }
        return result;
    }

    /**
     * 错误信息脱敏：抹掉连接串、账号口令、内网地址与堆栈行。
     *
     * <p>工具实现应自行给出可读的失败原因，这里只做<b>兜底</b>——因为异常文本里出现
     * JDBC URL 或内网 IP 是很常见的（调研报告 §7.2）。
     */
    static String sanitize(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "工具执行失败（无具体原因）";
        }
        String text = raw.trim();
        text = text.replaceAll("(?i)(jdbc:[a-zA-Z0-9:./@?=&_\\-]+)", "jdbc:<已脱敏>");
        text = text.replaceAll("(?i)((password|passwd|pwd)\\s*[=:]\\s*)[^\\s,;'\"\\)]+", "$1<已脱敏>");
        text = text.replaceAll("(?i)((user(name)?)\\s*[=:]\\s*)[^\\s,;'\"\\)]+", "$1<已脱敏>");
        text = text.replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "<已脱敏地址>");
        // 堆栈行不利于模型理解，且可能带出内部类名与路径
        text = text.replaceAll("(?m)^\\s*at\\s+.*$", "");
        text = text.replaceAll("\\s{2,}", " ").trim();
        return StrUtil.isBlank(text) ? "工具执行失败（详情见服务端日志）" : StrUtil.sub(text, 0, 500);
    }

    /** 日志里打印的参数摘要（避免把模型给出的长 JSON 全量落盘） */
    private String summarizeArgs(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return "";
        }
        return StrUtil.sub(arguments.replaceAll("\\s+", " "), 0, 200);
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }
}
