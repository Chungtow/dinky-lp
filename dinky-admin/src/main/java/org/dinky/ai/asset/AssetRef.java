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

package org.dinky.ai.asset;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一候选条目（{@code @} 候选升级 C1 的「统一契约」）。
 *
 * <p><b>为什么所有类型共用一个结构</b>：改造前每类候选都是各自页面的 DTO（{@code Skill}、
 * {@code SemanticPublishedProjectVO}、{@code KbCollectionBrief}…），前端要为每类写一段
 * 「取数 + 过滤 + 排序」。统一成 {@code {type, code, title}} 之后，前端只需维护<b>一个</b>
 * 内存数组与<b>一个</b>匹配函数，类型退化为条目的一个字段——这正是"任意字符串跨类型匹配"
 * 的实现前提（评估 §1.2：跨类型匹配靠候选契约统一，不靠底层存储统一）。
 *
 * <p><b>为什么预取不下发 {@code subtitle}</b>：2 万条量级下副标题会让载荷翻倍
 * （评估 §3.2 测算：不下发可把常驻内存从 13~18MB 压到 6~8MB）。副标题在用户<b>选中</b>后
 * 单独走 {@code /mention/detail} 拉取。
 *
 * @since 2026/10/30
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 协议值，取自 {@link AssetType#getCode()} */
    private String type;

    /**
     * 稳定寻址（拼进 {@code @} 句柄的那一段）：skill / doc 为名字，kb 为库编码，
     * semantic 为项目名，source 为 {@code ds/db/tbl} 路径。
     */
    private String code;

    /** 展示名（前端高亮匹配用，可与 {@code code} 不同——如 kb 的显示名） */
    private String title;

    /** 副标题 / 摘要（预取路径<b>恒为 null</b>，仅详情接口返回） */
    private String subtitle;

    public static AssetRef of(AssetType type, String code, String title) {
        return AssetRef.builder().type(type.getCode()).code(code).title(title).build();
    }
}
