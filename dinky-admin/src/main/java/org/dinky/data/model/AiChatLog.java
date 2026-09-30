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

package org.dinky.data.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * AI 对话审计日志（表 <code>dinky_ai_chat_log</code>）。
 *
 * <p>用于回答三个运维问题：<b>谁</b>在<b>什么时候</b>问了<b>什么</b>，消耗了多少 token，
 * 生成的 SQL 是否可执行。同时为"按天配额"提供统计来源。
 *
 * @since 2026/09/27
 */
@Data
@TableName("dinky_ai_chat_log")
@ApiModel(value = "AiChatLog", description = "AI Chat Log Information")
public class AiChatLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "ID", dataType = "Long", example = "1")
    private Long id;

    @ApiModelProperty(value = "User Id", dataType = "Integer", notes = "操作用户")
    private Integer userId;

    @ApiModelProperty(value = "Session Id", dataType = "String", notes = "会话 id")
    private String sessionId;

    @ApiModelProperty(value = "Action", dataType = "String", notes = "TEXT_TO_SQL / EXPLAIN")
    private String action;

    @ApiModelProperty(value = "Model", dataType = "String", notes = "模型名称")
    private String model;

    @ApiModelProperty(value = "Database Id", dataType = "Integer", notes = "数据源 id")
    private Integer databaseId;

    @ApiModelProperty(value = "Schema Name", dataType = "String")
    private String schemaName;

    @ApiModelProperty(value = "Question", dataType = "String", notes = "用户输入")
    private String question;

    @ApiModelProperty(value = "SQL", dataType = "String", notes = "模型生成并被校验的 SQL")
    private String sqlText;

    @ApiModelProperty(value = "Exec Status", dataType = "String", notes = "none/verified/failed/rejected")
    private String execStatus;

    @ApiModelProperty(value = "Exec Error", dataType = "String", notes = "数据源返回的原始错误")
    private String execError;

    @ApiModelProperty(value = "Retry Count", dataType = "Integer", notes = "自动修复重试次数")
    private Integer retryCount;

    @ApiModelProperty(value = "Prompt Tokens", dataType = "Integer")
    private Integer promptTokens;

    @ApiModelProperty(value = "Completion Tokens", dataType = "Integer")
    private Integer completionTokens;

    @ApiModelProperty(value = "Duration Ms", dataType = "Long", notes = "端到端耗时")
    private Long durationMs;

    /** 阶段 1b：本次对话的工具调用次数（0 表示未使用工具） */
    @ApiModelProperty(value = "Tool Call Count", dataType = "Integer", notes = "工具调用次数")
    private Integer toolCallCount;

    /** 阶段 1b：工具调用摘要 JSON（工具名 / 参数 / 成败 / 耗时），已做字符限制 */
    @ApiModelProperty(value = "Tool Calls", dataType = "String", notes = "工具调用明细摘要 JSON")
    private String toolCalls;

    @ApiModelProperty(value = "Success", dataType = "Boolean", notes = "整体是否成功")
    private Boolean success;

    /** 阶段 2：被 AI 整块改写的作业 id */
    @ApiModelProperty(value = "Write Task Id", dataType = "Long", notes = "被 AI 改写的作业 id")
    private Long writeTaskId;

    /** 阶段 2：改写前内容 hash（不存正文，避免业务代码撑大审计表） */
    @ApiModelProperty(value = "Write Before Hash", dataType = "String", notes = "改写前内容 hash")
    private String writeBeforeHash;

    /** 阶段 2：改写后内容 hash */
    @ApiModelProperty(value = "Write After Hash", dataType = "String", notes = "改写后内容 hash")
    private String writeAfterHash;

    /** 阶段 2：内容字符数变化（正数为增加、负数为删减） */
    @ApiModelProperty(value = "Write Chars", dataType = "Integer", notes = "改写字符数变化")
    private Integer writeChars;

    @ApiModelProperty(value = "Create Time", dataType = "LocalDateTime")
    private LocalDateTime createTime;
}
