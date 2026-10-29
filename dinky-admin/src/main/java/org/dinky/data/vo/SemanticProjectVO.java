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
 * 语义项目（HDFS {@code /semantic/<name>}）。
 *
 * <p>项目本身就是 HDFS 目录，不存在"项目表"——列表每项由目录元数据即时算出。
 */
@Getter
@Setter
@ApiModel(value = "SemanticProjectVO", description = "dbt semantic project on HDFS")
public class SemanticProjectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Name", notes = "项目名（= HDFS 一级目录名）")
    private String name;

    @ApiModelProperty(value = "FileCount", notes = "项目内条目数（含目录，最多 300）")
    private int fileCount;

    @ApiModelProperty(value = "LastModified", notes = "目录最近修改时间（epoch ms）")
    private long lastModified;
}
