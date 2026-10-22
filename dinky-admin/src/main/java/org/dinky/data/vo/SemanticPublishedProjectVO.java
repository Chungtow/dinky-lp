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
 * 已发布语义项目（P3b，{@code @semantic/} 一级候选）。
 *
 * <p>与 {@link SemanticProjectVO} 的区别：那个是**编辑区**项目（{@code /semantic/<p>}，可写）；
 * 这个是**已发布版本**（{@code /semantic/_published/<p>/<ver>}，不可变），AI 只能引用后者。
 */
@Getter
@Setter
@ApiModel(value = "SemanticPublishedProjectVO", description = "Published dbt semantic project")
public class SemanticPublishedProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Project", notes = "语义项目名")
    private String name;

    @ApiModelProperty(value = "Version", notes = "最新发布版本（目录名降序取首个）")
    private String version;

    @ApiModelProperty(value = "IndexState", notes = "ready / index_pending（未就绪时注入会降级直读 YAML）")
    private String indexState;

    @ApiModelProperty(value = "PublishedAt", notes = "发布时间（meta.json）")
    private String publishedAt;

    @ApiModelProperty(value = "TableCount", notes = "可引用语义模型数")
    private int tableCount;

    @ApiModelProperty(value = "MetricCount", notes = "可引用指标数")
    private int metricCount;
}
