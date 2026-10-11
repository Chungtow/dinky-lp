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
