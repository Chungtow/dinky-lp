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
import java.util.ArrayList;
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Skill 正文 {@code @} 句柄校验结果（保存后校验 / E4）。
 *
 * <p><b>为什么不塞进 {@code /api/skill/save} 的响应</b>：该接口返回 {@code Result<Skill>}，
 * 加 warnings 属于改契约，且 RegCenter 三处保存入口都要用同一份校验结果。
 *
 * <p><b>{@code syntax} 为什么必须下发</b>：反引号包裹（{@code `@type/code`}，explicit）与裸写
 * （{@code @type/code}，bare）校验强度不同——bare 只提示不告警（存量 skill 一打开就满屏黄线是不可接受的）。
 * 判定谁说了算在后端，前端只按 {@code syntax} 决定"要不要渲染波浪线"。
 *
 * @since 2026/10/30
 */
@Getter
@Setter
@ApiModel(value = "SkillHandleValidationVO", description = "Skill content @ handle validation result")
public class SkillHandleValidationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Summary", notes = "按状态汇总的计数")
    private Summary summary = new Summary();

    @ApiModelProperty(value = "Handles", notes = "逐句柄结果（按出现顺序）")
    private List<Handle> handles = new ArrayList<>();

    @ApiModelProperty(value = "Content Hash", notes = "入参 contentHash 的原样回显；前端据此判断结果是否已过期（用户在校验返回前继续编辑）")
    private String contentHash;

    /** 汇总计数（{@code total = ok + notFound + denied + unknownType}）。 */
    @Getter
    @Setter
    @ApiModel(value = "SkillHandleValidationSummary", description = "Handle validation summary")
    public static class Summary implements Serializable {

        private static final long serialVersionUID = 1L;

        @ApiModelProperty("句柄总数")
        private int total;

        @ApiModelProperty("解析成功")
        private int ok;

        @ApiModelProperty("资源不存在")
        private int notFound;

        @ApiModelProperty("无访问权限")
        private int denied;

        @ApiModelProperty("未知的引用类型")
        private int unknownType;

        @ApiModelProperty("裸写（未用反引号包裹）的数量——只给 info 提示，不产生警告")
        private int bareOnly;
    }

    /** 单个句柄的校验结果。 */
    @Getter
    @Setter
    @ApiModel(value = "SkillHandleValidationHandle", description = "Single handle validation result")
    public static class Handle implements Serializable {

        private static final long serialVersionUID = 1L;

        @ApiModelProperty(value = "Raw", notes = "原文片段（explicit 含反引号）")
        private String raw;

        @ApiModelProperty(value = "Syntax", notes = "explicit（反引号） | bare（裸写）")
        private String syntax;

        @ApiModelProperty(value = "Type", notes = "协议值：source/table/column/skill/doc/semantic/kb/topic/env")
        private String type;

        @ApiModelProperty(value = "Code", notes = "稳定寻址（@ 之后的那一段）")
        private String code;

        @ApiModelProperty(value = "Line", notes = "1-based 行号，指向 @ 字符")
        private int line;

        @ApiModelProperty(value = "Column", notes = "1-based 列号，指向 @ 字符（不含反引号）")
        private int column;

        @ApiModelProperty(value = "Status", notes = "ok | not_found | denied | unknown_type")
        private String status;

        @ApiModelProperty(value = "Title", notes = "解析到的资源名（悬停 / 汇总展示用）")
        private String title;

        @ApiModelProperty(value = "Message", notes = "人类可读说明；正常为 null")
        private String message;
    }
}
