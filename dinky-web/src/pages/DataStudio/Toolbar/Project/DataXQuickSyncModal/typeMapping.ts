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

export interface HiveTypeOptions {
  /** 整数统一升级为 BIGINT（贴近现有人工建表产物） */
  intToBigInt?: boolean;
  /** decimal 映射为 STRING 而非 DOUBLE（精度敏感场景） */
  decimalAsString?: boolean;
}

const normalize = (type?: string) => (type ?? '').trim().toLowerCase();

/**
 * 判断某个 Hive 类型是否会触发 DataX hdfswriter 的类型校验失败。
 */
export function isUnsupportedHiveType(hiveType?: string): boolean {
  const t = (hiveType ?? '').trim().toUpperCase();
  return t.startsWith('DECIMAL') || t.startsWith('NUMERIC') || t.startsWith('BINARY');
}

/**
 * MySQL 类型 → Hive 类型。未知类型兜底 STRING。
 */
export function mapToHiveType(mysqlType?: string, opts: HiveTypeOptions = {}): string {
  const raw = normalize(mysqlType);
  if (!raw) {
    return 'STRING';
  }
  const base = raw.replace(/\(.*?\)/g, '').replace(/unsigned/g, '').trim();

  // decimal / numeric：hdfswriter 枚举无 DECIMAL
  if (base === 'decimal' || base === 'numeric') {
    return opts.decimalAsString ? 'STRING' : 'DOUBLE';
  }
  // binary 家族：枚举无 BINARY
  if (['binary', 'varbinary', 'blob', 'tinyblob', 'mediumblob', 'longblob'].includes(base)) {
    return 'STRING';
  }
  // bit / boolean
  if (['bit', 'boolean', 'bool'].includes(base)) {
    return 'BOOLEAN';
  }
  // 整数
  if (base === 'tinyint') {
    if (/^tinyint\(1\)/.test(raw)) {
      return 'BOOLEAN';
    }
    return opts.intToBigInt ? 'BIGINT' : 'TINYINT';
  }
  if (base === 'smallint') {
    return opts.intToBigInt ? 'BIGINT' : 'SMALLINT';
  }
  if (base === 'mediumint' || base === 'int' || base === 'integer') {
    return opts.intToBigInt ? 'BIGINT' : 'INT';
  }
  if (base === 'bigint') {
    return 'BIGINT';
  }
  // 浮点
  if (base === 'float') {
    return 'FLOAT';
  }
  if (base === 'double' || base === 'real') {
    return 'DOUBLE';
  }
  // 时间
  if (base === 'date') {
    return 'DATE';
  }
  if (base === 'datetime' || base === 'timestamp') {
    return 'TIMESTAMP';
  }
  // char / varchar / text / json / enum / set 以及其它未知类型
  return 'STRING';
}

/* --------------------- 反向同步：Hive → MySQL（阶段一/二共用） --------------------- */

export interface MysqlTypeOptions {
  /** string/varchar 映射的目标长度（默认 255）；≤0 或 >4000 时用 TEXT */
  stringLength?: number;
  /** 复杂类型（array/map/struct）映射为 JSON（默认）还是 TEXT */
  complexAsJson?: boolean;
}

/**
 * Hive 类型 → MySQL 列类型（反向同步的建表 DDL 用）。
 *
 * ⚠️ 与 {@link mapToHiveType} 方向相反，且**不需要**像正向那样把 decimal/binary 降级
 * ——MySQL 类型体系更全，相反要注意的是「字符串静默截断」与「复杂类型不可读」
 * （复杂类型按 JSON 声明，若 `hdfsreader` 读不出来则需在去重 SQL 阶段 CAST 成字符串）。
 */
export function mapToMysqlType(hiveType?: string, opts: MysqlTypeOptions = {}): string {
  const raw = (hiveType ?? '').trim().toLowerCase();
  const base = raw.replace(/\(.*\)/, '').trim();
  const len = opts.stringLength ?? 255;

  switch (base) {
    case 'tinyint':
      return 'TINYINT';
    case 'smallint':
      return 'SMALLINT';
    case 'int':
    case 'integer':
      return 'INT';
    case 'bigint':
      return 'BIGINT';
    case 'float':
      return 'FLOAT';
    case 'double':
      return 'DOUBLE';
    case 'decimal':
    case 'numeric': {
      const m = raw.match(/\((\d+)\s*,\s*(\d+)\)/);
      return m ? `DECIMAL(${m[1]},${m[2]})` : 'DECIMAL(20,6)';
    }
    case 'boolean':
      return 'TINYINT(1)';
    case 'date':
      return 'DATE';
    case 'timestamp':
      return 'DATETIME';
    case 'binary':
      return 'VARBINARY(255)';
    case 'string':
    case 'varchar':
    case 'char': {
      const m = raw.match(/\((\d+)\)/);
      const size = m ? Number(m[1]) : len;
      return size > 0 && size <= 4000 ? `VARCHAR(${size})` : 'TEXT';
    }
    case 'array':
    case 'map':
    case 'struct':
      return opts.complexAsJson === false ? 'TEXT' : 'JSON';
    default:
      return len > 0 && len <= 4000 ? `VARCHAR(${len})` : 'TEXT';
  }
}
