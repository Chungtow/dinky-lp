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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * skill 目录树节点（阶段 4b）。
 *
 * <p><b>只暴露相对路径</b>（相对于 skill 根目录）——前端据此渲染树、发起文件操作，
 * <b>不感知</b>资源存储里的绝对路径（如 {@code /skills/xxx/}），避免泄漏存储布局、也让前端无需拼路径。
 */
@Getter
@Setter
@ApiModel(value = "SkillFileNode", description = "File tree node inside a skill")
public class SkillFileNode implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Name", dataType = "String", example = "conventions.md")
    private String name;

    @ApiModelProperty(value = "Relative Path", dataType = "String", example = "references/conventions.md")
    private String relativePath;

    @ApiModelProperty(value = "Directory", dataType = "Boolean", notes = "true 表示目录")
    private boolean directory;

    @ApiModelProperty(value = "Main File", dataType = "Boolean", notes = "true 表示该资产的主文件（SKILL.md / DOC.md）")
    private boolean mainFile;

    @ApiModelProperty(value = "Size", dataType = "Long", example = "1024")
    private Long size;

    @ApiModelProperty(value = "Update Time", dataType = "String", example = "2026-10-05 16:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @ApiModelProperty(value = "Children", dataType = "List<SkillFileNode>")
    private List<SkillFileNode> children = new ArrayList<>();
}
