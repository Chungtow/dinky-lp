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
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库文档列表分页结果（批次 B）。
 *
 * <p>{@code total} 是<b>服务端</b>算的命中总数（不是本页条数）：界面要显示"共 N 篇"，
 * 而 N 与 {@code items.size()} 通常不等——这一列就是为了避免前端把本页条数当总数。
 */
@Getter
@Setter
@ApiModel(value = "KbDocumentPageVO", description = "Paged documents of a knowledge base collection")
public class KbDocumentPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Collection", notes = "本次查询的库编码")
    private String collection;

    @ApiModelProperty(value = "Total", notes = "命中总数（跨页）")
    private long total;

    @ApiModelProperty(value = "Items", notes = "当前页条目")
    private List<KbDocumentVO> items;

    @ApiModelProperty(value = "Limit", notes = "本页容量")
    private int limit;

    @ApiModelProperty(value = "Offset", notes = "本页偏移")
    private int offset;
}
