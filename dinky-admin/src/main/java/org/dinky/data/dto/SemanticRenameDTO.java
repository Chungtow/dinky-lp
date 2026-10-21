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

/** 语义条目改名请求（同目录内改名，不跨目录移动） */
@Getter
@Setter
@ApiModel(value = "SemanticRenameDTO", description = "Rename an entry inside a semantic project")
public class SemanticRenameDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Project", notes = "项目名（HDFS 一级目录）", required = true)
    private String project;

    @ApiModelProperty(value = "Path", notes = "待改名的相对路径（相对项目根）", required = true)
    private String path;

    @ApiModelProperty(value = "NewName", notes = "新名称，只允许单段（不含 /），不得重名", required = true)
    private String newName;

    @ApiModelProperty(value = "IsDir", notes = "前端视角是否目录；后端以 HDFS 实际类型为准，此字段仅用于校验提示")
    private Boolean isDir;

    @ApiModelProperty(value = "BaseMtime", notes = "改名文件时的乐观锁基准 mtime；目录改名忽略此值")
    private Long baseMtime;
}
