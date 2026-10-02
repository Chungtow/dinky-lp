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
 * AI Chat 执行确认 / 取消请求（阶段 2c-0）。
 *
 * <p>用于两个独立端点：{@code /api/aiChat/confirm}（二次确认写语句是否执行）与
 * {@code /api/aiChat/cancel}（中断本次运行）。两者都凭 SSE 流下发的 {@code runId} 定位运行。
 *
 * <p>{@code userId} 由服务端用登录态覆盖，不接受前端传入。
 *
 * @since 2026/10/02
 */
@Data
@ApiModel(value = "AiChatConfirmRequest", description = "AI Chat Confirm / Cancel Request")
public class AiChatConfirmRequest {

    /** 运行 id（由 SSE 流在对话开始时下发） */
    @ApiModelProperty(value = "运行 id")
    private String runId;

    /** 是否确认执行（true=确认；false/null=拒绝）。{@code /cancel} 端点忽略该字段 */
    @ApiModelProperty(value = "是否确认执行")
    private Boolean approve;

    /** 服务端填充的当前用户 id（鉴权：仅创建者可确认/取消） */
    private Integer userId;
}
