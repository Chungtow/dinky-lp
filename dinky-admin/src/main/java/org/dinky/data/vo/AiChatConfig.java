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
 * 前端可用的 AI 配置状态。
 *
 * <p><b>NOTE:</b> 该结构<strong>不包含</strong> API Key，密钥仅在服务端使用。
 *
 * @since 2026/09/26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(value = "AiChatConfig", description = "AI Chat Config")
public class AiChatConfig {

    @ApiModelProperty(value = "是否启用 AI 能力")
    private Boolean enable;

    @ApiModelProperty(value = "模型名称")
    private String model;

    @ApiModelProperty(value = "模型服务地址")
    private String baseUrl;

    @ApiModelProperty(value = "是否已配置 API Key")
    private Boolean hasApiKey;

    /**
     * 是否开启 Craft 模式（阶段 2）。
     *
     * <p>默认 false：开启后 AI 可整块改写编辑器内容。前端据此决定是否渲染模式切换控件——
     * 未开启时不展示无功能的控件。
     */
    @ApiModelProperty(value = "是否开启 Craft 模式（AI 可整块改写编辑器内容）")
    private Boolean craftModeEnable;

    /**
     * 默认 LLM 实例 id（阶段 3）。
     *
     * <p>前端首次进入时选中它；请求不带 {@code profileId} 时后端也用它。
     */
    @ApiModelProperty(value = "默认 LLM 实例 id")
    private String defaultProfileId;

    /**
     * 可用 LLM 实例列表（阶段 3）。
     *
     * <p><b>脱敏</b>：每项只含 {@code hasApiKey} 布尔，<b>不含 apiKey 明文</b>——延续本类
     * 「不含密钥」的契约。
     */
    @ApiModelProperty(value = "可用 LLM 实例列表（不含密钥）")
    private List<AiChatProfile> profiles;

    /**
     * 是否开启 Skills 能力（阶段 4a）。
     *
     * <p>前端据此决定「输入框 {@code @} 候选里是否提供 skill」；关闭时行为与迭代前一致。
     */
    @ApiModelProperty(value = "是否开启 Skills 能力")
    private Boolean skillEnable;

    /** 实例的前端视图（阶段 3）：<b>不含 apiKey</b>，仅暴露 {@link #hasApiKey} 布尔 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ApiModel(value = "AiChatProfile", description = "LLM profile view without api key")
    public static class AiChatProfile {

        @ApiModelProperty(value = "实例 id")
        private String id;

        @ApiModelProperty(value = "展示名")
        private String name;

        @ApiModelProperty(value = "模型名称")
        private String model;

        @ApiModelProperty(value = "模型服务地址")
        private String baseUrl;

        @ApiModelProperty(value = "是否已配置 API Key（不返回明文）")
        private Boolean hasApiKey;

        /** 该实例是否支持工具调用；false 时前端提示「将退化为纯问答」（阶段 3） */
        @ApiModelProperty(value = "是否支持工具调用")
        private Boolean supportsTools;
    }
}
