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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * AI Chat 限流器（阶段 0.3）：按用户的滑动窗口计数。
 *
 * <p>dinky 当前<b>没有任何限流组件</b>（无 guava RateLimiter / sentinel / bucket4j 依赖），
 * 因此这里用 JDK 自带的并发容器做最小实现，不引入新依赖。
 *
 * <p>⚠️ <b>已知局限</b>：计数在 JVM 内存中，<b>仅在单实例部署下准确</b>。当前 dinky 为单机部署，
 * 满足需求；若将来多实例部署，需改为 Redis 计数。
 *
 * @since 2026/09/27
 */
@Slf4j
@Component
public class AiChatRateLimiter {

    private static final long WINDOW_MILLIS = 60 * 1000L;

    /** userId → 该用户在窗口内的请求时间戳队列 */
    private final Map<Integer, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 判断并占用一次配额。
     *
     * @param userId 当前用户 id
     * @param limitPerMinute 每分钟上限（0 或负数表示不限）
     * @return 允许时返回 null；超限时返回提示文案
     */
    public String tryAcquire(int userId, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> queue = windows.computeIfAbsent(userId, key -> new ConcurrentLinkedDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MILLIS) {
                queue.pollFirst();
            }
            if (queue.size() >= limitPerMinute) {
                log.warn("AI chat rate limit exceeded, userId: {}, limit: {}/min", userId, limitPerMinute);
                return "请求过于频繁：当前限制每分钟 " + limitPerMinute + " 次，请稍后再试";
            }
            queue.addLast(now);
        }
        return null;
    }
}
