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

import org.dinky.data.model.AiChatLog;
import org.dinky.mybatis.service.ISuperService;

/**
 * AI 对话审计服务。
 *
 * @since 2026/09/27
 */
public interface AiChatLogService extends ISuperService<AiChatLog> {

    /**
     * 记录一条对话审计（失败不影响主流程）。
     *
     * @param log 审计内容
     */
    void record(AiChatLog log);

    /**
     * 查询某用户当日用量（用于按天配额）。
     *
     * @param userId 用户 id
     * @return 当日请求数与 token 数；查询失败时返回 0 值（不阻断请求）
     */
    DailyUsage todayUsage(Integer userId);

    /** 当日用量 */
    class DailyUsage {
        private int requests;
        private long tokens;

        public DailyUsage() {}

        public DailyUsage(int requests, long tokens) {
            this.requests = requests;
            this.tokens = tokens;
        }

        public int getRequests() {
            return requests;
        }

        public void setRequests(int requests) {
            this.requests = requests;
        }

        public long getTokens() {
            return tokens;
        }

        public void setTokens(long tokens) {
            this.tokens = tokens;
        }
    }
}
