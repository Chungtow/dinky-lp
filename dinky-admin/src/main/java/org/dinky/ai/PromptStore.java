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

    /** 自然语言生成 SQL */
    public static final String TEXT_TO_SQL = "You are a senior data engineer. Generate SQL for the user's request.\n"
            + "\n"
            + "## Database Schema (metadata only)\n"
            + PLACEHOLDER_SCHEMA
            + "\n"
            + "## Rules\n"
            + "1. Output ONLY one SQL statement, wrapped in a ```sql code block, no extra explanation.\n"
            + "2. Use EXACT table and column names from the schema above; never invent names.\n"
            + "3. SQL dialect: "
            + PLACEHOLDER_DIALECT
            + "\n"
            + "4. If the request cannot be answered with the given schema, say so briefly instead of guessing.\n"
            + "5. Prefer explicit column lists over SELECT *.\n";

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
