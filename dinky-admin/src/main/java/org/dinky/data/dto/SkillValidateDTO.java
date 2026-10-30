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
 * Skill 正文校验入参（{@code POST /api/skill/validate}）。
 *
 * <p><b>没有 skillId</b>：校验的是"这份正文里的句柄"，与存到哪个 skill 无关——新建、另存为、
 * 从 RegCenter 弹窗保存都能复用同一个请求体；也因此天然支持"保存前先校验"的将来扩展。
 *
 * @since 2026/10/30
 */
@Getter
@Setter
@ApiModel(value = "SkillValidateDTO", description = "Validate @ handles inside skill content")
public class SkillValidateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Content", dataType = "String", notes = "SKILL.md 全文（上限 256KB，与文件写接口一致）")
    private String content;

    @ApiModelProperty(
            value = "Content Hash",
            dataType = "String",
            notes = "可选的正文指纹（前端计算）。原样回显，用于丢弃过期结果——" + "用户在校验返回前继续编辑时，行列会错位")
    private String contentHash;
}
