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

/**
 * 生成加分区 SQL（分区字段名与表达式必须和 writer path 一致）。
 *
 * ⚠️ 只做「注册分区」，ANALYZE 由后续的 `analyze_table_*` 节点负责。
 * 原因：DataX 的 hdfswriter 要求 writer path（含 `pt=xxx` 目录）**事先存在且不会自动创建**
 * （`HdfsWriter$Job.prepare` 里 `isPathexists(path)` 为假直接报 HdfsWriter-02），
 * 而 `create_table` 只建表目录。因此节点顺序必须是：
 *
 *   create_table → add_partition（建出分区目录）→ sync_data（写数据）→ analyze_table（刷统计）
 *
 * 统计若放在写数据之前做，分析的是空目录 → `numRows=0`，下游 `count(*)` 仍会返回 0。
 */
export function buildAddPartitionSql(
  schemaName?: string,
  tableName?: string,
  partitionColumn?: string,
  partitionExpression?: string
): string {
  const qualified = schemaName ? `\`${schemaName}\`.\`${tableName}\`` : `\`${tableName}\``;
  return `ALTER TABLE ${qualified} ADD IF NOT EXISTS PARTITION (${partitionColumn}='${partitionExpression ?? ''}')`;
}

/**
 * 生成统计刷新 SQL（`analyze_table_*` 作业）。
 *
 * ⚠️ 必须在数据写入之后执行：Hive 的 ADD PARTITION 只在分区目录由本语句创建时才收集
 * fast stats；一键同步里目录是被 add_partition/sync_data 先创建出来的，Hive 会跳过收集，
 * 分区统计保留 `numFiles=0 / numRows=0 / totalSize=0` 且 `COLUMN_STATS_ACCURATE` 标记为可信。
 * 由于 HS2 默认 `hive.compute.query.using.stats=true`，下游 `select count(*)`/`select *`
 * 会直接读统计导致查不到数据（实测踩过）。ANALYZE 后统计才与真实数据一致。
 */
export function buildAnalyzeSql(
  schemaName?: string,
  tableName?: string,
  partitionColumn?: string,
  partitionExpression?: string
): string {
  const qualified = schemaName ? `\`${schemaName}\`.\`${tableName}\`` : `\`${tableName}\``;
  return `ANALYZE TABLE ${qualified} PARTITION (${partitionColumn}='${partitionExpression ?? ''}') COMPUTE STATISTICS`;
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

/**
 * 非法参数名检测：抓出「${X}」里 X 不符合 [A-Za-z_][A-Za-z0-9_]* 的引用。
 *
 * 背景（三处正则不一致，必须在这里拦住）:
 *   - 前端 extractPlaceholders: /\$\{([A-Za-z_][A-Za-z0-9_]*)\}/  → 不合法的名字直接被忽略
 *   - 后端 DataX 取参 extractParamNames: /\$\{(\w+)\}/             → 同样忽略，json 里原样保留
 *   - 后端 SQL 变量替换 GLOBAL_VARIABLE_PATTERN: /\$\{(.+?)}/      → 匹配但不认识，保留占位符 + 告警
 * 结果: ${biz-date} / ${yyyyMMdd-1} 这类写法会静默进 HDFS 路径与 Hive 分区名。
 */
export function extractInvalidParamRefs(text?: string): string[] {
  const result: string[] = [];
  const re = /\$\{([^}]*)\}/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(text ?? '')) !== null) {
    const name = match[1];
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) && !result.includes(name)) {
      result.push(name);
    }
  }
  return result;
}

/* ================= 阶段二：反向（Hive/Spark → MySQL）SQL 生成 ================= */

export interface MysqlColumnDef {
  name: string;
  /** 已经过 mapToMysqlType 映射的 MySQL 类型 */
  type: string;
  comment?: string;
}

export interface MysqlCreateTableInput {
  schemaName?: string;
  tableName?: string;
  columns: MysqlColumnDef[];
  /** 业务主键列（决定 PRIMARY KEY，也是 upsert 的依据） */
  primaryKey: string[];
  engine?: string;
  charset?: string;
  comment?: string;
}

export const DEFAULT_MYSQL_ENGINE = 'InnoDB';
export const DEFAULT_MYSQL_CHARSET = 'utf8mb4';

/**
 * 生成 MySQL 建表 DDL（反向：目标表不存在时）。
 *
 * ⚠️ 主键列显式 `NOT NULL`：MySQL 虽对 PK 隐含 NOT NULL，显式声明更清晰，
 * 也与人工 SOP 的建表语句一致（`user_id BIGINT NOT NULL`）。
 * ⚠️ 只生成一条语句：Dinky 的 executeCommonSql 走 JDBC 单次执行，目标库需已存在。
 */
export function buildMysqlCreateTableDdl(input: MysqlCreateTableInput): string {
  const {
    schemaName,
    tableName,
    columns,
    primaryKey,
    engine = DEFAULT_MYSQL_ENGINE,
    charset = DEFAULT_MYSQL_CHARSET,
    comment
  } = input;
  const pkSet = new Set(primaryKey);
  const columnLines = columns.map((c) => {
    const notNull = pkSet.has(c.name) ? ' NOT NULL' : ' DEFAULT NULL';
    const commentPart = c.comment ? ` COMMENT '${escapeSqlLiteral(c.comment)}'` : '';
    return `  \`${c.name}\` ${c.type}${notNull}${commentPart}`;
  });
  if (primaryKey.length > 0) {
    columnLines.push(`  PRIMARY KEY (${primaryKey.map((k) => `\`${k}\``).join(', ')})`);
  }
  const qualified = schemaName ? `\`${schemaName}\`.\`${tableName}\`` : `\`${tableName}\``;
  const tail = [
    `ENGINE = ${engine}`,
    `DEFAULT CHARSET = ${charset}`,
    `COLLATE = ${charset}_general_ci`
  ];
  if (comment) {
    tail.push(`COMMENT = '${escapeSqlLiteral(comment)}'`);
  }
  return `CREATE TABLE IF NOT EXISTS ${qualified} (\n${columnLines.join(',\n')}\n) ${tail.join('\n  ')}`;
}
