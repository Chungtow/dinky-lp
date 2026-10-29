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

import java.util.Map;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;

/**
 * AI 提示词集中管理。
 *
 * <p>所有能力（Text-to-SQL / Explain 等）的提示词在此统一维护，schema 等动态内容通过
 * <code>{{placeholder}}</code> 占位符注入，便于复用与后续调整。
 *
 * @since 2026/09/26
 */
public final class PromptStore {

    private PromptStore() {}

    public static final String PLACEHOLDER_SCHEMA = "{{schema}}";
    /**
     * FlinkSQL 上下文区块（P0 只读）。
     *
     * <p><b>与 {@link #PLACEHOLDER_SCHEMA} 的关系</b>：schema 由「数据源 id + schema 名」驱动，而 FlinkSQL
     * 作业没有数据源，其上下文来自「作业绑定 + Flink Catalog + 外部资源 + 规则红线 + 命名名册」的组合，
     * 因此走独立占位符、独立预算（{@code llmFlinkContextMaxChars}）。**非 FlinkSQL 方言一律传空串**，
     * 保证既有 Sql / SparkSQL 链路的 prompt 逐字节不变。
     */
    public static final String PLACEHOLDER_FLINK_CONTEXT = "{{flinkContext}}";

    public static final String PLACEHOLDER_DIALECT = "{{dialect}}";
    public static final String PLACEHOLDER_SQL = "{{sql}}";
    public static final String PLACEHOLDER_ERROR = "{{error}}";
    /** 当前作业编辑区内容（整体区块，含标题；无内容时后端传空串） */
    public static final String PLACEHOLDER_EDITOR_SQL = "{{editorSql}}";
    /** 当前作业最近一次执行情况（整体区块，含标题；无内容时后端传空串） */
    public static final String PLACEHOLDER_JOB_CONTEXT = "{{jobContext}}";
    /** 阶段 2c-2：自动纠错已尝试次数（用于 {@link #TOOL_REPAIR}） */
    public static final String PLACEHOLDER_ATTEMPT = "{{attempt}}";
    /** 阶段 2c-2：自动纠错上限（用于 {@link #TOOL_REPAIR}） */
    public static final String PLACEHOLDER_MAX_ATTEMPTS = "{{maxAttempts}}";
    /**
     * 阶段 4b：「创建 skill 的行为规范」区块占位符（用于 {@link #TEXT_TO_SQL}）。
     *
     * <p><b>按需注入</b>——仅当用户本次明确要求新建 skill 时由 {@code AiChatServiceImpl#buildSkillRules}
     * 填入 {@link #SKILL_RULES}，其余场景注入空串。为什么条件注入而非常驻：① 规则只对创建场景有意义，
     * 常驻白占上下文预算；② 未开启 skill 工具时（如生产 {@code skillEnable=false}），模型看到「如何创建
     * skill」却无工具可用，容易产生无效的工具调用尝试。
     */
    public static final String PLACEHOLDER_SKILL_RULES = "{{skillRules}}";

    /**
     * 创建 skill 的行为规范（按需注入，见 {@link #PLACEHOLDER_SKILL_RULES}）。
     *
     * <p>第 1 条的动机：模型的默认习惯是「先逐个研读既有 skill 再动手」，UAT 实测一次创建会读 3 个
     * 范例，在工具轮次有限时会把「建主体 + 写附件」挤到下一轮甚至触顶；第 3、4 条同样来自 UAT——
     * 只建主文件不写附件、或在主文件里引用从未写成功的附件，都会产出坏资产（如 lpdw-monitor）。
     */
    public static final String SKILL_RULES = "\n"
            + "## 创建 skill（本次请求相关）\n"
            + "1. **最多参考 1 个**既有 skill 作为格式范例，然后直接创建，**不要逐个研读**现有 skill。\n"
            + "2. 例外：若用户**显式指定**了参考对象（如「参考 xxx」「照 xxx 的样子」），或明确要求\n"
            + "   「参考全部 / 所有 / 逐个」既有 skill，则以用户要求为准。\n"
            + "3. 创建后若还有 references/ 附件（流程文档、SQL 模板、阈值口径等），**当轮就用\n"
            + "   write_skill_file 写完**（一次调用写一个文件），不要只建主文件、等用户再催一遍。\n"
            + "4. 主文件里**引用到的每个附件都必须真实写入**；若某个附件写入失败，要如实告知用户，\n"
            + "   不要留下指向不存在文件的引用。\n";

