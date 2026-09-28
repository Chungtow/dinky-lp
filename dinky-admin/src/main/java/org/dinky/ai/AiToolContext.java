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

import org.dinky.data.model.DataBase;

import lombok.Getter;
import lombok.Setter;

/**
 * 工具执行上下文。
 *
 * <p><b>安全约束</b>：本对象<b>永远不会被序列化进发往大模型的请求</b>——其中的 {@link DataBase}
 * 含数据源地址与凭据。工具只能从这里取已解析好的对象，模型侧永远见不到它
 * （LangChain4j 的 ToolContext 模式，见调研报告 §7.5）。
 *
 * <p><b>为什么持有 DataBase 对象而不是 id</b>：dinky 的租户过滤基于 ThreadLocal，而工具循环跑在
 * 异步线程池里，按 id 反查会因租户上下文丢失而查不到数据源（表现为「数据源不存在」）。
 * 因此由请求线程解析好后传入（同 {@code SqlVerifier#verify(DataBase, String)} 的做法）。
 *
 * <p>刻意<b>不提供</b> {@code toString()}：避免误打日志时把数据源凭据落到日志里。
 *
 * @since 2026/09/28
 */
@Getter
@Setter
public class AiToolContext {

    /** 请求线程已解析好的数据源（不可为 null 的工具才有意义） */
    private DataBase dataBase;

    /** 当前 schema（用户未指定时工具可自行降级） */
    private String schemaName;

    private Integer userId;

    private Integer tenantId;

    /** 单个工具的超时秒数 */
    private int timeoutSeconds;

    /** 单个工具返回内容的字符上限 */
    private int maxResultChars;

    public static AiToolContext create(
            DataBase dataBase,
            String schemaName,
            Integer userId,
            Integer tenantId,
            int timeoutSeconds,
            int maxResultChars) {
        AiToolContext context = new AiToolContext();
        context.setDataBase(dataBase);
        context.setSchemaName(schemaName);
        context.setUserId(userId);
        context.setTenantId(tenantId);
        context.setTimeoutSeconds(timeoutSeconds);
        context.setMaxResultChars(maxResultChars);
        return context;
    }
}
