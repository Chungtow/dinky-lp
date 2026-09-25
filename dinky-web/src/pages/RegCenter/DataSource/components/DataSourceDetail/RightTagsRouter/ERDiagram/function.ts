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

import { DataSources } from '@/types/RegCenter/data';

/**
 * mermaid 标识符仅允许字母、数字与下划线
 */
const sanitize = (value?: string): string =>
  (value ?? '').replace(/\[\]/g, '_arr').replace(/[^a-zA-Z0-9_]/g, '_');

/**
 * 剥离 schema 前缀（如 traccar.tc_user -> tc_user）
 */
const stripSchema = (name: string, schema: string): string => {
  if (!name) {
    return '';
  }
  return schema && name.startsWith(`${schema}.`) ? name.slice(schema.length + 1) : name;
};

/**
 * 构建 ER 图的 mermaid erDiagram 源码
 *
 * <p>规则（对齐 pgconsole Entity Diagram）：
 * <ol>
 *     <li>当前表：列出全部列，主键标注 PK、外键标注 FK；</li>
 *     <li>正向外键（上游，本表引用别表）：{@code 父表 ||--o{ 当前表 : "约束名"}；</li>
 *     <li>反向外键（下游，别表引用本表）：{@code 当前表 ||--o{ 子表 : "约束名"}；</li>
 *     <li>关联表仅列出关键列，避免额外请求。</li>
 * </ol>
 *
 * <p>该源码同时用于两处：可视化渲染（beautiful-mermaid）与「源代码」Tab 展示复制。
 *
 * @param table     当前表（含列信息，keyFlag 标识主键）
 * @param relations 外键关系（上游 foreignKeys / 下游 referencedBy）
 * @returns mermaid erDiagram 源码；无表名时返回空字符串
 */
export const buildErDiagram = (
  table: Partial<DataSources.Table>,
  relations?: DataSources.TableRelations
): string => {
  const tableName = sanitize(table?.name);
  if (!tableName) {
    return '';
  }

  const foreignKeys = relations?.foreignKeys ?? [];
  const referencedBy = relations?.referencedBy ?? [];
  const fkColumns = new Set(foreignKeys.flatMap((fk) => fk.columns ?? []));

  const lines: string[] = ['erDiagram'];

  // 1. 当前表：全部列 + PK/FK 标注
  lines.push(`  ${tableName} {`);
  (table?.columns ?? []).forEach((column) => {
    const markers: string[] = [];
    if (column.keyFlag) {
      markers.push('PK');
    }
    if (fkColumns.has(column.name)) {
      markers.push('FK');
    }
    const markerStr = markers.length ? ` ${markers.join(',')}` : '';
    lines.push(`    ${sanitize(column.type)} ${sanitize(column.name)}${markerStr}`);
  });
  lines.push('  }');

  // 关联表的关键列：sanitize 表名 -> (列名 -> 标记)
  const relatedColumns = new Map<string, Map<string, string>>();
  const addRelatedColumn = (relTable: string, column: string, marker: string) => {
    if (!relatedColumns.has(relTable)) {
      relatedColumns.set(relTable, new Map());
    }
    relatedColumns.get(relTable)!.set(column, marker);
  };

  // 2. 正向外键（上游）：本表引用别表
  foreignKeys.forEach((fk) => {
    const refName = sanitize(stripSchema(fk.refTableName, fk.refSchemaName));
    (fk.refColumns ?? []).forEach((column) => addRelatedColumn(refName, column, 'PK'));
    lines.push(`  ${refName} ||--o{ ${tableName} : "${fk.name ?? ''}"`);
  });

  // 3. 反向外键（下游）：别表引用本表
  referencedBy.forEach((fk) => {
    const childName = sanitize(stripSchema(fk.tableName, fk.schemaName));
    (fk.columns ?? []).forEach((column) => addRelatedColumn(childName, column, 'FK'));
    lines.push(`  ${tableName} ||--o{ ${childName} : "${fk.name ?? ''}"`);
  });

  // 4. 关联表实体（仅关键列）
  relatedColumns.forEach((columns, relTable) => {
    lines.push(`  ${relTable} {`);
    columns.forEach((marker, column) => lines.push(`    _ ${sanitize(column)} ${marker}`));
    lines.push('  }');
  });

  return lines.join('\n');
};
