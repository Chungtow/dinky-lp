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
    public static final String PLACEHOLDER_DIALECT = "{{dialect}}";
    public static final String PLACEHOLDER_SQL = "{{sql}}";
    public static final String PLACEHOLDER_ERROR = "{{error}}";
    /** 当前作业编辑区内容（整体区块，含标题；无内容时后端传空串） */
    public static final String PLACEHOLDER_EDITOR_SQL = "{{editorSql}}";
    /** 当前作业最近一次执行情况（整体区块，含标题；无内容时后端传空串） */
    public static final String PLACEHOLDER_JOB_CONTEXT = "{{jobContext}}";

    /** 自然语言取数 / 元数据咨询 */
    public static final String TEXT_TO_SQL = "你是资深数据工程师，正在 Dinky 数据开发平台内协助用户。\n"
            + "\n"
            + "## 你拿到的数据库元数据（只有表名/字段/类型/注释/外键等元数据，不含任何业务数据行）\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
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
            + "   必须基于其中的状态与报错原文分析根因并给出修复建议，不要泛泛而谈。\n";

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