    /** 自然语言取数 / 元数据咨询 */
    public static final String TEXT_TO_SQL = "你是资深数据工程师，正在 Dinky 数据开发平台内协助用户。\n"
            + "\n"
            + "## 你拿到的数据库元数据（只有表名/字段/类型/注释/外键等元数据，不含任何业务数据行）\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + PLACEHOLDER_FLINK_CONTEXT
            + PLACEHOLDER_EDITOR_SQL
            + PLACEHOLDER_JOB_CONTEXT
            + "## 回答问题的方式\n"
            + "1. 先判断问题类型：\n"
            + "   - 咨询类（例如“哪张表是设备信息表”“有没有跟订单相关的表”“某字段是什么意思”）：\n"
            + "     直接用中文给出结论，不要硬凑成取数 SQL。\n"
            + "   - 取数类（要查询/统计/关联/导出数据）：给出完整可执行的 SQL。\n"
            + "2. 输出顺序固定为两部分：\n"
            + "   ① 依据：2-4 条要点，说明你依据元数据中的哪些表名/字段/注释得出的结论（每行一条，简洁）。\n"
            + "   ② 结论：咨询类给中文结论；取数类给出一个 ```sql 代码块，里面是完整 SQL。\n"
            + "\n"
            + "## 硬性约束\n"
            + "1. 只能使用元数据中出现过的表名与字段名，禁止编造；若确实缺少所需字段，请直接说明缺什么，\n"
            + "   并引导用户在 AI Chat 面板顶部选择 schema / 具体表后重试，**不要**自行编写\n"
            + "   information_schema / SHOW 之类的元数据探测 SQL，也不要谎称数据库无法访问。\n"
            + "2. SQL 必须完整可执行：不得省略、不得使用占位符、不得截断、不得写伪代码。\n"
            + "3. 方言："
            + PLACEHOLDER_DIALECT
            + "；优先写显式列名，避免 SELECT *。\n"
            + "4. 不要输出“我无法访问数据库”这类无意义的免责声明——你确实只拿到了元数据，请基于元数据作答。\n"
            + "5. 用中文回答。\n"
            + "6. 若上下文中提供了「当前编辑区内容」，用户口中的“这段代码”“这段 SQL”“这里”均指它；\n"
            + "   改写或续写时必须保留原有业务口径，不要另起炉灶。\n"
            + "7. 若上下文中提供了「当前作业最近一次执行情况」，当用户问“为什么跑挂了 / 报错了 / 失败了”时，\n"
            + "   必须基于其中的状态与报错原文分析根因并给出修复建议，不要泛泛而谈。\n"
            // 阶段 4b：创建 skill 的行为规范——**条件注入**（仅当用户本次要求创建 skill 时非空，
            // 见 AiChatServiceImpl#buildSkillRules）。理由：该规则只对创建场景有意义，常驻会白占
            // 预算；且在未开启 skill 工具时（如生产 skillEnable=false）会诱导模型尝试无效工具调用。
            + PLACEHOLDER_SKILL_RULES;

