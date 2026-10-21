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

/** 语义文件保存请求 */
@Getter
@Setter
@ApiModel(value = "SemanticFileSaveDTO", description = "Save a semantic file back to HDFS")
public class SemanticFileSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Project", notes = "项目名（HDFS 一级目录）", required = true)
    private String project;

    @ApiModelProperty(value = "Path", notes = "相对项目根的路径", required = true)
    private String path;

    @ApiModelProperty(value = "Content", notes = "文件正文", required = true)
    private String content;

    @ApiModelProperty(value = "BaseMtime", notes = "读文件时拿到的 HDFS mtime（epoch ms）；>0 时用于乐观锁，冲突则拒绝保存")
    private Long baseMtime;
}
