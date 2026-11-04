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

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FlinkSQL 上下文预览（{@code GET /api/aiChat/context}）。
 *
 * <p><b>用途是"自证"而不是"使用"</b>：它把本轮真正会注入给模型的上下文原样吐出来，配合：
 *
 * <ul>
 *   <li>{@code sections} —— 注入了哪几段、每段多少字符、有没有降级；
 *   <li>{@code checks} —— 7 段自检结论（缺哪段、缺了会有什么后果）；
 *   <li>{@code allPass} —— 一句话判据，UAT 直接断言它。
 * </ul>
 *
 * <p>这样"上下文是不是给到位了"不再需要让模型复述（既费 token 又会被模型改写）。
 *
 * @since 2026/11/03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "AiChatContextPreview", description = "FlinkSQL 上下文预览（只读）")
public class AiChatContextPreview {

    @ApiModelProperty(value = "请求携带的作业 id（可为空：新建 / 未保存的 tab）")
    private Integer taskId;

    @ApiModelProperty(value = "请求携带的方言；非 FlinkSql 返回空上下文")
    private String dialect;

    @ApiModelProperty(value = "是否真正绑定了作业（false = 只有框架事实）")
    private Boolean taskBound;

    @ApiModelProperty(value = "上下文总字符数（受 llm.flinkContextMaxChars 预算约束）")
    private Integer chars;

    @ApiModelProperty(value = "是否因预算被截断")
    private Boolean truncated;

    @ApiModelProperty(value = "段落清单（按注入顺序）")
    private List<Section> sections;

    @ApiModelProperty(value = "7 段自检结论")
    private List<Check> checks;

    @ApiModelProperty(value = "7 段是否全绿（UAT 判据）")
    private Boolean allPass;

    @ApiModelProperty(value = "注入的上下文原文")
    private String text;

    /** 单段信息 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ApiModel("AiChatContextPreview.Section")
    public static class Section {

        @ApiModelProperty(value = "段标识")
        private String id;

        @ApiModelProperty(value = "段标题")
        private String title;

        @ApiModelProperty(value = "字符数")
        private Integer chars;

        @ApiModelProperty(value = "装配异常原因（正常为 null）")
        private String degraded;
    }

    /** 单条自检结论 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ApiModel("AiChatContextPreview.Check")
    public static class Check {

        @ApiModelProperty(value = "检查项标识")
        private String id;

        @ApiModelProperty(value = "检查项名称")
        private String item;

        @ApiModelProperty(value = "pass / fail")
        private String status;

        @ApiModelProperty(value = "fail 时说明缺什么")
        private String evidence;
    }
}
