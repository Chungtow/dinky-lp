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

package org.dinky.ai.kb;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库「轻量摘要」（知识库 {@code @kb} 接入 v1.0）。
 *
 * <p><b>为什么不是整张 {@code KbCollection} 实体</b>：本对象会随 {@code AiToolContext} 进入
 * <b>异步工具线程</b>，只需三重信息即可完成全部判定与渲染——编码（下发 {@code filter.collection}）、
 * 名称（可见时的展示与错误提示）、说明（{@code @} 候选与注入的范围声明）。
 * 多带属主 / 租户 / 时间戳只会扩大快照的内存与泄漏面，没有任何用处。
 *
 * <p>设计对齐 {@code SkillBrief}：<b>快照只放判定所需的最小集</b>，工具内不再回查数据库。
 *
 * @since 2026/10/27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbCollectionBrief implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 知识库编码（{@code c_} + 8 位小写 hex）。
     *
     * <p>这是唯一会写进 kb-service 请求体的标识（{@code filter.collection}）；显示名不参与接口。
     */
    private String code;

    /** 显示名（可改，仅用于界面与提示） */
    private String name;

    /** 一句话说明（{@code @} 候选展示与范围声明用），可为空 */
    private String description;
}
