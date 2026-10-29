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
 * 语义文件内容（含乐观锁基准 mtime）。
 *
 * <p>{@code modifiedAt} 必须随保存请求原样回传（{@code baseMtime}）：HDFS mtime 变了即为
 * 他人改动，后端拒绝覆盖（前端提示"刷新后重新编辑"）。
 */
@Getter
@Setter
@ApiModel(value = "SemanticFileContentVO", description = "Content of a semantic file on HDFS")
public class SemanticFileContentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Path", notes = "相对项目根的路径")
    private String path;

    @ApiModelProperty(value = "Content", notes = "文件正文（UTF-8 文本）")
    private String content;

    @ApiModelProperty(value = "Size", notes = "字节数")
    private long size;

    @ApiModelProperty(value = "ModifiedAt", notes = "HDFS mtime（epoch ms），保存时回传为 baseMtime")
    private long modifiedAt;
}
