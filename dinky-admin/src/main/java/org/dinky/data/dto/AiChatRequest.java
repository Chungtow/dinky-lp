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

package org.dinky.data.dto;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * AI Chat 请求。
 *
 * <p><b>NOTE:</b> 请求体中只允许携带元数据定位信息（数据源 / schema / 表），
 * 严禁携带任何业务数据行——AI 上下文只包含元数据，这是本功能的安全红线。
 *
 * @since 2026/09/26
 */
@Data
@ApiModel(value = "AiChatRequest", description = "AI Chat Request")
public class AiChatRequest {

    /**
     * 动作：TEXT_TO_SQL（自然语言生成 SQL） / EXPLAIN（解释 SQL） /
     * FIX_SQL（阶段 2b：基于最近执行报错修复用户选中的 SQL） /
     * REWRITE_SQL（阶段 2b：综合优化改写用户选中的 SQL）。
     *
     * <p>FIX_SQL / REWRITE_SQL 均为<b>单轮显式改写</b>：只产出 SQL 文本、<b>绝不执行</b>，
     * 供前端做 diff 对照后由用户确认替换选中片段（不进入自动校验/修复闭环）。
     */
    @ApiModelProperty(value = "动作：TEXT_TO_SQL / EXPLAIN / FIX_SQL / REWRITE_SQL", example = "TEXT_TO_SQL")
    private String action = "TEXT_TO_SQL";

    @ApiModelProperty(value = "本轮用户输入")
    private String message;

    @ApiModelProperty(value = "历史对话（不含本轮），用于多轮追问")
    private List<AiChatMessage> history;

    /** 会话标识：为空表示首轮（首轮才携带 schema 上下文，后续跳过以节省 token） */
    @ApiModelProperty(value = "会话标识，首轮为空")
    private String sessionId;

    @ApiModelProperty(value = "数据源 ID（上下文来源）")
    private Integer databaseId;

    @ApiModelProperty(value = "schema 名称")
    private String schemaName;

    @ApiModelProperty(value = "表名（Catalog 选中表时携带）")
    private String tableName;

    @ApiModelProperty(value = "SQL 方言，用于提示词", example = "MySQL")
    private String dialect;

    /**
     * 当前作业的编辑区内容（阶段 1.0「作业上下文绑定」）。
     *
     * <p>早期版本仅 EXPLAIN 动作携带，导致普通提问时模型看不到用户正在写的代码；现已改为全动作下发。
     */
    @ApiModelProperty(value = "当前作业编辑区内容")
    private String sql;

    /**
     * 当前作业 id，用于读取最近一次执行状态与报错原文，帮助模型回答“为什么跑挂了”。
     *
     * <p>只取 JobInstance 的 status / error 等元信息，不取业务数据行。
     */
    @ApiModelProperty(value = "当前作业 id")
    private Integer taskId;

    /**
     * 用户在编辑器中<b>选中</b>的片段（阶段 1a：1.0.4）。
     *
     * <p>非空时优先级高于 {@link #sql} 全文：用户选中某段 SQL 提问，意图就是问这一段，
     * 此时再下发全文既浪费预算又干扰模型（业界一致做法）。
     */
    @ApiModelProperty(value = "编辑器选中片段")
    private String selectedSql;

    /**
     * 前端暂存的「最近一次执行报错」原文（阶段 2b：Fix SQL）。
     *
     * <p>编辑器内执行 SQL（尤其 MySQL 等直连数据源）的报错既不落 {@code dinky_history}
     * 也不落 {@code dinky_job_instance}，只能由前端在请求失败时捕获并随请求下发；
     * 仅在 {@code action=FIX_SQL} 时使用，是报错来源的<b>最高优先级</b>。
     */
    @ApiModelProperty(value = "前端暂存的最近一次执行报错（Fix SQL 用）")
    private String executionError;

    /**
     * 上下文范围档位（阶段 1a：1.1）：{@code current}（仅当前选中表）/ {@code all}（当前数据源全库，
     * 默认，兼容既有行为）/ {@code custom}（仅 {@link #customTables} 勾选的表）。
     */
    @ApiModelProperty(value = "上下文范围：current / all / custom", example = "all")
    private String contextScope;

    /** {@code contextScope=custom} 时用户勾选的表名列表 */
    @ApiModelProperty(value = "custom 档位下勾选的表名")
    private List<String> customTables;

