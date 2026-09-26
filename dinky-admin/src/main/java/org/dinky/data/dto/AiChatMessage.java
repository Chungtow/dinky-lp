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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条 AI 对话消息。
 *
 * @since 2026/09/26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "AiChatMessage", description = "AI Chat Message")
public class AiChatMessage {

    @ApiModelProperty(value = "角色：system / user / assistant", example = "user")
    private String role;

    @ApiModelProperty(value = "消息内容")
    private String content;

    public static AiChatMessage of(String role, String content) {
        return new AiChatMessage(role, content);
    }
}
