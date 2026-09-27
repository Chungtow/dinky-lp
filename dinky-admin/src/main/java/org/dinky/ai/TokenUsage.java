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

import lombok.Data;

/**
 * 单次大模型调用的 token 用量（用于审计与按天配额）。
 *
 * <p>注意：流式调用下多数模型默认<b>不返回</b> usage，需在请求中带
 * <code>stream_options.include_usage</code>；若模型仍不返回，则此处为 0，
 * 配额统计会偏小（审计日志可据此识别）。
 *
 * @since 2026/09/27
 */
@Data
public class TokenUsage {

    private int promptTokens;
    private int completionTokens;

    public TokenUsage() {}

    public TokenUsage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    /** 总 token 数 */
    public int total() {
        return promptTokens + completionTokens;
    }
}
