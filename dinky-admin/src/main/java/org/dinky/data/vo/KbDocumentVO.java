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
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库文档条目（批次 B：左侧栏「知识库」tab 的文档列表）。
 *
 * <p><b>为什么要有 {@code openable}</b>：首期只支持 Markdown / 代码类文本的只读查看，
 * PDF / DOCX <b>不预览</b>（实施计划 §二「不做」）。判定必须放在服务端——放前端会让
 * "能不能打开"变成两套口径（前端按扩展名、后端按 content-type），一升级就漂移。
 *
 * <p>{@code updatedAt} 取 kb-service 的 <b>epoch 秒</b>（实测字段类型），不转成字符串：
 * 时区与格式由前端自己决定，后端转一次就多一处时区 bug。
 */
@Getter
@Setter
@ApiModel(value = "KbDocumentVO", description = "A document inside a knowledge base collection")
public class KbDocumentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "DocId", notes = "sha256(正文)，kb-service 全局唯一")
    private String docId;

    @ApiModelProperty(value = "Filename", notes = "入库时的文件名（带后缀，决定解析方式）")
    private String filename;

    @ApiModelProperty(value = "Size", notes = "字节数")
    private Long size;

    @ApiModelProperty(value = "ContentType", notes = "入库时声明的 MIME")
    private String contentType;

    @ApiModelProperty(value = "Status", notes = "queued / parsing / embedding / ready / failed")
    private String status;

    @ApiModelProperty(value = "Chunks", notes = "切片数")
    private Integer chunks;

    @ApiModelProperty(value = "UpdatedAt", notes = "最近更新时间（epoch 秒）")
    private Long updatedAt;

    @ApiModelProperty(value = "Collections", notes = "该文档所属的全部库编码（可能多归属）")
    private List<String> collections;

    @ApiModelProperty(value = "Error", notes = "解析失败原因（status=failed 时有值）")
    private String error;

    @ApiModelProperty(value = "Openable", notes = "是否可在编辑区只读打开（仅 Markdown / 代码类文本）")
    private boolean openable;
}
