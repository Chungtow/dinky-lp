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

/** 删除 / 新建 skill 内目录入参（阶段 4b）。 */
@Getter
@Setter
@ApiModel(value = "SkillFileRemoveDTO", description = "Remove or create a directory inside a skill")
public class SkillFileRemoveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Skill ID", dataType = "Long", example = "1")
    private Long skillId;

    @ApiModelProperty(
            value = "Relative Path",
            dataType = "String",
            example = "references",
            notes = "相对 skill 根目录的路径；删除目录时会递归删除其下所有文件")
    private String relativePath;
}