    /**
     * Craft 模式（阶段 2）：产出<b>改写后的完整目标内容</b>，由前端整块替换进编辑器。
     *
     * <p>与 {@link #TEXT_TO_SQL} 的关键差异：Ask 只需回答/给 SQL，而 Craft 的输出会被<b>整块替换</b>
     * 进编辑器，因此必须是可直接落盘的完整文本——任何片段或省略都会截断用户原有代码。
     */
    public static final String CRAFT = "你是资深数据工程师，正在 Dinky 数据开发平台的编辑器中【直接改写】用户当前作业的代码。\n"
            + "\n"
            + "## 你拿到的上下文\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + PLACEHOLDER_EDITOR_SQL
            + PLACEHOLDER_JOB_CONTEXT
            + "\n"
            + "## 任务\n"
            + "按用户要求，产出**改写后的完整文件内容**——不是片段、不是 diff、不是补丁。\n"
            + "\n"
            + "## 硬性约束\n"
            + "1. **必须输出完整内容**：你的输出会被整块替换进编辑器，任何省略（如「其余保持不变」、\n"
            + "   “...”、“同上”）都会造成用户代码缺失，属于严重错误。\n"
            + "2. 输出**有且仅有一个**代码块，块内即改写后的完整内容；不要给出多个候选版本。\n"
            + "3. 只改用户要求的部分，**保留**其余内容与格式（缩进、注释、结尾分号风格）。\n"
            + "4. 方言："
            + PLACEHOLDER_DIALECT
            + "。\n"
            + "5. 只能使用元数据中出现过的表名与字段名，禁止编造。\n"
            + "6. 代码块之外只写一句话说明改了什么，不要长篇解释，也不要复述全文。\n"
            + "7. 用中文回答。\n";

    /**
     * 依据数据源返回的<b>真实报错</b>修复 SQL（阶段 0：正确性闭环）。
     *
     * <p>关键设计：把执行引擎的原始错误原文回传给模型，而不是让它凭空重写——
     * 这是"语法自动纠错"能真正收敛的前提。
     */
    public static final String SQL_REPAIR = "你上一步生成的 SQL 在目标数据源上执行失败了，请修复它。\n"
            + "\n"
            + "## 执行失败的 SQL\n"
            + "```sql\n"
            + PLACEHOLDER_SQL
            + "\n```\n"
            + "\n"
            + "## 数据源返回的真实错误（原文）\n"
            + PLACEHOLDER_ERROR
            + "\n"
            + "\n"
            + "## 可用的数据库元数据（只有元数据，不含任何数据行）\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + "\n"
            + "## 修复要求\n"
            + "1. 只修导致报错的地方，保持用户的取数意图不变；\n"
            + "2. 只能使用元数据中出现过的表名与字段名，禁止编造；\n"
            + "3. 若错误提示表/字段不存在，请改用元数据中真实存在的名称；\n"
            + "4. 只输出一个 ```sql 代码块，里面是完整可执行的 SQL，不要任何解释文字；\n"
            + "5. 方言："
            + PLACEHOLDER_DIALECT
            + "。\n";

    /**
     * 工具调用失败后的<b>纠错指引</b>（阶段 2c-2：自动纠错闭环）。
     *
     * <p>与 {@link #SQL_REPAIR} 的区别：后者是「重新生成」用的完整 system prompt（整条替换）；
     * 本模板是一段<b>追加在工具失败回灌内容之后</b>的短指引——工具循环里模型的 system prompt 不变，
     * 只把「失败原文 + 该怎么做」作为工具结果回灌，引导模型改稿并再次调用工具。
     *
     * <p>占位符：{@link #PLACEHOLDER_ATTEMPT}（第几次尝试）/ {@link #PLACEHOLDER_MAX_ATTEMPTS}（上限）。
     */
    public static final String TOOL_REPAIR = "\n"
            + "## 纠错指引（第 "
            + PLACEHOLDER_ATTEMPT
            + "/"
            + PLACEHOLDER_MAX_ATTEMPTS
            + " 次尝试）\n"
            + "1. 依据上面的报错修正 SQL——**只改导致报错的地方**，保持原有取数意图不变；\n"
            + "2. 只能使用元数据中真实存在的表名 / 字段名，禁止编造；\n"
            + "3. 修正后**再次调用工具**验证；若再次失败，按报错继续修正；\n"
            + "4. 涉及写语句（DML / DDL）时仍需用户确认，**不要试图绕过确认**；\n"
            + "5. 若判断已无法通过修改解决（如权限不足、表确实不存在），请停止重试并直接说明原因。\n";

