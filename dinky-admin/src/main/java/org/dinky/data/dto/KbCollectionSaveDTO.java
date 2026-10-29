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

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库新建 / 编辑（批次 D 管理页）。
 *
 * <p><b>{@code code} 不在这里</b>——它是知识库与 kb-service 之间的唯一连接键：
 * <ul>
 *   <li>Dinky 自建库：服务端生成 {@code c_} + 8 位 hex，<b>创建后永不改</b>；</li>
 *   <li>纳管既有库：原样沿用 RAG 侧标识（走 {@code /collections/adopt}，不是这个 DTO）。</li>
 * </ul>
 * 让前端传 code 的那一天，就是"改一下编码、检出一片空"故障的起点（D-K11/D-K12）。
 */
@Getter
@Setter
@ApiModel(value = "KbCollectionSaveDTO", description = "Create / update a knowledge base collection")
public class KbCollectionSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Id", notes = "为空 = 新建；不为空 = 编辑")
    private Long id;

    @ApiModelProperty(value = "Name", notes = "显示名，同租户内唯一", required = true)
    private String name;

    @ApiModelProperty(value = "Description", notes = "一句话说明（@ 候选里显示）")
    private String description;

    @ApiModelProperty(value = "Is Public", notes = "是否公开给全体登录用户。<b>公开只影响可见，不影响可写</b>（写权限恒需属主或 write 授权）")
    private Boolean isPublic;

    @ApiModelProperty(value = "Enabled", notes = "false = 停用（草稿态，不参与候选与检索；不是删除）")
    private Boolean enabled;
}