    /**
     * 用户通过 {@code @} 显式引用的上下文项（阶段 1a：1.4）。
     *
     * <p>这些项享有<b>最高优先级且不被预算裁剪</b>。
     */
    @ApiModelProperty(value = "@ 显式引用项")
    private List<AiChatMention> mentions;

    /**
     * 对话模式（阶段 2）：{@code ask}（默认，只回答不触碰编辑器）/ {@code craft}（可整块改写编辑器内容）。
     *
     * <p>后端据此分流 system prompt：Craft 要求模型输出<b>完整目标内容</b>而非片段，
     * 并把 mode 写入审计。
     *
     * <p><b>安全边界</b>：Craft 需管理员开启 {@code llm.craftModeEnable}；未开启时后端强制回落为
     * {@code ask}，不信任前端传入值——避免"仅靠隐藏控件"来保证安全。
     */
    @ApiModelProperty(value = "对话模式：ask / craft", example = "ask")
    private String mode;

    /**
     * LLM 实例 id（阶段 3：多 LLM 实例配置）。
     *
     * <p>为空 / {@code default} / 传入不存在的 id → 后端一律<b>回落默认实例</b>
     * （即 {@code sys.llm.*} 单组字段配置），因此<b>旧客户端与评测脚本无需任何改动</b>。
     */
    @ApiModelProperty(value = "LLM 实例 id（缺省走默认实例）", example = "default")
    private String profileId;

    /**
     * 项目级语义资产常驻（P3b，D17）：语义项目名（非句柄，不含 {@code /}）。
     *
     * <p>非空 = 本轮对话要把该项目的**资产清单**（表名 + 指标名 + 一句话口径）常驻进上下文；
     * 为空等价于关闭（不是"全局常驻所有项目"——那样会吃光预算）。
     *
     * <p>生效还需：语义开关开启 + 当前用户有 {@code datastudio:semantic:view}（§4.7，
     * 前端隐藏不作为安全边界）。单资产精确引用走 {@code mentions} 里的 {@code @semantic/...}。
     */
    @ApiModelProperty(value = "项目级语义资产常驻：语义项目名（可空）", example = "sem_demo2")
    private String semanticProject;

    /**
     * 上下文指纹（P4 一期）：由前端按「当前绑定来源」计算，形如 {@code ctx_1a2b3c4d5e6f7a8b}。
     *
     * <p>用途：自动绑定后，切换 tab / 换语义项目都会让上下文变化，但 {@code sessionId} 不变。
     * 后端据此判断「本轮是否需要按首轮重新注入 schema / 语义清单」——
     * 指纹与上一次不同 → 视作首轮重注入（见 {@code AiChatServiceImpl}）。
     *
     * <p>为空 = 旧客户端，行为完全不变（只按 sessionId 是否为空判断首轮）。
     */
    @ApiModelProperty(value = "上下文指纹（可空，空则退化为只看 sessionId）", example = "ctx_1a2b3c4d5e6f7a8b")
    private String contextFingerprint;

    /**
     * 服务端填充的当前用户 id（限流 / 配额 / 审计用）。
     *
     * <p><b>不接受前端传入</b>：Controller 会用 Sa-Token 的登录 id 覆盖，防止伪造绕过限流。
     */
    private Integer userId;

    /**
     * 知识库审计（批次 D）：<b>注入层</b>被拒绝的库编码（{@code @kb} 指到不可见/已停用的库）。
     *
     * <p>为什么挂在请求对象上：注入发生在请求线程（{@link org.dinky.ai.mention.KbMentionInjector}），
     * 而审计对象在另一条链路上构建；两者唯一的共同上下文就是这个请求。用 {@code @JsonIgnore}
     * 保证它<b>绝不会</b>被序列化回前端——那是内部审计数据，不是响应字段。
     *
     * <p>工具层的越权记录走 {@code AiToolContext}（异步线程里攒），二者在审计落库时合并。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<String> kbDeniedCodes = new java.util.ArrayList<>();

    /** 记录一次注入层拒绝（去重；不抛异常，审计永远不该影响主流程） */
    public void recordKbDenied(String code) {
        if (code != null && !code.trim().isEmpty() && !kbDeniedCodes.contains(code.trim())) {
            kbDeniedCodes.add(code.trim());
        }
    }
}
