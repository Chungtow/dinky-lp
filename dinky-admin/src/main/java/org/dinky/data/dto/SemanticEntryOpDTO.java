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

/** 语义条目通用操作请求（新建目录 / 删除） */
@Getter
@Setter
@ApiModel(value = "SemanticEntryOpDTO", description = "Create or delete an entry of a semantic project")
public class SemanticEntryOpDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Project", notes = "项目名（HDFS 一级目录）", required = true)
    private String project;

    @ApiModelProperty(value = "Path", notes = "相对项目根的路径（新建目录时为待建目录）", required = true)
    private String path;

    @ApiModelProperty(value = "Recursive", notes = "删除目录时是否递归（false 且目录非空则报 SEMANTIC_NOT_EMPTY）")
    private Boolean recursive;

    @ApiModelProperty(value = "BaseMtime", notes = "删除文件时的乐观锁基准 mtime（epoch ms）；目录删除忽略此值")
    private Long baseMtime;
}
