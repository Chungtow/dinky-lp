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
import org.dinky.ai.kb.KbCollectionBrief;

import java.util.ArrayList;
import java.util.List;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 知识库类工具的共享辅助（知识库 {@code @kb} 接入 v1.0）。
 *
 * <p>与 {@link SkillToolSupport} 同一约定：<b>「AI 工具只消费、不解析」</b>——工具内不做数据库查询，
 * 全部基于请求线程预先注入的 {@link AiToolContext#getVisibleCollections()} 快照做<b>集合判定</b>。
 *
 * <p>这解决两件事：① 工具循环跑在异步线程池，租户 / Sa-Token 上下文已丢失，查库会失败；
 * ② 模型可能编造一个不可见的库编码来试探越权——快照里查不到即在工具入口拒绝，
 * 不会把越权编码转发给 kb-service（后者不认人，`filter` 里放什么就搜什么）。
 *
 * @since 2026/10/27
 */
final class KbToolSupport {

    private KbToolSupport() {}

    /** 在可见快照里按编码查找（不存在返回 null） */
    static KbCollectionBrief find(AiToolContext context, String code) {
        if (context == null || CollUtil.isEmpty(context.getVisibleCollections()) || StrUtil.isBlank(code)) {
            return null;
        }
        String target = StrUtil.trim(code);
        for (KbCollectionBrief brief : context.getVisibleCollections()) {
            if (target.equals(brief.getCode())) {
                return brief;
            }
        }
        return null;
    }

    /** 不可见时的统一错误文案（提示模型如何自纠，同时不泄漏"是否存在"的信息） */
    static String notVisibleMessage(String code) {
        return "未找到可见的知识库：" + StrUtil.nullToEmpty(code) + "（可能不存在，或当前用户无权访问/已停用）。" + "不要再重试同一个编码，也不要猜测其他编码。";
    }

    /** 当前可见库的编码清单（缺省范围用；模型给的编码不合法时用于纠正） */
    static List<String> visibleCodes(AiToolContext context) {
        List<String> codes = new ArrayList<>();
        if (context == null || CollUtil.isEmpty(context.getVisibleCollections())) {
            return codes;
        }
        for (KbCollectionBrief brief : context.getVisibleCollections()) {
            if (StrUtil.isNotBlank(brief.getCode())) {
                codes.add(brief.getCode());
            }
        }
        return codes;
    }

    /** 可见库的「名称(编码)」清单，用于错误提示里给出可选值，帮助模型自我纠正 */
    static String visibleLabels(AiToolContext context) {
        if (context == null || CollUtil.isEmpty(context.getVisibleCollections())) {
            return "（当前无可见知识库）";
        }
        StringBuilder sb = new StringBuilder();
        for (KbCollectionBrief brief : context.getVisibleCollections()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(StrUtil.nullToEmpty(brief.getName()))
                    .append("(")
                    .append(brief.getCode())
                    .append(")");
        }
        return sb.toString();
    }
}
