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
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库授权（按租户 / 角色 / 用户）。
 *
 * <p><b>为什么需要这张表</b>：{@link KbCollection} 上的 {@code owner_id} + {@code visibility}
 * 只能表达"一个库归一个租户"（或"本人 / 全体公开"）三档，无法表达
 * <b>"同一个库同时授权给 A、B 两个租户"</b>，也无法按角色授权。UAT 阶段确认：知识库的实际
 * 使用形态是<b>按租户授权为主、个人授权为辅</b>，故补此 ACL 表。
 *
 * <p><b>与 visibility 的关系</b>：{@code visibility} 仍是"快捷三档"，优先级最高、无需查本表；
 * 本表只承载"额外授权"，可见性 = owner ∪ public ∪ 归属租户 ∈ 我的租户 ∪ 本表命中。
 *
 * <p><b>为什么 subject_id 不建外键</b>：它是多态列（user / role / tenant 三类共用一列），
 * 数据库无法用一个外键指向三张表；合法性由应用层保证，ER 图中本表通过
 * {@code collection_id} 与 {@link KbCollection} 连线。
 *
 * @since 2026/10/28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("dinky_kb_collection_grant")
@ApiModel(value = "KbCollectionGrant", description = "Knowledge base collection grant")
public class KbCollectionGrant implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主体类型：用户 */
    public static final String SUBJECT_USER = "user";

    /** 主体类型：角色 */
    public static final String SUBJECT_ROLE = "role";

    /** 主体类型：租户（主要形态） */
    public static final String SUBJECT_TENANT = "tenant";

    /** 权限：可检索 */
    public static final String PERMISSION_VIEW = "view";

    /** 权限：可推送 / 维护 */
    public static final String PERMISSION_WRITE = "write";

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "主键")
    private Long id;

    @ApiModelProperty(value = "知识库 id（dinky_kb_collection.id）")
    private Long collectionId;

    @ApiModelProperty(value = "被授权主体类型：user | role | tenant")
    private String subjectType;

    @ApiModelProperty(value = "主体 id")
    private Integer subjectId;

    @ApiModelProperty(value = "权限：view | write")
    private String permission;

    @ApiModelProperty(value = "授权操作人（dinky_user.id）")
    private Integer createBy;

    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    @ApiModelProperty(value = "更新时间")
    private Date updateTime;
}
