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

package org.dinky.data.model;

import java.io.Serializable;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ForeignKey
 *
 * <p>表的外键关系（约束级，支持复合外键）。语义统一为「子表 columns -&gt; 父表 refColumns」：
 *
 * <ul>
 *     <li>上游（本表引用的外键）：本表为子表，引用 {@code refTableName}.{@code refColumns}
 *     <li>下游（引用本表的外键）：本表为父表，被 {@code tableName}.{@code columns} 引用
 * </ul>
 *
 * @since 2026/9/25
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ForeignKey implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 外键约束名 */
    private String name;

    /** 子表（引用方）所在 schema */
    private String schemaName;

    /** 子表（引用方）名 */
    private String tableName;

    /** 子表（引用方）外键列，复合外键时按 KEY_SEQ 顺序排列 */
    private List<String> columns;

    /** 父表（被引用方）所在 schema */
    private String refSchemaName;

    /** 父表（被引用方）名 */
    private String refTableName;

    /** 父表（被引用方）被引用列，与 {@link #columns} 一一对应 */
    private List<String> refColumns;
}
