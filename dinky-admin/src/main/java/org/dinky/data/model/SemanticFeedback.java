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

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 语义资产反馈（P4 一期：口径错了有地方说、有人处理）。
 *
 * <p><b>要解决的问题</b>：P3 之前 AI 能把语义资产用起来，但用户发现「这条指标口径写错了」
 * 时没有任何出口——只能口头告诉管理员，既不可追溯也不会被跟进。本表把这类意见落到
 * <b>具体资产 + 具体发布版本</b>上，并带一个最小状态机 {@code OPEN → RESOLVED / IGNORED}。
 *
 * <p><b>为什么要记 {@code version} 与 {@code yaml_sha256}</b>：语义资产会随发布更新，
 * 一条「口径有误」的反馈如果不知道是针对哪一版提的，处理完也无法判断是否还有效。
 * 这两个值由后端按<b>提交时的最新发布版本</b>自行解析，不信任前端传值。
 *
 * <p><b>租户</b>：本表<b>不加</b> {@code tenant_id}——语义项目存放在全局 HDFS 路径 {@code /semantic}，
 * 自身没有租户维度；对应的 {@code dinky_ai_chat_log} 同样没有租户列，保持一致口径。
 * 该表未加入 {@code MybatisPlusConfig.IGNORE_TABLE_NAMES}，租户插件默认放行（不注入条件）。
 *
 * @since 2026/10/23
 */
@Data
@TableName("dinky_semantic_feedback")
@ApiModel(value = "SemanticFeedback", description = "Semantic Asset Feedback")
public class SemanticFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待处理（提交后的初始态，也是「待办」列表的筛选条件） */
    public static final String STATUS_OPEN = "OPEN";

    /** 已处理（口径已修正 / 已确认） */
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 已忽略（确认不是问题，或无法复现） */
    public static final String STATUS_IGNORED = "IGNORED";

    /** 有用 */
    public static final int RATING_UP = 1;

    /** 有误 */
    public static final int RATING_DOWN = -1;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "ID", dataType = "Long", example = "1")
    private Long id;

    @ApiModelProperty(
            value = "Project Name",
            dataType = "String",
            notes = "语义项目名（对应 HDFS /semantic/_published/<project>）")
    private String projectName;

    @ApiModelProperty(value = "Asset Name", dataType = "String", notes = "资产名（语义模型名 / 指标名）")
    private String assetName;

    @ApiModelProperty(value = "Asset Type", dataType = "String", notes = "TABLE | METRIC")
    private String assetType;

    @ApiModelProperty(value = "Version", dataType = "String", notes = "反馈时的发布版本号（后端解析，不取前端值）")
    private String version;

    @ApiModelProperty(value = "Yaml Sha256", dataType = "String", notes = "反馈时该资产 YAML 的 sha256（判断反馈是否已随版本失效）")
    private String yamlSha256;

    @ApiModelProperty(value = "Rating", dataType = "Integer", notes = "1=有用 / -1=有误")
    private Integer rating;

    @ApiModelProperty(
            value = "Feedback Type",
            dataType = "String",
            notes = "WRONG_DEFINITION | WRONG_DIMENSION | OUTDATED | OTHER")
    private String feedbackType;

    @ApiModelProperty(value = "Content", dataType = "String", notes = "反馈正文")
    private String content;

    @ApiModelProperty(value = "Session Id", dataType = "String", notes = "来源 AI 会话（可空，用于回溯上下文）")
    private String sessionId;

    @ApiModelProperty(value = "User Id", dataType = "Integer", notes = "提交人（dinky_sys_user.id）")
    private Integer userId;

    @ApiModelProperty(value = "Status", dataType = "String", notes = "OPEN | RESOLVED | IGNORED")
    private String status;

    @ApiModelProperty(value = "Resolver Id", dataType = "Integer", notes = "处理人（dinky_sys_user.id）")
    private Integer resolverId;

    @ApiModelProperty(value = "Resolve Note", dataType = "String", notes = "处理说明")
    private String resolveNote;

    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "Resolve Time", dataType = "String", notes = "处理时间")
    private LocalDateTime resolveTime;

    @TableField(fill = FieldFill.INSERT)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "Create Time", dataType = "String", notes = "创建时间")
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "Update Time", dataType = "String", notes = "更新时间")
    private LocalDateTime updateTime;
}
