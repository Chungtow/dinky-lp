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
 * {@code @} 候选的列式响应（{@code @} 候选升级 C1）。
 *
 * <p><b>为什么是列式而不是对象数组</b>：候选总量按万级设计（skill/doc/semantic/kb 合计），
 * {@code [{type,code,title}]} 在 JS 里是 N 个对象 + N 组重复的键字符串，
 * 常驻内存约为列式（{@code types[]/codes[]/titles[]}）的 2 倍以上（评估 §3.2）。
 * 列式下前端只做三次下标访问，代价是下标必须严格对齐——由
 * {@code MentionCandidateService} 单点保证。
 *
 * <p><b>ETag 语义</b>：{@code etag} 由「条目数 + 每条 type/code 的内容哈希」算出，
 * 客户端带 {@code If-None-Match} 复用同值时服务端直接回 304，
 * 因此"打开浮层"只在数据真的变了才重新传输。
 *
 * @since 2026/10/30
 */
@Getter
@Setter
@ApiModel(value = "MentionCandidateVO", description = "AI Chat @ candidates (columnar)")
public class MentionCandidateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 内容指纹：{@code <hex>-<count>}，同时用作 HTTP ETag */
    @ApiModelProperty(value = "ETag", notes = "内容指纹；客户端回传 If-None-Match 可命中 304")
    private String etag;

    /** 条目数（= 各列长度） */
    @ApiModelProperty(value = "Count")
    private int count;

    /** {@code prefetch}（无 q 的全量预取）/ {@code search}（带 q 的服务端搜索） */
    @ApiModelProperty(value = "Mode", notes = "prefetch / search")
    private String mode;

    @ApiModelProperty(value = "Types", notes = "协议值，与 codes/titles 下标对齐")
    private List<String> types;

    @ApiModelProperty(value = "Codes", notes = "拼进 @ 句柄的稳定标识")
    private List<String> codes;

    @ApiModelProperty(value = "Titles", notes = "展示名")
    private List<String> titles;

    /**
     * 副标题列（与 {@code codes} 下标对齐，允许 {@code null} 元素）。
     *
     * <p><b>为什么原本不下发、现在又加了</b>：最初为了把常驻内存压到 6~8MB，副标题改成
     * "选中后再拉"（{@code /mention/detail}）。但实测发现候选面板里<b>没有副标题就无法区分
     * 同名/编码式候选</b>——典型是知识库：{@code name} 必须是库编码（{@code ima_kb_62blyRRR}，
     * 系统生成、可安全写进 {@code filter.collection}），而用户认的是库名
     * "Traccar 运维教程（IMA）"。只显示编码时，多个名字带"运维"的库完全无从选择。
     *
     * <p>因此改为<b>预取时一并下发轻量副标题</b>（kb=库名、skill/doc=一句话描述、
     * semantic=版本与资产数），并设条目阈值 {@code MAX_SUB_ITEMS}：超过阈值时整列留空，
     * 回到"选中后再拉"的老路——保证内存预算在条目膨胀时不会失守。
     */
    @ApiModelProperty(value = "Subtitles", notes = "副标题；超出阈值时为空")
    private List<String> subs;

    public static final String MODE_PREFETCH = "prefetch";

    public static final String MODE_SEARCH = "search";
}
