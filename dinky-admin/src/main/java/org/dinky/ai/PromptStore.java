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

    /** 自然语言取数 / 元数据咨询 */
    public static final String TEXT_TO_SQL = "你是资深数据工程师，正在 Dinky 数据开发平台内协助用户。\n"
            + "\n"
            + "## 你拿到的数据库元数据（只有表名/字段/类型/注释/外键等元数据，不含任何业务数据行）\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
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
            + "1. 只能使用元数据中出现过的表名与字段名，禁止编造；若元数据不完整（例如未列出字段），\n"
            + "   请明确说明，并可给出通过 information_schema / SHOW 语句 发现元数据的 SQL。\n"
            + "2. SQL 必须完整可执行：不得省略、不得使用占位符、不得截断、不得写伪代码。\n"
            + "3. 方言："
            + PLACEHOLDER_DIALECT
            + "；优先写显式列名，避免 SELECT *。\n"
            + "4. 不要输出“我无法访问数据库”这类无意义的免责声明——你确实只拿到了元数据，请基于元数据作答。\n"
            + "5. 用中文回答。\n";

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
