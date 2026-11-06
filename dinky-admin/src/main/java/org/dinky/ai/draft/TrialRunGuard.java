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

package org.dinky.ai.draft;

import org.dinky.ai.rule.RuntimeGateway;
import org.dinky.data.model.SystemConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 试跑节流（M1-E）：slot 下限 + 同用户频次 + 并发。
 *
 * <p><b>为什么必须限流</b>：B1~B4 常驻占 9~11 / 24 slots，AI 试跑若不受限，
 * 会把生产作业挤下线 —— 而"被挤下线"在 Flink 侧表现为作业 FAILED，归因成本极高。
 *
 * <p><b>fail-safe</b>：拿不到 freeSlots 时<b>拒绝</b>试跑（宁可不试跑，也不冒挤掉生产作业的风险），
 * 与规则引擎的 "unknown ≠ pass" 同一口径。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrialRunGuard {

    /** 同用户两次试跑的最小间隔（毫秒） */
    private static final long INTERVAL_MS = 5 * 60 * 1000L;

    private final RuntimeGateway runtimeGateway;

    private final Map<Integer, Long> lastTrialAt = new ConcurrentHashMap<>();

    private final Map<Integer, Integer> runningTrials = new ConcurrentHashMap<>();

    /** 判定结果：allowed=false 时 reason 给出人话，前端直接展示 */
    public Result check(Integer userId) {
        int floor = SystemConfiguration.getInstances().getLlmTrialSlotsFloor();
        Integer freeSlots = runtimeGateway.freeSlots();
        if (freeSlots == null) {
            return Result.reject("无法获取 Flink 空闲 slot，已拒绝试跑（避免挤掉生产作业）");
        }
        if (freeSlots < floor) {
            return Result.reject("空闲 slot 不足（当前 " + freeSlots + "，下限 " + floor + "），已拒绝试跑");
        }
        if (userId != null) {
            long now = System.currentTimeMillis();
            Long last = lastTrialAt.get(userId);
            if (last != null && now - last < INTERVAL_MS) {
                long remain = (INTERVAL_MS - (now - last)) / 1000;
                return Result.reject("同一用户 5 分钟内只能试跑 1 次，请 " + remain + " 秒后重试");
            }
            if (runningTrials.getOrDefault(userId, 0) > 0) {
                return Result.reject("你还有 1 个 AI 发起的作业在运行，请先停止后再试");
            }
        }
        return Result.allow(freeSlots);
    }

    /** 试跑开始时调用（占位） */
    public void onStart(Integer userId) {
        if (userId != null) {
            runningTrials.merge(userId, 1, Integer::sum);
            lastTrialAt.put(userId, System.currentTimeMillis());
        }
    }

    /** 试跑结束/失败时调用（释放占位） */
    public void onFinish(Integer userId) {
        if (userId != null) {
            runningTrials.computeIfPresent(userId, (key, value) -> value > 1 ? value - 1 : null);
        }
    }

    /** 节流判定结果 */
    public static class Result {

        private final boolean allowed;

        private final String reason;

        private final Integer freeSlots;

        private Result(boolean allowed, String reason, Integer freeSlots) {
            this.allowed = allowed;
            this.reason = reason;
            this.freeSlots = freeSlots;
        }

        public static Result allow(Integer freeSlots) {
            return new Result(true, null, freeSlots);
        }

        public static Result reject(String reason) {
            return new Result(false, reason, null);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }

        public Integer getFreeSlots() {
            return freeSlots;
        }
    }
}
