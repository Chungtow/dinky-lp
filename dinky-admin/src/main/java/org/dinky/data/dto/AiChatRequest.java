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

package org.dinky.data.dto;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * AI Chat 请求。
 *
 * <p><b>NOTE:</b> 请求体中只允许携带元数据定位信息（数据源 / schema / 表），
 * 严禁携带任何业务数据行——AI 上下文只包含元数据，这是本功能的安全红线。
 *
 * @since 2026/09/26
 */
@Data
@ApiModel(value = "AiChatRequest", description = "AI Chat Request")
public class AiChatRequest {

    /** 动作：TEXT_TO_SQL（自然语言生成 SQL） / EXPLAIN（解释 SQL） */
    @ApiModelProperty(value = "动作：TEXT_TO_SQL / EXPLAIN", example = "TEXT_TO_SQL")
    private String action = "TEXT_TO_SQL";

    @ApiModelProperty(value = "本轮用户输入")
    private String message;

    @ApiModelProperty(value = "历史对话（不含本轮），用于多轮追问")
    private List<AiChatMessage> history;

    /** 会话标识：为空表示首轮（首轮才携带 schema 上下文，后续跳过以节省 token） */
    @ApiModelProperty(value = "会话标识，首轮为空")
    private String sessionId;

    @ApiModelProperty(value = "数据源 ID（上下文来源）")
    private Integer databaseId;

    @ApiModelProperty(value = "schema 名称")
    private String schemaName;

    @ApiModelProperty(value = "表名（Catalog 选中表时携带）")
    private String tableName;

    @ApiModelProperty(value = "SQL 方言，用于提示词", example = "MySQL")
    private String dialect;

    /**
     * 当前作业的编辑区内容（阶段 1.0「作业上下文绑定」）。
     *
     * <p>早期版本仅 EXPLAIN 动作携带，导致普通提问时模型看不到用户正在写的代码；现已改为全动作下发。
     */
    @ApiModelProperty(value = "当前作业编辑区内容")
    private String sql;

    /**
     * 当前作业 id，用于读取最近一次执行状态与报错原文，帮助模型回答“为什么跑挂了”。
     *
     * <p>只取 JobInstance 的 status / error 等元信息，不取业务数据行。
     */
    @ApiModelProperty(value = "当前作业 id")
    private Integer taskId;

    /**
     * 用户在编辑器中<b>选中</b>的片段（阶段 1a：1.0.4）。
     *
     * <p>非空时优先级高于 {@link #sql} 全文：用户选中某段 SQL 提问，意图就是问这一段，
     * 此时再下发全文既浪费预算又干扰模型（业界一致做法）。
     */
    @ApiModelProperty(value = "编辑器选中片段")
    private String selectedSql;

    /**
     * 上下文范围档位（阶段 1a：1.1）：{@code current}（仅当前选中表）/ {@code all}（当前数据源全库，
     * 默认，兼容既有行为）/ {@code custom}（仅 {@link #customTables} 勾选的表）。
     */
    @ApiModelProperty(value = "上下文范围：current / all / custom", example = "all")
    private String contextScope;

    /** {@code contextScope=custom} 时用户勾选的表名列表 */
    @ApiModelProperty(value = "custom 档位下勾选的表名")
    private List<String> customTables;

    /**
     * 用户通过 {@code @} 显式引用的上下文项（阶段 1a：1.4）。
     *
     * <p>这些项享有<b>最高优先级且不被预算裁剪</b>。
     */
    @ApiModelProperty(value = "@ 显式引用项")
    private List<AiChatMention> mentions;

    /**
     * 服务端填充的当前用户 id（限流 / 配额 / 审计用）。
     *
     * <p><b>不接受前端传入</b>：Controller 会用 Sa-Token 的登录 id 覆盖，防止伪造绕过限流。
     */
    private Integer userId;
}
