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

/**
 * 四段式输出协议（M1-D）的唯一文本来源。
 *
 * <p><b>为什么要把文本集中到一处</b>：同一份协议现在有两个消费方——
 *
 * <ol>
 *   <li>上下文注入（{@code FlinkContextProvider#appendProtocol}）——正常对话时让模型按协议产出；
 *   <li>围栏补发（{@code AiChatServiceImpl#FENCE_REPAIR_PROMPT}）——模型没按协议产出时纠正一次。
 * </ol>
 *
 * <p>过去补发提示只写了「块内是协议规定的 JSON（sql / customConfig / ddl / precheck 四段）」，
 * <b>没有给字段骨架</b>，一旦该轮上下文里本来就没有协议（例：新建作业 tab 无 taskId，上下文整块为空），
 * 模型只能凭空构造，只能回答「协议不在我的指令里」。2026-11-03 UAT 实测就是这样二次失败的。
 * 因此把协议原文做成常量，补发提示里**整段内联**，让补发不依赖历史上下文。
 *
 * @since 2026/11/03
 */
public final class FlinkJobProtocol {

    /** 围栏语言标识（前端按这个 tag 解析作业卡片，后端按它判断"有没有给围栏"） */
    public static final String FENCE_TAG = "flinksql-job";

    /** 围栏 JSON 的字段骨架（示例值只用于示意类型，不是业务默认值） */
    public static final String JSON_SKELETON = "```" + FENCE_TAG + "\n"
            + "{\"flinksqlJob\":{\"name\":\"作业名\",\"sql\":\"完整 FlinkSQL\","
            + "\"customConfig\":[{\"key\":\"execution.checkpointing.min-pause\",\"value\":\"10s\"}],"
            + "\"ddl\":[{\"engine\":\"clickhouse\",\"database\":\"traccar\",\"table\":\"dws_x\","
            + "\"statement\":\"CREATE TABLE ...\"}],"
            + "\"precheck\":[{\"item\":\"sink 表已存在\",\"how\":\"describe_tables\",\"status\":\"pass\"}]}}\n"
            + "```\n";

    /**
     * 输出协议正文（注入上下文的那一段，语义与 S8 之前的硬编码常量等价）。
     *
     * <p>写成"必须"而不是"建议"：不给出围栏时，前端只能渲染 Markdown，「写入草稿」就无从谈起
     * （用户得手工复制四段并逐段粘贴）。
     */
    public static final String PROMPT = "\n### 输出协议（给出新作业或改动作业时必须遵守）\n"
            + "- 除 Markdown 说明外，必须**再输出一个 ```flinksql-job 围栏**，围栏内是 JSON：\n"
            + JSON_SKELETON
            + "- `sql` 必须**完整可执行**（含 SET 头与 INSERT INTO），不要写省略号或注释占位\n"
            + "- `customConfig` 只放「只能放自定义配置」的参数；能走 SET 的不要重复\n"
            + "- `ddl` 放目标库建表语句；目标表已存在时给空数组 `[]`\n"
            + "- `precheck` 每项结论只有 pass / unknown / fail；**没验证过就写 unknown**，不要写 pass\n"
            + "- 给出围栏前后应先用 `validate_task_draft` 自检，存在 error 级违规时先修正再输出\n";

    /**
     * 围栏补发提示词（<b>自包含</b>：内联协议原文，不依赖该轮上下文里是否有协议段落）。
     *
     * <p>只要求"再输出一次围栏"而不是"重答一遍"：补发内容会追加到原回复末尾（前端按同一条
     * assistant 消息解析），若模型重复整段 SQL，用户会看到两份几乎一样的作业说明。
     */
    public static final String REPAIR_PROMPT = "你的上一条回复没有按《输出协议》给四段式 `" + FENCE_TAG
            + "` 围栏，导致前端无法渲染作业卡片。\n"
            + "以下是协议原文，本次必须严格遵守（**不要假设你能从别处看到它**）：\n"
            + PROMPT
            + "\n请**只重新输出那一个围栏**：\n"
            + "1. 只输出 ```" + FENCE_TAG + " 代码块，块内是上面的 JSON（sql / customConfig / ddl / precheck 四段）；\n"
            + "2. **不要再写任何解释、评论、标题**，也不要输出 ```sql 代码块；\n"
            + "3. 内容必须与你上一条回复的作业方案一致（含已修正后的 SET 头与自定义配置）。";

    private FlinkJobProtocol() {}
}
