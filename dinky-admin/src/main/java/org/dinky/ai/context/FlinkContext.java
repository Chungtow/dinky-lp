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

package org.dinky.ai.context;

import java.util.Collections;
import java.util.List;

import lombok.Getter;

/**
 * FlinkSQL 上下文装配结果：**文本 + 段落清单**。
 *
 * <p><b>为什么不再只返回 String</b>：以前只能把组装好的文本塞进 prompt，出了问题时
 * （模型编造 connector、编造连接串、写错参数位置）无法区分「没注入」与「注入了但模型没遵守」，
 * 只能靠让模型"原样贴出上下文"反推，既费 token 又会被模型改写。
 *
 * <p>现在装配期就把每段（L1 能力 / L2 默认 / 链路约定 / 规则 / 协议 / 外部资源 / 作业绑定）
 * 的字符数与降级原因记录下来，配合 {@code GET /api/aiChat/context} 可零 token 自证，
 * 也可在对话开始时落一行日志，让"这次到底给了模型什么"可审计。
 *
 * @since 2026/11/03
 */
@Getter
public class FlinkContext {

    /** 组装好的上下文文本（超预算时已被截断，见 {@link #truncated}） */
    private final String text;

    /** 是否绑定了具体作业（false 表示"新建 / 未保存"tab：只有框架事实，没有作业细节） */
    private final boolean taskBound;

    /** 是否因 {@code llm.flinkContextMaxChars} 预算被截断 */
    private final boolean truncated;

    /** 段落清单（按注入顺序） */
    private final List<Section> sections;

    public FlinkContext(String text, boolean taskBound, boolean truncated, List<Section> sections) {
        this.text = text == null ? "" : text;
        this.taskBound = taskBound;
        this.truncated = truncated;
        this.sections = sections == null ? Collections.emptyList() : sections;
    }

    /** 空结果：非 FlinkSQL 方言（对既有 Sql / SparkSQL 链路零影响） */
    public static FlinkContext empty() {
        return new FlinkContext("", false, false, Collections.emptyList());
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** 单段装配结果 */
    @Getter
    public static class Section {

        /** 稳定标识（job / capabilities / catalog / resources / defaults / profile / rules / protocol / review / registry） */
        private final String id;

        /** 展示名（给 UAT / 日志看） */
        private final String title;

        /** 该段字符数（0 表示装配后为空） */
        private final int chars;

        /** 该段装配异常时的原因（null 表示正常） */
        private final String degraded;

        public Section(String id, String title, int chars, String degraded) {
            this.id = id;
            this.title = title;
            this.chars = chars;
            this.degraded = degraded;
        }

        public boolean isDegraded() {
            return degraded != null;
        }
    }
}
