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
 * 「kb-service 有、Dinky 未纳管」的知识库（批次 D，D-K11/D-K12）。
 *
 * <p><b>为什么会有孤儿库</b>：RAG 侧的 collection 不是"被创建"出来的，而是文档标签的
 * <b>扫描聚合</b>（{@code source:meta_scan}）——没有创建事件可订阅。外部系统（IMA 等）
 * 直推文档就会凭空多出一个库，Dinky 侧完全不知道。没有这个接口，管理员只能手工抄
 * {@code ima_kb_62blyRRR} 这类随机标识，抄错一位就是"纳管了但检不出东西"。
 *
 * <p><b>纳管时 {@code code} 必须原样沿用</b>：重编码会让 {@code filter.collection}
 * 与 kb-service 对不上（D-K12）。
 */
@Getter
@Setter
@ApiModel(value = "KbOrphanVO", description = "A collection existing in kb-service but not registered in Dinky")
public class KbOrphanVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Code", notes = "kb-service 的 collection 标识（纳管时必须原样沿用）")
    private String code;

    @ApiModelProperty(value = "Docs", notes = "文档数（用于判断值不值得纳管）")
    private long docs;

    @ApiModelProperty(value = "Chunks", notes = "切片数")
    private long chunks;

    @ApiModelProperty(value = "LastTs", notes = "最近入库时间（epoch 秒；0 = 未知）")
    private long lastTs;

    @ApiModelProperty(value = "SampleFilename", notes = "库内随手一篇的文件名，帮管理员判断这是谁的数据")
    private String sampleFilename;
}
