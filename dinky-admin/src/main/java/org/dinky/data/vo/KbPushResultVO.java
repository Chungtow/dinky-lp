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
 * 推送结果（批次 C）。
 *
 * <p>三个字段缺一不可，因为"推送"在 RAG 侧有<b>三种互不相同</b>的结果：
 * <ol>
 *   <li>{@code dedup=false}：新文档，{@code status=queued}，解析与 embedding 在后台跑，
 *       要轮询详情等 {@code ready}/{@code failed}；</li>
 *   <li>{@code dedup=true}：同内容已存在，<b>没有重复入库</b>，{@code collections} 是合并后的
 *       最终归属——用户最需要的恰恰是这一句，只提示"已存在"会让人以为归属变了；</li>
 *   <li>内容改过：会得到<b>新</b> {@code docId}，旧版本仍在库里（{@code supersede_result=none}），
 *       所以这里从不承诺"覆盖"。</li>
 * </ol>
 */
@Getter
@Setter
@ApiModel(value = "KbPushResultVO", description = "Result of pushing a document into a knowledge base")
public class KbPushResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "DocId", notes = "sha256(正文)；内容一变就是新 id")
    private String docId;

    @ApiModelProperty(value = "Dedup", notes = "true = 同内容已存在（未重复入库）")
    private boolean dedup;

    @ApiModelProperty(value = "Status", notes = "queued / parsing / embedding / ready / failed")
    private String status;

    @ApiModelProperty(value = "Collections", notes = "推送后该文档的最终归属（合并结果，只加不减）")
    private List<String> collections;

    @ApiModelProperty(value = "Message", notes = "可直接展示的一句话（幂等文案已按矩阵分档）")
    private String message;
}
