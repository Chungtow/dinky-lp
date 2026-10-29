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
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TableRelations
 *
 * <p>表的关系集合，作为 ER 图（Entity Diagram）的数据源。
 *
 * @since 2026/9/25
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TableRelations implements Serializable {

    private static final long serialVersionUID = 1L;

    /** schema 名 */
    private String schemaName;

    /** 表名 */
    private String tableName;

    /** 上游：本表引用的外键（本表作为子表 -&gt; 别表作为父表） */
    @Builder.Default
    private List<ForeignKey> foreignKeys = new ArrayList<>();

    /** 下游：引用本表的外键（别表作为子表 -&gt; 本表作为父表） */
    @Builder.Default
    private List<ForeignKey> referencedBy = new ArrayList<>();
}
