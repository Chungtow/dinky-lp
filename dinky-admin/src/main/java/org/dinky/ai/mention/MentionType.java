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
 * AI Chat {@code @} 引用（mention）的类型常量（阶段 4b）。
 *
 * <p><b>为什么现在才抽</b>：4a 时前后端各自硬编码了 {@code "table"} / {@code "column"} / {@code "skill"}
 * 字面量（当时就需要收敛，被记为遗留偏差）。4b 要新增 {@code doc} 并做前缀细分，若不先收敛，
 * 「一处改动漏掉另一处」的概率会随类型数量线性上升——所以本批先把它钉成常量。
 *
 * <p>⚠️ <b>值不可改</b>：它们随请求体在前后端之间传递，改动等于协议变更（与 {@code AiTool#name()} 同理）。
 *
 * @since 2026/10/05
 */
public final class MentionType {

    /** 表引用（{@code @table/<名>}（旧写法 {@code @table-<名>} 仍兼容）或最旧写法 {@code @表名}） */
    public static final String TABLE = "table";

    /** 字段引用（{@code @表.字段}，阶段 2a） */
    public static final String COLUMN = "column";

    /** skill 引用（{@code @skill/<名>}，阶段 4a） */
    public static final String SKILL = "skill";

    /** 知识文档引用（{@code @doc/<名>}，阶段 4b；与 skill 共用存储与权限模型） */
    public static final String DOC = "doc";

    /**
     * 跨数据源物理对象引用（{@code @source/<数据源>[/<库>[/<表>[.<字段>]]]}，P1）。
     *
     * <p><b>与 {@link #TABLE} 的区别</b>：{@code @table/<表名>} 的语义是「当前面板所选数据源内的表」
     * （依赖请求里的 {@code databaseId}），而 FlinkSQL 作业<b>没有数据源</b>（P0 结论），
     * 想在 FlinkSQL 里引用「MySQL 源表 / Doris sink 表」就必须走全限定路径——这正是本类型存在的理由。
     *
     * <p>语义上一步覆盖了曾经的 {@code @doris/} 设想：Dinky 的元数据层本就按数据源类型路由
     * （{@code Driver.build(...)}），无需为某一种数据源单独开语法。
     */
    public static final String SOURCE = "source";

    /**
     * Kafka topic 引用（{@code @topic/<名>}，P1）。
     *
     * <p>P1 的零连接版：只从「名册 + 作业 DDL 的 canal-json 血缘 + MySQL 源表结构」推导，
     * <b>不连接 Kafka</b>；实时枚举（含未被使用的 topic）由批次 2 的 {@code list_topics} 工具承担。
     */
    public static final String TOPIC = "topic";

    /**
     * FlinkSQL 环境任务引用（{@code @env/<env任务名>}，P1）。
     *
     * <p><b>只注入枚举结果</b>（该 env 经回放注册出的 catalog / 库清单），
     * <b>绝不注入 env 语句原文</b>——里面是 {@code 'password'='...'} 明文连接串。
     */
    public static final String ENV = "env";

    /**
     * 语义资产引用（{@code @semantic/<project>[/<name>]}，P3b）。
     *
     * <p><b>名字里存的是路径式句柄</b>（对齐 {@link #SOURCE} 的 {@code ds/db/tbl} 存法，D19）：
     * {@code "<project>"} 或 {@code "<project>/<asset>"} 或长式
     * {@code "<project>/table|metric/<asset>"}。句柄解析发生在<b>后端注入时</b>，
     * 前端只负责候选与插入文本。
     *
     * <p>语义资产是**元数据**（表名/列名/口径），不含业务数据行，也不调 {@code mf}（D21）。
     */
    public static final String SEMANTIC = "semantic";

    /**
     * 知识库范围引用（{@code @kb/<code>}，知识库 @kb 接入 v1.0）。
     *
     * <p><b>⚠️ 与 {@link #SEMANTIC} / {@link #SKILL} 的本质区别</b>：那两者是<b>内容句柄</b>
     * （解析后直接把正文注入上下文）；kb 是<b>范围句柄</b>——它只声明"本次在哪个知识库里检索"，
     * 产物仅一行范围声明，<b>正文必须由 {@code kb_search} 工具召回</b>。
     *
     * <p>若把 kb 当内容句柄实现（read 后注入全文），会退化成"预注入"：每次对话都产生 embedding
     * 费用、把大量无关片段塞进上下文，且与「按需消费」的设计前提相反
     * （调研 §4.7.0）。注入实现见 {@code KbMentionInjector}。
     *
     * <p>名字里存的是库<b>编码</b>（{@code c_} + 8 位 hex），不是显示名：编码系统生成、永不变，
     * 可直接写进 kb-service 的 {@code filter.collection}；显示名可改且可能重名，故不参与句柄
     * （前端插入时只插编码，显示名仅用于候选浮层展示）。
     */
    public static final String KB = "kb";

    private MentionType() {}
}
