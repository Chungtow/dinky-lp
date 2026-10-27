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
 * AI Chat 知识库（{@code @kb}）元数据。
 *
 * <p><b>职责边界</b>：本表只承载知识库的<b>语义与权限</b>（编码 / 显示名 / 属主 / 租户 / 可见性 / 是否启用）。
 * 知识库的<b>内容</b>（文档 / 切片 / 向量）全在 kb-service（MinIO + Qdrant），Dinky 只持有
 * {@code code} 并在检索时把它写进 {@code filter.collection}。
 *
 * <p><b>为什么不存库内文档成员表</b>：归属是 kb-service 侧 payload 的属性，双写必然漂移；
 * 列表/计数一律实时向 kb-service 取（{@code GET /v1/collections}）。这是先行记录 v0.3 的既定决策。
 *
 * <p><b>权限</b>：本表<b>不接入</b> MyBatis-Plus 租户插件（与 {@link Skill} 同理），
 * 所有可见性判定走<b>显式条件</b>（{@code owner_id} / {@code tenant_id} / {@code visibility}），
 * 以免受「异步线程丢失租户上下文」影响（调研 §4.6.1 事实 3）。
 *
 * @since 2026/10/27
 */
@Data
@TableName("dinky_kb_collection")
@ApiModel(value = "KbCollection", description = "AI Chat Knowledge Base Collection")
public class KbCollection implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "ID", dataType = "Long", example = "1")
    private Long id;

    @ApiModelProperty(
            value = "Code",
            dataType = "String",
            notes = "知识库编码（^c_[0-9a-f]{8}$）：系统生成、永不变，写入 kb-service payload，API 全用它")
    private String code;

    @ApiModelProperty(value = "Name", dataType = "String", notes = "显示名（用户输入，可改；仅用于界面）")
    private String name;

    @ApiModelProperty(value = "Description", dataType = "String", notes = "一句话说明（@ 候选与范围声明用）")
    private String description;

    @ApiModelProperty(value = "Owner Id", dataType = "Integer", notes = "属主（sys_user.id）")
    private Integer ownerId;

    @ApiModelProperty(value = "Tenant Id", dataType = "Integer", notes = "租户（sys_tenant.id，请求线程内取值）")
    private Integer tenantId;

    @ApiModelProperty(value = "Visibility", dataType = "String", notes = "private | tenant | public")
    private String visibility;

    @ApiModelProperty(value = "Enabled", dataType = "Boolean", notes = "是否可被检索（0 否 / 1 是）")
    private Boolean enabled;

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
