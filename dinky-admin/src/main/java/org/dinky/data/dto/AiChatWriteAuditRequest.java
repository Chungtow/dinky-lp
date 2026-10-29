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
 * AI Craft 写入审计请求（阶段 2：T2-5）。
 *
 * <p><b>为什么必须落审计</b>：阶段 1 及之前，AI 的"插入编辑器"是纯前端闭环，后端完全不知道
 * AI 改了什么。这在 Ask 时代可以接受，但在 Craft（AI 主动改写）时代不可接受——它是放开写能力后
 * <b>唯一的事后追溯手段</b>。
 *
 * <p><b>NOTE:</b> 只上报 hash 与字符数变化，<b>不上传代码正文</b>：既避免审计表膨胀，
 * 也避免作业代码进入后端存储造成额外的泄露面。
 *
 * @since 2026/09/30
 */
@Data
@ApiModel(value = "AiChatWriteAuditRequest", description = "AI Craft Write Audit Request")
public class AiChatWriteAuditRequest {

    /** 被 AI 整块改写的作业 id */
    @ApiModelProperty(value = "被改写的作业 id")
    private Integer taskId;

    @ApiModelProperty(value = "会话标识")
    private String sessionId;

    /** 冗余记录模式，便于后续按 mode 统计（当前固定为 craft） */
    @ApiModelProperty(value = "对话模式", example = "craft")
    private String mode;

    @ApiModelProperty(value = "改写前内容 hash")
    private String beforeHash;

    @ApiModelProperty(value = "改写后内容 hash")
    private String afterHash;

    @ApiModelProperty(value = "字符数变化（正增负减）")
    private Integer chars;

    /**
     * 服务端填充的当前用户 id（审计用）。
     *
     * <p><b>不接受前端传入</b>：Controller 会用 Sa-Token 的登录 id 覆盖，防止伪造。
     */
    private Integer userId;
}
