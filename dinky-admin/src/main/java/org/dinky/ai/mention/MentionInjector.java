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

package org.dinky.ai.mention;

/**
 * {@code @} 引用注入器（知识库 {@code @kb} 接入 v1.0，B0）。
 *
 * <p><b>为什么要有这层抽象</b>：{@code AiChatServiceImpl#buildMentionContext} 原本是一条
 * 约 70 行的 {@code if/else} 链，每加一类 {@code @} 引用都要动那个方法。类型已到 8 个，
 * 若继续这个写法，「一处改动漏掉另一处」的概率随类型数线性上升。
 * 本接口把"某一类引用怎么注入"抽成独立实现，由 Spring 收集成注册表。
 *
 * <p><b>兼容策略（零行为变更）</b>：注册表是<b>前置</b>的——只有注册了实现的类型才走注册表，
 * 既有 8 类仍走原 {@code if/else}。因此本批新增 {@code @kb} 不需要改动任何既有分支，
 * 回归风险为零（实施计划 U-19 的判据即"产物逐字节一致"）。
 *
 * <p>⚠️ 实现必须<b>无状态</b>且<b>不抛异常</b>：注入阶段任何异常都不能阻断对话，
 * 内部异常要自己吞掉并转成一行可读说明（与 {@code appendSemanticDetail} 的处理一致）。
 *
 * @since 2026/10/27
 */
public interface MentionInjector {

    /**
     * 本注入器负责的 {@code MentionType} 常量值（如 {@link MentionType#KB}）。
     *
     * <p>必须与 {@code MentionType} 中已定义的常量完全一致；大小写不敏感匹配。
     */
    String type();

    /**
     * 产出一段要拼进系统提示词的上下文。
     *
     * @param context 注入上下文（含请求线程已解析好的可见集快照）
     * @return 要拼接的文本；返回 {@code null} 或空串表示"这一类引用不产出任何内容"
     */
    String inject(MentionInjectContext context);
}
