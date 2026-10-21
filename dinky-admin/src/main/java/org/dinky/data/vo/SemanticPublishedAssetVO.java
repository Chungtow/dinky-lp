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
 * 已发布项目内可引用资产（P3b，{@code @semantic/<p>/} 二级候选）。
 *
 * <p>{@code type} 用索引 {@code names[]} 的口径（{@code TABLE} / {@code METRIC}）；
 * 同名时前端<b>两种都列</b>，由用户选长式写法——后端解析时同样"报歧义不猜"（D20）。
 */
@Getter
@Setter
@ApiModel(value = "SemanticPublishedAssetVO", description = "Referable asset of a published semantic project")
public class SemanticPublishedAssetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Name", notes = "资产名（语义模型名或指标名）")
    private String name;

    @ApiModelProperty(value = "Type", notes = "TABLE / METRIC")
    private String type;

    @ApiModelProperty(value = "OneLiner", notes = "一句话口径（索引 one_liner；降级时为空）")
    private String oneLiner;
}