    /**
     * 修复「<b>用户选中的 SQL</b>」（阶段 2b：局部改写 Fix SQL）。
     *
     * <p>与 {@link #SQL_REPAIR} 的区别：后者的主语是"你（模型）上一步生成的 SQL"，用于生成流内的
     * 自动重试；本模板的主语是"用户在编辑器中主动选中的 SQL"，由用户显式触发、单轮产出、经 diff 确认后
     * 才替换回编辑器。两者占位符机制一致。
     */
    public static final String SQL_FIX = "用户在编辑器中选中的 SQL 执行失败了，请修复它。\n"
            + "\n"
            + "## 执行失败的 SQL（用户选中片段）\n"
            + "```sql\n"
            + PLACEHOLDER_SQL
            + "\n```\n"
            + "\n"
            + "## 数据源返回的真实错误（原文）\n"
            + PLACEHOLDER_ERROR
            + "\n"
            + "\n"
            + "## 可用的数据库元数据（只有元数据，不含任何数据行）\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + "\n"
            + "## 修复要求\n"
            + "1. 只修导致报错的地方，保持用户的取数意图不变；\n"
            + "2. 只能使用元数据中出现过的表名与字段名，禁止编造；\n"
            + "3. 若错误提示表/字段不存在，请改用元数据中真实存在的名称；\n"
            + "4. 只输出一个 ```sql 代码块，里面是完整可执行的 SQL，不要任何解释文字；\n"
            + "5. 方言："
            + PLACEHOLDER_DIALECT
            + "。\n";

    /**
     * 综合优化改写「<b>用户选中的 SQL</b>」（阶段 2b：局部改写 Rewrite SQL）。
     *
     * <p>不基于报错，只做"综合优化"（决策 3：默认综合优化，暂不做性能/可读性/方言分档）。
     * 与 Fix 一样单轮产出、经 diff 确认后替换选中片段，<b>不自动执行</b>。
     */
    public static final String SQL_REWRITE = "你是资深数据工程师，请对用户在编辑器中选中的一段 SQL 做【综合优化改写】。\n"
            + "\n"
            + "## 待改写的 SQL（用户选中片段）\n"
            + "```sql\n"
            + PLACEHOLDER_SQL
            + "\n```\n"
            + "\n"
            + "## 可用的数据库元数据（只有元数据，不含任何数据行）\n"
            + PLACEHOLDER_SCHEMA
            + PLACEHOLDER_JOB_CONTEXT
            + "## 改写要求\n"
            + "1. 综合优化：在不改变业务口径的前提下提升可读性与执行效率"
            + "（如补显式列名、避免 SELECT *、合理 JOIN/过滤、补齐必要别名）；\n"
            + "2. 只能使用元数据中出现过的表名与字段名，禁止编造；\n"
            + "3. 保持原有业务逻辑与结果集语义不变，不得删减任何过滤/关联条件；\n"
            + "4. 只输出一个 ```sql 代码块，里面是完整可执行的 SQL，不要任何解释文字；\n"
            + "5. 方言："
            + PLACEHOLDER_DIALECT
            + "。\n";

    /** 解释 SQL */
    public static final String EXPLAIN = "You are a senior data engineer. Explain the given SQL in Chinese.\n"
            + "\n"
            + "## Database Schema (metadata only)\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + "## SQL to explain (dialect: "
            + PLACEHOLDER_DIALECT
            + ")\n"
            + "```sql\n"
            + PLACEHOLDER_SQL
            + "\n```\n"
            + "\n"
            + "## Output\n"
            + "Explain in Chinese with: 1) 这段代码做什么（业务口径）2) 关键步骤拆解 3) 潜在性能/正确性风险。\n"
            + "Be concise and structured; use short bullet points.\n";

    /**
     * 渲染提示词：把占位符替换为实际内容。
     *
     * @param template 模板
     * @param params 占位符 → 实际值
     * @return 渲染后的提示词
     */
    public static String render(String template, Map<String, String> params) {
        String result = template;
        if (MapUtil.isNotEmpty(params)) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                result = result.replace(entry.getKey(), StrUtil.nullToEmpty(entry.getValue()));
            }
        }
        return result;
    }
}
