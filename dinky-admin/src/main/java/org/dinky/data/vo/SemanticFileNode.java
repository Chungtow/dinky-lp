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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义项目文件树节点（相对项目根的路径）。
 *
 * <p>{@code readOnly=true} 的节点在前端置灰：{@code meta.json}（由 JupyterLab 推送维护）与
 * {@code target/}（dbt 产物）。
 */
@Getter
@Setter
@ApiModel(value = "SemanticFileNode", description = "File tree node of a dbt semantic project")
public class SemanticFileNode implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Path", notes = "相对项目根的路径，如 metrics/semantic_models.yml")
    private String path;

    /**
     * 是否目录。
     *
     * <p>刻意关闭 Lombok 的 isDir()/setDir() 并手写 getIsDir()/setIsDir()：Jackson 对 boolean
     * 的 {@code isXxx()} 会把属性名剥成 {@code xxx}，导致 JSON 变成 {@code dir}，与前端约定的
     * {@code isDir} 不一致（页面里会永远当成文件）。
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ApiModelProperty(value = "IsDir", notes = "是否目录")
    private boolean isDir;

    public boolean getIsDir() {
        return isDir;
    }

    public void setIsDir(boolean isDir) {
        this.isDir = isDir;
    }

    @ApiModelProperty(value = "Size", notes = "字节数（目录为 0）")
    private long size;

    @ApiModelProperty(value = "ReadOnly", notes = "只读（meta.json / target 下）")
    private boolean readOnly;

    @ApiModelProperty(value = "LastModified", notes = "最近修改时间（epoch ms），保存时用作乐观锁基准")
    private long lastModified;
}
