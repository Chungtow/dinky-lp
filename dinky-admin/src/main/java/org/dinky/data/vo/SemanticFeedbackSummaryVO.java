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
import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义反馈汇总（P4 一期 §3.7 {@code GET /semantic/feedback/summary}）。
 *
 * <p>给两处用：①「待办」角标（只看 {@code open}）；②运营视图看问题类型分布。
 * 刻意做成<b>计数快照</b>而不是图表数据——一期只需要「有没有人用、处理得怎么样」。
 */
@Getter
@Setter
@ApiModel(value = "SemanticFeedbackSummaryVO", description = "Semantic feedback counters")
public class SemanticFeedbackSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Total", notes = "总条数（可按项目过滤）")
    private long total;

    @ApiModelProperty(value = "Open", notes = "待处理条数（待办角标）")
    private long open;

    @ApiModelProperty(value = "Resolved", notes = "已处理条数")
    private long resolved;

    @ApiModelProperty(value = "Ignored", notes = "已忽略条数")
    private long ignored;

    @ApiModelProperty(value = "Recent 7 Days", notes = "近 7 天新增条数（看有没有人在用）")
    private long recent7d;

    @ApiModelProperty(value = "Positive", notes = "rating=1 的条数")
    private long positive;

    @ApiModelProperty(value = "Negative", notes = "rating=-1 的条数")
    private long negative;

    @ApiModelProperty(value = "By Feedback Type", notes = "问题类型分布（空类型归到 UNSPECIFIED）")
    private Map<String, Long> byFeedbackType = new LinkedHashMap<>();
}
