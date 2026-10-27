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

import org.dinky.ai.context.RequestSnapshot;
import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.dto.AiChatMention;
import org.dinky.data.dto.AiChatRequest;

import java.util.Collections;
import java.util.List;

import lombok.Builder;
import lombok.Getter;

/**
 * {@link MentionInjector} 的输入上下文。
 *
 * <p><b>为什么可见集是快照而不是"让注入器自己查"</b>：注入发生在请求线程，但工具循环跑在
 * 异步线程池，那里 Sa-Token 与租户上下文都已丢失（见 {@code AiToolContext} 类头注释）。
 * 为了"注入"与"工具执行"看到<b>完全相同</b>的可见范围，可见集在请求线程只算一次，
 * 两处共用同一份快照——这也是 4b 的 {@code visibleSkills} 踩过坑后定下的范式。
 *
 * @since 2026/10/27
 */
@Getter
@Builder
public class MentionInjectContext {

    /**
     * <b>本次正在处理的单个引用</b>（注册表按 mention 逐个分派）。
     *
     * <p>注入器只处理"这一个"：{@code type} 已由调用方用于选到本注入器，
     * 实现只需读 {@code name}（对 {@code @kb} 而言即库编码）及其他可选字段。
     */
    private final AiChatMention mention;

    /** 本次请求（注入器一般只用其上的业务参数，不重新解析消息文本） */
    private final AiChatRequest request;

    /** 当前面板所选数据源（FlinkSQL 对话为 null） */
    private final Integer databaseId;

    /** 当前 schema（用户未指定时为空） */
    private final String schemaName;

    /** 请求线程预解析的上下文快照（数据源索引 + 命名名册） */
    private final RequestSnapshot snapshot;

    /**
     * 当前用户可见的知识库快照（{@code @kb} 用）。
     *
     * <p>默认为<b>空列表</b>而非 {@code null}：注入器可直接遍历，无需判空。
     */
    @Builder.Default
    private final List<KbCollectionBrief> visibleCollections = Collections.emptyList();
}
