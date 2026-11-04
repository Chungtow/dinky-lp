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

export type FlinkDdlItem = {
  engine?: string;
  database?: string;
  table?: string;
  statement?: string;
};

export type FlinkPrecheckItem = {
  item?: string;
  how?: string;
  status?: string;
};

export type FlinkCustomConfigItem = {
  key: string;
  value: string;
};

export type FlinkJobDraft = {
  name?: string;
  sql?: string;
  customConfig?: FlinkCustomConfigItem[];
  ddl?: FlinkDdlItem[];
  precheck?: FlinkPrecheckItem[];
};

/** 把 `{key,value}` 数组或对象两种写法统一成数组（模型两种都会写） */
export const normalizeCustomConfig = (input: unknown): FlinkCustomConfigItem[] => {
  if (!input) return [];
  if (Array.isArray(input)) {
    return input
      .map((item) => {
        if (!item || typeof item !== 'object') return null;
        const record = item as Record<string, unknown>;
        const key = typeof record.key === 'string' ? record.key : '';
        if (!key) return null;
        return { key, value: record.value === undefined || record.value === null ? '' : String(record.value) };
      })
      .filter((item): item is FlinkCustomConfigItem => item !== null);
  }
  if (typeof input === 'object') {
    return Object.entries(input as Record<string, unknown>).map(([key, value]) => ({
      key,
      value: value === undefined || value === null ? '' : String(value)
    }));
  }
  return [];
};

/** 解析 ```flinksql-job 围栏内的 JSON；失败返回 null（降级纯文本渲染） */
export const parseFlinkJobDraft = (raw: string): FlinkJobDraft | null => {
  if (!raw) return null;
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    const parsed = JSON.parse(raw.slice(start, end + 1)) as Record<string, unknown>;
    const job = (parsed.flinksqlJob as Record<string, unknown>) ?? parsed;
    const sql = typeof job.sql === 'string' ? job.sql : undefined;
    if (!sql) return null;
    return {
      name: typeof job.name === 'string' ? job.name : undefined,
      sql,
      customConfig: normalizeCustomConfig(job.customConfig),
      ddl: Array.isArray(job.ddl) ? (job.ddl as FlinkDdlItem[]) : [],
      precheck: Array.isArray(job.precheck) ? (job.precheck as FlinkPrecheckItem[]) : []
    };
  } catch {
    return null;
  }
};
