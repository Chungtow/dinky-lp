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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * AI Chat 运行态注册表（阶段 2c-0）。
 *
 * <p>承载「执行前二次确认」与「服务端中断」所需的<b>跨请求状态</b>：一次对话（SSE 流）在服务端生成
 * 唯一 {@code runId}，确认与取消两个独立 HTTP 请求凭 {@code runId} 找到对应运行并投递结果。
 *
 * <p><b>安全</b>：所有操作校验 {@code userId} 与创建者一致，避免他人凭 runId 越权确认/取消。
 *
 * <p><b>生命周期</b>：由 {@code doChat} 在 finally 中 {@link #remove(String)}；不依赖 GC，
 * 避免长期驻留。
 *
 * @since 2026/10/02
 */
@Slf4j
@Component
public class AiChatRunRegistry {

    /** 单次运行的上下文 */
    @Getter
    public static class RunContext {
        /** 发起用户（鉴权用） */
        private final Integer userId;
        /** 待确认结果的投递队列（容量 1）：true=确认执行；false=拒绝 */
        private final BlockingQueue<Boolean> confirmQueue = new LinkedBlockingQueue<>(1);
        /** 是否已被请求取消（volatile，供循环线程读取） */
        private volatile boolean cancelled = false;

        RunContext(Integer userId) {
            this.userId = userId;
        }

        /** 等待一个确认结果；返回 null 表示超时未确认 */
        public Boolean awaitConfirm(long timeoutSeconds) {
            try {
                return confirmQueue.poll(Math.max(timeoutSeconds, 1), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** 投递确认结果（重复投递由队列容量保证只接受首个） */
        boolean offer(Boolean approve) {
            return confirmQueue.offer(approve);
        }
    }

    private final Map<String, RunContext> runs = new ConcurrentHashMap<>();

    /** 创建一次运行的上下文 */
    public RunContext create(String runId, Integer userId) {
        RunContext context = new RunContext(userId);
        runs.put(runId, context);
        return context;
    }

    /** 取运行上下文；不存在返回 null */
    public RunContext get(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    /** 结束运行并清理 */
    public void remove(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }

    /**
     * 投递「确认 / 拒绝」结果。
     *
     * @return 是否成功投递（false：runId 不存在、用户不匹配、或已投递过）
     */
    public boolean submitConfirm(String runId, Integer userId, boolean approve) {
        RunContext context = get(runId);
        if (context == null || !Objects.equals(context.getUserId(), userId)) {
            return false;
        }
        boolean accepted = context.offer(approve);
        if (!accepted) {
            log.debug("Duplicate confirm ignored, runId: {}", runId);
        }
        return accepted;
    }

    /**
     * 请求取消运行。
     *
     * @return 是否成功置位（false：runId 不存在或用户不匹配）
     */
    public boolean cancel(String runId, Integer userId) {
        RunContext context = get(runId);
        if (context == null || !Objects.equals(context.getUserId(), userId)) {
            return false;
        }
        context.cancelled = true;
        // 若正卡在「等待确认」上，投递拒绝让其尽快退出（拒绝 = 不执行）
        context.offer(Boolean.FALSE);
        return true;
    }

    /** 是否已被取消 */
    public boolean isCancelled(RunContext context) {
        return context != null && context.cancelled;
    }
}
