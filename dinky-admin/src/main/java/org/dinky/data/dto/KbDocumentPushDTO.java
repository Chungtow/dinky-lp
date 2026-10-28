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
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 推送到知识库的请求（批次 C）。
 *
 * <p><b>为什么 Dinky 侧边界用 JSON 而不是 multipart</b>：先行记录 §10.3 描述的是
 * **Dinky → kb-service** 那一跳（那里确实必须 multipart）；前端到 Dinky 这一跳传的是
 * 编辑器里的字符串，没有任何二进制，用 multipart 只会把"文件名/编码/边界"这些
 * 与业务无关的细节漏到前端。所以这里收 JSON，由 {@code KbClient} 拼 multipart。
 *
 * <p><b>前端不许传的字段</b>：{@code provider}（恒 {@code dinky}）与 {@code path}（由
 * {@link #source} 映射）——来源标记是审计口径，必须由服务端决定，否则前端能伪造成
 * "这条是从语雀导进来的"（A 侧 {@code source} 字段的语义就被污染了）。
 */
@Getter
@Setter
@ApiModel(value = "KbDocumentPushDTO", description = "Push a text into a knowledge base collection")
public class KbDocumentPushDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Filename", notes = "必须带后缀：后缀决定解析方式（.md 切标题，.sql/.yaml 等按纯文本切分）", required = true)
    private String filename;

    @ApiModelProperty(value = "Content", notes = "正文（UTF-8 文本）", required = true)
    private String content;

    @ApiModelProperty(value = "Collections", notes = "目标库编码；<b>至少一个</b>，且每个都必须有写权限", required = true)
    private List<String> collections;

    @ApiModelProperty(value = "Source", notes = "来源：semantic / skill / resource / editor；服务端据此映射 path，前端不可自定义 path")
    private String source;

    @ApiModelProperty(value = "ExternalId", notes = "Dinky 侧业务主键（如 skill id），便于回溯")
    private String externalId;
}
