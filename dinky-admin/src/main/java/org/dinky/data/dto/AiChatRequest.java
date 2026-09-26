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

    @ApiModelProperty(value = "EXPLAIN 动作的目标 SQL")
    private String sql;
}
