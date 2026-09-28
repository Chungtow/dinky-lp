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

import cn.hutool.json.JSONArray;
import lombok.Data;

/**
 * 一次工具循环的产出。
 *
 * @since 2026/09/28
 */
@Data
public class AiToolRunResult {

    /** 全部轮次累计的 token 用量（现有修复重试链路的 usage 另行累加） */
    private TokenUsage usage = new TokenUsage();

    /** 累计正文（供后续 SQL 校验与审计使用） */
    private String answer = "";

    /** 工具调用总次数（进入审计列 tool_call_count） */
    private int toolCallCount;

    /** 工具调用明细（进入审计列 tool_calls，已做字符限制） */
    private JSONArray toolCallLogs = new JSONArray();

    /** 是否有工具调用失败（用于前端提示与回归观察） */
    private boolean toolFailed;

    public void appendAnswer(String text) {
        if (text != null) {
            answer = answer + text;
        }
    }
}
