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
 * 重命名 skill 内文件入参（批次 F）。
 *
 * <p>{@code newName} 只允许<b>纯文件名</b>（不含 {@code /}）：一期只改名字不换目录，
 * 跨目录移动会牵动父目录的递归建删，放到后续版本。
 */
@Getter
@Setter
@ApiModel(value = "SkillFileRenameDTO", description = "Rename a file inside a skill")
public class SkillFileRenameDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Skill ID", dataType = "Long", example = "1")
    private Long skillId;

    @ApiModelProperty(
            value = "Relative Path",
            dataType = "String",
            example = "references/checklist.md",
            notes = "待重命名文件相对 skill 根目录的路径")
    private String relativePath;

    @ApiModelProperty(value = "New Name", dataType = "String", example = "checklist-v2.md", notes = "新文件名（不含路径）")
    private String newName;
}
