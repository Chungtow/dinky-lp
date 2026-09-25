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
 * 规则（对齐 pgconsole Entity Diagram）：
 * 1. 当前表：列出全部列，主键标注 PK、外键标注 FK；
 * 2. 正向外键（上游）：父表 ||--o{ 当前表 : "约束名"；
 * 3. 反向外键（下游）：当前表 ||--o{ 子表 : "约束名"；
 * 4. 关联表仅列出关键列，避免额外请求。
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

/** 表卡片中的字段 */
export type ErColumn = {
  name: string;
  /** PK / FK / 空字符串 */
  mark: string;
};

/** G6 图数据：一张表 = 一个节点 */
export type ErGraphNode = {
  id: string;
  data: {
    name: string;
    /** 是否为当前正在查看的表 */
    isCurrent: boolean;
    columns: ErColumn[];
  };
};

/** G6 图数据：表与表之间的外键关系 */
export type ErGraphEdge = {
  id: string;
  source: string;
  target: string;
  data: { name: string; key: string };
};

/** ER 图 G6 数据 */
export type ErGraphData = {
  nodes: ErGraphNode[];
  edges: ErGraphEdge[];
};

/**
 * 构建 G6 版 ER 图数据：**一张表一个节点**（节点内展示字段与 PK/FK 标记），
 * 表与表之间以「父表 -&gt; 子表」的有向边表达外键关系。
 *
 * <p>与 {@link buildErDiagram}（mermaid 源码）使用同一份关系数据，保证「可视化」与「源代码」语义一致。
 * 关联表仅包含外键涉及的关键列；同一对表的重复外键只保留一条边，避免视觉噪音。
 */
export const buildErGraphData = (
  table: Partial<DataSources.Table>,
  relations?: DataSources.TableRelations
): ErGraphData => {
  const currentTable = stripSchema(table?.name ?? '', table?.schema ?? '');
  const result: ErGraphData = { nodes: [], edges: [] };
  if (!currentTable) {
    return result;
  }

  // 表名 -> (列名 -> 标记)
  const tables = new Map<string, Map<string, string>>();
  const tableOf = (name: string) => {
    const key = name || currentTable;
    if (!tables.has(key)) {
      tables.set(key, new Map());
    }
    return tables.get(key)!;
  };

  // 1. 当前表：全部列（PK / FK / 普通列）
  const current = tableOf(currentTable);
  const fkColumnNames = new Set<string>();
  (relations?.foreignKeys ?? []).forEach((fk) => (fk.columns ?? []).forEach((c) => fkColumnNames.add(c)));
  (table?.columns ?? []).forEach((column) => {
    current.set(column.name, column.keyFlag ? 'PK' : fkColumnNames.has(column.name) ? 'FK' : '');
  });

  // 2. 表与表之间的边（父表 -> 子表），同一对表去重
  const edges: ErGraphEdge[] = [];
  const edgeKeys = new Set<string>();
  const addEdge = (parentTable: string, childTable: string, fkName: string) => {
    if (!parentTable || !childTable || parentTable === childTable) {
      return;
    }
    const key = `${parentTable}->${childTable}`;
    if (edgeKeys.has(key)) {
      return;
    }
    edgeKeys.add(key);
    edges.push({
      id: `e-${key}`,
      source: parentTable,
      target: childTable,
      data: { name: fkName ?? '', key }
    });
  };

  // 3. 上游：本表引用的外键（父表 -> 本表）
  (relations?.foreignKeys ?? []).forEach((fk) => {
    const refTable = stripSchema(fk.refTableName ?? '', fk.refSchemaName ?? '');
    if (!refTable) {
      return;
    }
    const parent = tableOf(refTable);
    (fk.refColumns ?? []).forEach((column) => parent.set(column, 'PK'));
    (fk.columns ?? []).forEach((column) => current.set(column, 'FK'));
    addEdge(refTable, currentTable, fk.name ?? '');
  });

  // 4. 下游：引用本表的外键（本表 -> 子表）
  (relations?.referencedBy ?? []).forEach((fk) => {
    const childTable = stripSchema(fk.tableName ?? '', fk.schemaName ?? '');
    if (!childTable) {
      return;
    }
    const child = tableOf(childTable);
    (fk.columns ?? []).forEach((column) => child.set(column, 'FK'));
    (fk.refColumns ?? []).forEach((column, i) => {
      current.set(column, current.get(column) === 'FK' ? 'FK' : 'PK');
      // 子表侧外键列已在外键信息中，若缺失则按名称补一个占位
      if ((fk.columns ?? [])[i]) {
        child.set((fk.columns ?? [])[i], 'FK');
      }
    });
    addEdge(currentTable, childTable, fk.name ?? '');
  });

  // 5. 组装节点（一张表一个节点）
  tables.forEach((columns, name) => {
    result.nodes.push({
      id: name,
      data: {
        name,
        isCurrent: name === currentTable,
        columns: Array.from(columns.entries()).map(([column, mark]) => ({ name: column, mark }))
      }
    });
  });

  return result;
};
