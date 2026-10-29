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

import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库条目 + 计数（批次 B：左侧栏「知识库」tab 的库列表）。
 *
 * <p><b>为什么计数不在 Dinky 表里</b>：篇数/切片数是<b>内容侧</b>的实时聚合（kb-service 的
 * {@code meta_scan}，60 s 缓存），存进 Dinky 就要双写、必漂移。所以库的"目录与权限"取 Dinky 表，
 * "有几篇"取 kb-service——两者在代理层按 {@code code} 对齐。
 *
 * <p>{@code lastTs} 为 {@code 0} 表示该库在 kb-service 侧<b>还没有任何文档</b>（空库/草稿），
 * 前端据此显示"暂无文档"，而不是 1970 年。
 */
@Getter
@Setter
@ApiModel(value = "KbCollectionStatVO", description = "A visible collection with document counters")
public class KbCollectionStatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Code", notes = "库编码（显示名请用 name）")
    private String code;

    @ApiModelProperty(value = "Name", notes = "显示名（来自 Dinky 表）")
    private String name;

    @ApiModelProperty(value = "Description", notes = "一句话说明")
    private String description;

    @ApiModelProperty(value = "Docs", notes = "文档数（kb-service 侧实时聚合）")
    private long docs;

    @ApiModelProperty(value = "Chunks", notes = "切片数")
    private long chunks;

    @ApiModelProperty(value = "LastTs", notes = "最近入库时间（epoch 秒；0 表示空库）")
    private long lastTs;
}
