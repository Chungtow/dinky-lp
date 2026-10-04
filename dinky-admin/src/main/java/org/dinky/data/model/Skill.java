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
 * Skill 元数据（阶段 4a：Skills 基础与引用）。
 *
 * <p><b>职责边界</b>：本表只承载 skill 的<b>语义与权限</b>（谁建的 / 谁能看改 / 是否启用 / 版本与哈希）；
 * skill 的<b>文件内容</b>（{@code SKILL.md} 与 {@code references/}）存放在资源存储的
 * {@code skills/<name>/} 目录下，由 {@code dinky_resources} 登记路径。
 *
 * <p><b>权限</b>：本表<b>不接入</b> MyBatis-Plus 租户插件（未加入 {@code MybatisPlusConfig.IGNORE_TABLE_NAMES}），
 * 所有可见性判定走 <b>显式条件</b>（{@code owner_id} / {@code tenant_id} / {@code visibility}），
 * 以免受「异步线程丢失租户上下文」影响（见调研报告 §4.6.1 事实 3）。
 *
 * @since 2026/10/05
 */
@Data
@TableName("dinky_skill")
@ApiModel(value = "Skill", description = "AI Chat Skill Metadata")
public class Skill implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "ID", dataType = "Long", example = "1")
    private Long id;

    @ApiModelProperty(
            value = "Name",
            dataType = "String",
            notes = "skill 名（^[a-z0-9][a-z0-9-]{1,63}$，与 SKILL.md frontmatter 一致）")
    private String name;

    @ApiModelProperty(value = "Description", dataType = "String", notes = "一句话说明（来自 frontmatter）")
    private String description;

    @ApiModelProperty(
            value = "Dir Full Name",
            dataType = "String",
            notes = "skill 目录在资源存储中的 full_name（如 /skills/dw-sql-review）")
    private String dirFullName;

    @ApiModelProperty(value = "Owner Id", dataType = "Integer", notes = "属主（sys_user.id）")
    private Integer ownerId;

    @ApiModelProperty(value = "Tenant Id", dataType = "Integer", notes = "租户（sys_tenant.id）")
    private Integer tenantId;

    @ApiModelProperty(value = "Visibility", dataType = "String", notes = "private | tenant")
    private String visibility;

    @ApiModelProperty(value = "Enabled", dataType = "Boolean", notes = "是否可被引用")
    private Boolean enabled;

    @ApiModelProperty(value = "Source", dataType = "String", notes = "来源：local | third_party")
    private String source;

    @ApiModelProperty(value = "Asset Type", dataType = "String", notes = "资产类型（预留：未来 semantic 等）")
    private String assetType;

    @ApiModelProperty(value = "Meta Json", dataType = "String", notes = "类型特有元数据（JSON，4a 未使用）")
    private String metaJson;

    @ApiModelProperty(value = "Version", dataType = "Integer", notes = "内容版本（每次保存 +1）")
    private Integer version;

    @ApiModelProperty(value = "Content Hash", dataType = "String", notes = "SKILL.md 内容哈希（变更审计）")
    private String contentHash;

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
