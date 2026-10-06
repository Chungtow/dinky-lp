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

import org.dinky.data.model.Skill;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可见 skill / doc 的<b>轻量快照</b>（阶段 4b）。
 *
 * <p><b>为什么要有这个类</b>（缺口 G3 的解法）：AI 工具循环跑在<b>异步线程池</b>里，
 * 此时租户上下文（ThreadLocal）已丢失——工具内若按 id / name 反查数据库，会因租户过滤而查不到，
 * 或者更糟：<b>把"能否看见"交给了数据库运气</b>（模型完全可以编造一个名字）。
 *
 * <p>因此约定（4b 实施计划 §3.2.3）：
 * <ol>
 *   <li><b>请求线程</b>预先算出「当前用户可见的资产集合」，做成快照注入 {@code AiToolContext}；</li>
 *   <li>工具内<b>只消费、不解析</b>——按 name 在快照里查，查不到即<b>拒绝</b>（越权在工具入口就被挡住，不进确认）；</li>
 *   <li>快照是<b>一次对话的有效期</b>：对话中途新建的资产要到下一轮对话才可见（有意为之，避免权限漂移）。</li>
 * </ol>
 *
 * @since 2026/10/05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillBrief implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String description;

    /** skill / doc */
    private String assetType;

    /** 资源存储中的目录完整路径（供工具内拼相对路径用；<b>不会</b>回灌给模型） */
    private String dirFullName;

    public static SkillBrief of(Skill skill) {
        if (skill == null) {
            return null;
        }
        return SkillBrief.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .assetType(skill.getAssetType())
                .dirFullName(skill.getDirFullName())
                .build();
    }
}
