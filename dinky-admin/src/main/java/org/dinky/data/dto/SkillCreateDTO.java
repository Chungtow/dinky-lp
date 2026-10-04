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

/** 新建 Skill 入参（阶段 4a） */
@Getter
@Setter
@ApiModel(value = "SkillCreateDTO", description = "Create a skill")
public class SkillCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(
            value = "Name",
            dataType = "String",
            example = "dw-sql-review",
            notes = "skill 名：^[a-z0-9][a-z0-9-]{1,63}$（将作为目录名，必须与 SKILL.md frontmatter 一致）")
    private String name;

    @ApiModelProperty(
            value = "Description",
            dataType = "String",
            example = "数仓 SQL 评审批量规范",
            notes = "一句话说明（会随清单注入模型，用于 @ 候选）")
    private String description;
}
