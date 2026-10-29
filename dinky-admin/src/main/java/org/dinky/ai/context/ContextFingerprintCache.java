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

package org.dinky.ai.context;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话上下文指纹缓存（P4 一期：自动上下文绑定的重注入判定）。
 *
 * <p><b>要解决的问题</b>：后端原本只按 {@code sessionId} 是否为空判断「是否首轮」
 * （见 {@code AiChatServiceImpl#firstTurn}），首轮才注入 schema 与语义清单。
 * 自动绑定上线后，切换编辑区 tab / 切换语义项目都会让上下文变化，但 {@code sessionId} 不变，
 * 于是 AI 会继续沿用旧项目的上下文——P3 曾用「切项目那轮不带 sessionId」绕过，
 * 但本批要求「切 tab 即时跟随」，若每次切 tab 都新开会话，对话历史会断裂。
 *
 * <p><b>做法</b>：前端按当前绑定来源算一个指纹随请求带来；后端记住「本会话上次用的指纹」，
 * 与本次不一致即视作首轮重注入。本类就是这份记忆。
 *
 * <p><b>降级方向是安全的</b>：缓存条目过期 / 进程重启 / 会话从未记录 → {@link #get} 返回
 * {@code null} → 后端按首轮处理 → 重新注入（多花一点 token，但绝不会给出旧上下文的错误答案）。
 *
 * <p>实现上不引入新依赖（项目未使用 Caffeine / Guava Cache），用
 * {@link ConcurrentHashMap} + 惰性过期 + 容量上限淘汰。
 */
@Slf4j
@Component
public class ContextFingerprintCache {

    /** 条目存活时间：超过即视为「未知上下文」，下一轮按首轮重注入。 */
    private static final Duration TTL = Duration.ofHours(2);

    /** 容量上限：防止长期运行后会话数增长导致内存膨胀。 */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 取该会话上次使用的上下文指纹。
     *
     * @param sessionId 会话 id
     * @return 指纹；未记录 / 已过期 / sessionId 为空时返回 {@code null}（调用方按首轮处理）
     */
    public String get(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        Entry entry = cache.get(sessionId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp > TTL.toMillis()) {
            cache.remove(sessionId);
            return null;
        }
        return entry.fingerprint;
    }

    /**
     * 记录该会话本轮使用的上下文指纹。
     *
     * @param sessionId   会话 id（为空则忽略：无会话即无记忆）
     * @param fingerprint 指纹（为空则忽略：等价于旧客户端，不参与判定）
     */
    public void put(String sessionId, String fingerprint) {
        if (sessionId == null || sessionId.isEmpty() || fingerprint == null || fingerprint.isEmpty()) {
            return;
        }
        cache.put(sessionId, new Entry(fingerprint, System.currentTimeMillis()));
        if (cache.size() > MAX_ENTRIES) {
            evictStaleAndOverflow();
        }
    }

    /** 清理指定会话（会话结束 / 用户新建会话时调用，避免残留）。 */
    public void evict(String sessionId) {
        if (sessionId != null) {
            cache.remove(sessionId);
        }
    }

    /** 当前条目数（仅用于观测与单测）。 */
    public int size() {
        return cache.size();
    }

    /**
     * 容量超限时的清理：先删过期条目；仍超限则按写入时间从旧到新淘汰。
     *
     * <p>非高精度策略——目的只是给内存设一个上界，不追求 LRU 语义。
     */
    private void evictStaleAndOverflow() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().timestamp > TTL.toMillis());
        int overflow = cache.size() - MAX_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        cache.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().timestamp))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .forEach(cache::remove);
        log.debug("ContextFingerprintCache evicted {} entries, size={}", overflow, cache.size());
    }

    /** 一条记忆：指纹 + 写入时间（毫秒）。 */
    private static final class Entry {

        private final String fingerprint;

        private final long timestamp;

        private Entry(String fingerprint, long timestamp) {
            this.fingerprint = fingerprint;
            this.timestamp = timestamp;
        }
    }
}
