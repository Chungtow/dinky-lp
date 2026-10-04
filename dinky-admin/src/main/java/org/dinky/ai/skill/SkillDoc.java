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

import lombok.Data;

/**
 * {@code SKILL.md} 的解析结果（阶段 4a）。
 *
 * <p>{@code name} / {@code description} 来自 YAML frontmatter（用于清单注入与 {@code @} 候选）；
 * {@code body} 是 frontmatter 之后的 Markdown 正文。
 *
 * @since 2026/10/05
 */
@Data
public class SkillDoc {

    /** skill 名（frontmatter 的 name） */
    private String name;

    /** 一句话说明（frontmatter 的 description） */
    private String description;

    /** 正文（frontmatter 之后的内容） */
    private String body;
}
