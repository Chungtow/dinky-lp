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
 * 知识库文档原文（批次 B 只读查看器）。
 *
 * <p><b>永远只读</b>：知识库的内容真相源在 kb-service，Dinky 侧改一个字都会与
 * {@code doc_id = sha256(正文)} 失配。要改内容请回原 tab 改完再推送（实施计划 §二「不做」）。
 *
 * <p>{@code collections} 一并返回，是为了在标题栏显示"这条文档归属哪几个库"——
 * 多归属（A 侧 dedup 合并策略）是用户最容易误解的一点。
 */
@Getter
@Setter
@ApiModel(value = "KbDocumentContentVO", description = "Raw text of a knowledge base document (read only)")
public class KbDocumentContentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "DocId", notes = "sha256(正文)")
    private String docId;

    @ApiModelProperty(value = "Filename", notes = "文件名（来自 Content-Disposition）")
    private String filename;

    @ApiModelProperty(value = "ContentType", notes = "MIME，前端据此决定高亮语言")
    private String contentType;

    @ApiModelProperty(value = "Content", notes = "正文（UTF-8 文本）")
    private String content;

    @ApiModelProperty(value = "Size", notes = "正文字符数（不是字节数，前端用于展示体量）")
    private int size;

    @ApiModelProperty(value = "Collections", notes = "所属库编码")
    private List<String> collections;
}
