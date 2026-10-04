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

package org.dinky.ai.skill;

/**
 * 知识资产渲染器（阶段 4a）。
 *
 * <p>本接口是<b>为未来语义层预留的「缝」</b>（总体计划 §4.8.3-③）：注入策略按 {@code asset_type} 分派，
 * 本期只有 Markdown（skill）一个实现；未来语义层若需要结构化渲染（YAML → 按问题摘取实体 / 指标），
 * <b>新增一个实现即可，不必改注入链路</b>。
 *
 * @since 2026/10/05
 */
public interface SkillRenderer {

    /** 支持的资产类型（与 {@code dinky_skill.asset_type} 对应） */
    String assetType();

    /**
     * 把 skill 文档渲染为注入模型的文本。
     *
     * @param doc skill 文档（name / description / body）
     * @param maxChars 字符预算上限（&le;0 表示不限制）
     * @return 渲染结果（含注入边界声明与截断提示）
     */
    String render(SkillDoc doc, int maxChars);
}
