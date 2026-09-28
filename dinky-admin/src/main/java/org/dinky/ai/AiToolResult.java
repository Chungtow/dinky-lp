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

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行结果。
 *
 * <p><b>安全约束</b>：{@link #content} 与 {@link #errorMessage} 都是<b>回灌给模型</b>的文本，
 * 因此错误信息必须是<b>脱敏后的</b>——原始异常里可能包含 JDBC URL、用户名、内网地址
 * （调研报告 §7.2）。完整异常只进服务端日志与审计表。
 *
 * @since 2026/09/28
 */
@Data
@Builder
public class AiToolResult {

    /** 是否成功 */
    private boolean success;

    /** 成功时给模型的正文（元数据清单 / 样例行等） */
    private String content;

    /** 失败时给模型的文案（已脱敏） */
    private String errorMessage;

    /** 执行耗时（毫秒，用于前端展示与审计） */
    private long costMs;

    public static AiToolResult success(String content, long costMs) {
        return AiToolResult.builder()
                .success(true)
                .content(content)
                .costMs(costMs)
                .build();
    }

    public static AiToolResult failure(String sanitizedMessage, long costMs) {
        return AiToolResult.builder()
                .success(false)
                .errorMessage(sanitizedMessage)
                .costMs(costMs)
                .build();
    }
}
