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

package org.dinky.ai.tools;

import org.dinky.ai.AiToolContext;
import org.dinky.ai.skill.SkillBrief;

import cn.hutool.core.collection.CollUtil;

/**
 * skill 类工具的共享辅助（阶段 4b）。
 *
 * <p>核心约定：<b>「AI 工具只消费、不解析」</b>——工具内不做数据库查询，全部基于请求线程预先注入的
 * {@link AiToolContext#getVisibleSkills()} 快照做<b>集合判定</b>。这同时解决两件事：
 * ① 异步线程丢失租户 / Sa-Token 上下文（查库会失败）；② 模型编造一个不可见的名字来试探越权
 * （快照里查不到 → 工具入口即拒绝，不进确认流程）。
 *
 * @since 2026/10/05
 */
final class SkillToolSupport {

    private SkillToolSupport() {}

    /** 在可见快照里按名称查找（不存在返回 null） */
    static SkillBrief find(AiToolContext context, String name) {
        if (context == null || CollUtil.isEmpty(context.getVisibleSkills())) {
            return null;
        }
        for (SkillBrief brief : context.getVisibleSkills()) {
            if (name.equals(brief.getName())) {
                return brief;
            }
        }
        return null;
    }

    /** 不可见时的统一错误文案（提示模型如何自纠，同时不泄漏"是否存在"的信息） */
    static String notVisibleMessage(String name) {
        return "未找到可见的 skill / 知识文档：" + name + "（可能不存在，或当前用户无权访问）。请先用 list_skills 确认准确名称，不要凭猜测重试。";
    }

    /** 当前可见资产名清单（用于错误提示里给出可选值，帮助模型自我纠正） */
    static String visibleNames(AiToolContext context) {
        if (context == null || CollUtil.isEmpty(context.getVisibleSkills())) {
            return "（当前无可见资产）";
        }
        StringBuilder sb = new StringBuilder();
        for (SkillBrief brief : context.getVisibleSkills()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(brief.getName());
        }
        return sb.toString();
    }
}
