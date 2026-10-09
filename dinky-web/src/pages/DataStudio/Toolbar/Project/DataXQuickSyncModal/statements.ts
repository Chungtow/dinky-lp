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

import { DataXField } from '@/pages/DataStudio/CenterTabContent/SqlTask/DataXVisualEditor/dataxJson';
import { QuickSyncParam } from './constants';

/** DDL 注释里的单引号转义 */
export const escapeSqlLiteral = (value?: string) => (value ?? '').replace(/'/g, "''");

/** 根据存储格式生成 Hive 的 STORED AS 子句 */
export function storedAsClause(fileType?: string): string {
  switch ((fileType ?? '').trim().toLowerCase()) {
    case 'parquet':
      return 'STORED AS PARQUET';
    case 'text':
    case 'textfile':
      return "ROW FORMAT DELIMITED FIELDS TERMINATED BY '\\t'\nSTORED AS TEXTFILE";
    case 'orc':
    default:
      return 'STORED AS ORC';
  }
}

export interface CreateTableInput {
  schemaName?: string;
  tableName?: string;
  fields: DataXField[];
  partitionColumn?: string;
  fileType?: string;
  comment?: string;
}

/**
 * 生成建表 DDL。
 * ⚠️ 必须只有一条语句：Dinky 的 executeCommonSql 走 JDBC 单次执行，
 * 目标库必须已存在（由用户在下拉里选），因此不生成 CREATE DATABASE。
 */
export function buildCreateTableDdl(input: CreateTableInput): string {
  const { schemaName, tableName, fields, partitionColumn, fileType, comment } = input;
  const columnLines = fields.map((f) => {
    const type = (f.type || 'STRING').trim().toUpperCase();
    const commentPart = f.comment ? ` COMMENT '${escapeSqlLiteral(f.comment)}'` : '';
    return `  \`${f.name}\` ${type}${commentPart}`;
  });
  const qualified = schemaName ? `\`${schemaName}\`.\`${tableName}\`` : `\`${tableName}\``;
  const lines: string[] = [`CREATE TABLE IF NOT EXISTS ${qualified} (`, columnLines.join(',\n'), ')'];
  if (comment) {
    // DDL 注释放在字段块之后、PARTITIONED BY 之前（Hive 语法允许）
    lines[lines.length - 1] = `)\nCOMMENT '${escapeSqlLiteral(comment)}'`;
  }
  if (partitionColumn) {
    lines.push(`PARTITIONED BY (\`${partitionColumn}\` STRING)`);
  }
  lines.push(storedAsClause(fileType));
  return lines.join('\n');
}

/** 生成加分区 SQL（分区字段名与表达式必须和 writer path 一致） */
export function buildAddPartitionSql(
  schemaName?: string,
  tableName?: string,
  partitionColumn?: string,
  partitionExpression?: string
): string {
  const qualified = schemaName ? `\`${schemaName}\`.\`${tableName}\`` : `\`${tableName}\``;
  return `ALTER TABLE ${qualified} ADD IF NOT EXISTS PARTITION (${partitionColumn}='${partitionExpression ?? ''}')`;
}

/** 抽取语句里所有 ${X} 占位符名 */
export function extractPlaceholders(text?: string): string[] {
  const result: string[] = [];
  const re = /\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
  let match: RegExpExecArray | null;
  const source = text ?? '';
  while ((match = re.exec(source)) !== null) {
    if (!result.includes(match[1])) {
      result.push(match[1]);
    }
  }
  return result;
}

/**
 * 参数引用闭合校验：语句里引用的每个 ${X} 都必须在 taskParams 里定义。
 * 后端 BaseTask.replaceTaskVariables 对缺失变量是「保留占位符 + 告警」，
 * 到 DS 侧会写坏分区，因此必须在前端挡住。
 */
export function validateParamRefs(texts: (string | undefined)[], params: QuickSyncParam[]): string[] {
  const defined = new Set((params ?? []).map((p) => p.prop).filter(Boolean));
  const missing: string[] = [];
  texts.forEach((text) => {
    extractPlaceholders(text).forEach((name) => {
      if (!defined.has(name) && !missing.includes(name)) {
        missing.push(name);
      }
    });
  });
  return missing;
}
