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

package org.dinky.data.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端可用的 AI 配置状态。
 *
 * <p><b>NOTE:</b> 该结构<strong>不包含</strong> API Key，密钥仅在服务端使用。
 *
 * @since 2026/09/26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "AiChatConfig", description = "AI Chat Config")
public class AiChatConfig {

    @ApiModelProperty(value = "是否启用 AI 能力")
    private Boolean enable;

    @ApiModelProperty(value = "模型名称")
    private String model;

    @ApiModelProperty(value = "模型服务地址")
    private String baseUrl;

    @ApiModelProperty(value = "是否已配置 API Key")
    private Boolean hasApiKey;
}
