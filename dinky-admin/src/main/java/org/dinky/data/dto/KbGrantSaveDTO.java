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

import java.io.Serializable;
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 新增知识库授权（批次 D）。
 *
 * <p><b>为什么 {@code subjectIds} 是批量</b>：管理页的授权顺序是"<b>租户</b> → 角色 → 成员"，
 * 按租户授权时一次要勾一串租户；逐个提交既慢又容易漏。授权表仍是一行一个主体，
 * 批量只是这一层的便利。
 *
 * <p><b>{@code permission} 只有两档</b>：{@code view}（能看能搜）与 {@code write}（还能往里推）。
 * 没有"管理"档——管理能力由权限点（save/share）+ 属主身份决定，不放在数据侧，
 * 否则会出现"数据授权却能改别人库配置"的越权。
 */
@Getter
@Setter
@ApiModel(value = "KbGrantSaveDTO", description = "Grant view/write on a collection to tenants / roles / users")
public class KbGrantSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Subject Type", notes = "tenant / role / user", required = true)
    private String subjectType;

    @ApiModelProperty(value = "Subject Ids", notes = "主体 id 列表（批量）", required = true)
    private List<Integer> subjectIds;

    @ApiModelProperty(value = "Permission", notes = "view（默认）/ write", required = true)
    private String permission;
}
