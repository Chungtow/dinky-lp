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

/** 写 skill 内文件入参（阶段 4b）。路径由 {@code org.dinky.ai.skill.SkillPathGuard} 统一校验。 */
@Getter
@Setter
@ApiModel(value = "SkillFileWriteDTO", description = "Write a file inside a skill directory")
public class SkillFileWriteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Skill ID", dataType = "Long", example = "1")
    private Long skillId;

    @ApiModelProperty(
            value = "Relative Path",
            dataType = "String",
            example = "references/conventions.md",
            notes = "相对 skill 根目录的路径；不允许绝对路径与 .. 片段")
    private String relativePath;

    @ApiModelProperty(value = "Content", dataType = "String", notes = "文本内容（UTF-8）")
    private String content;
}
