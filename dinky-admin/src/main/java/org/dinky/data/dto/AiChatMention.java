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

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * AI Chat 中用户通过 {@code @} 显式引用的上下文项（阶段 1a）。
 *
 * <p>设计要点：显式引用是<b>最高优先级</b>上下文——即使用户选择的 context 档位较窄、
 * 或字符预算已用尽，被 {@code @} 指定的对象也<b>不得被裁剪</b>。
 * 这是「显式优于隐式」原则的落地：中文问题配英文表名时自动召回基本失效，
 * 用户手动指定是唯一可靠的兜底（2026-09-26 UAT 已实测）。
 *
 * <p>{@code type} 预留 {@code knowledge}：后续「语料包」能力（见阶段 1 计划 §3.4）
 * 可直接复用本结构，无需改协议。
 *
 * @since 2026/09/28
 */
@Data
@ApiModel(value = "AiChatMention", description = "AI Chat @ 引用项")
public class AiChatMention {

    /**
     * 引用类型：{@code table}（表）/ {@code job}（其它作业）/ {@code selection}（编辑区片段）/
     * {@code knowledge}（语料包，预留）
     */
    @ApiModelProperty(value = "引用类型：table / job / selection / knowledge", example = "table")
    private String type;

    /** schema 名称（type=table 时有效） */
    @ApiModelProperty(value = "schema 名称")
    private String schemaName;

    /** 引用对象的名称：表名 / 作业标题 / 语料包名 */
    @ApiModelProperty(value = "引用对象的名称", example = "tc_users")
    private String name;

    /**
     * 片段正文（type=selection / job 时由前端携带）。
     *
     * <p>仅编辑器内的 SQL 文本，<b>不含业务数据行</b>。
     */
    @ApiModelProperty(value = "片段正文（selection / job）")
    private String content;
}
