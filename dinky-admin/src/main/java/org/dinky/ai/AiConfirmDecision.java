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

package org.dinky.ai;

/**
 * 写操作确认的三种结局（2026-10-29 用户口径：AI 的修改"放进编辑区即可，不要自动落盘"）。
 *
 * <ul>
 *   <li>{@link #REJECT}：用户拒绝 / 取消 / 超时——工具不得做任何写入；</li>
 *   <li>{@link #APPROVE}：用户确认<b>落盘</b>——工具照常执行（SQL 执行、skill 删除等只能走这条）；</li>
 *   <li>{@link #APPLY_ONLY}：用户确认<b>仅应用到编辑区</b>——前端已把新正文塞进编辑器（未保存），
 *       工具必须<b>跳过持久化</b>并把这件事如实告诉模型，否则用户会看到"版本号自己涨了、
 *       编辑器却没变"（批次 F UAT 2026-10-29 的原缺陷）。</li>
 * </ul>
 *
 * <p>只有"能表达成一段正文"的写工具（{@code write_skill_file}）支持 {@code APPLY_ONLY}；
 * SQL 执行、删除类操作即使收到该值也按 {@link #REJECT} 处理（见 {@code WriteSkillFileTool} 之外的调用方，
 * 它们只认 {@code == APPROVE}）。
 */
public enum AiConfirmDecision {
    REJECT,
    APPROVE,
    APPLY_ONLY;

    /** 是否为"允许落盘"的确认（SQL / 删除等工具的唯一通过条件） */
    public boolean isApproved() {
        return this == APPROVE;
    }
}
