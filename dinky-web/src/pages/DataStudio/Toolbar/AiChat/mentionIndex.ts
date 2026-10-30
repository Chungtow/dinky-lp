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

export type MentionCandidatePayload = {
  /** 内容指纹（服务端 ETag，客户端暂不参与协商，留作后续增量拉取） */
  etag?: string;
  count?: number;
  /** `prefetch`（无 q 的全量） / `search`（带 q 的服务端过滤） */
  mode?: string;
  types?: string[];
  codes?: string[];
  titles?: string[];
};

/**
 * 可作「类型前缀」的 scope 名。
 *
 * <p>与后端 {@code MentionType} 的协议值逐一对应；`source` / `semantic` 是<b>路径式</b>
 * （选中会补 `/` 并继续下钻），其余是单级。
 */
export type MentionScopeName =
  | 'source'
  | 'semantic'
  | 'kb'
  | 'skill'
  | 'doc'
  | 'table'
  | 'topic'
  | 'env';

export type MentionScope = {
  scope: MentionScopeName;
  /** 前缀之后的剩余串（不含前缀本身） */
  keyword: string;
  /** 是否以 `/` 结尾（路径式下钻：`source/ds/db/` 表示"库已选定，要看表"） */
  trailing: boolean;
  /** 剩余串按 `/` 切分后的层级（路径式类型用） */
  parts: string[];
};

const SCOPE_NAMES: MentionScopeName[] = [
  'source',
  'semantic',
  'kb',
  'skill',
  'doc',
  'table',
  'topic',
  'env'
];

/** `source/xxx`、`skill-xxx`（兼容 2026-10-06 之前的连字符写法） */
const SCOPE_PATTERN = /^([A-Za-z]+)[-/]([^\s]*)$/;

/**
 * 解析类型前缀，实现「输入具体类型时收敛到该类型的候选」。
 *
 * @param query 输入框里 `@` 之后的全文（不含 `@`）
 * @returns 命中已知前缀返回 scope，否则返回 `null`（= 无前缀，跨类型混合匹配）
 */
export function parseMentionScope(query: string): MentionScope | null {
  const matched = SCOPE_PATTERN.exec((query ?? '').trim());
  if (!matched) {
    return null;
  }
  const scope = matched[1].toLowerCase() as MentionScopeName;
  if (!SCOPE_NAMES.includes(scope)) {
    return null;
  }
  const raw = matched[2] ?? '';
  return {
    scope,
    keyword: raw,
    trailing: raw.endsWith('/'),
    parts: raw.split('/').filter((part) => part !== '')
  };
}

/**
 * 单个字段的打分。
 *
 * <p>优先级：全等 &gt; 前缀 &gt; 子串 &gt; 子序列。子序列用于 `ord` 命中 `ods_order_detail`
 * 这类"缩写式"检索（与 CodeBuddy / fzf 同类），但<b>分数低于子串</b>，
 * 避免缩写命中盖过真正的词首命中。
 */
function scoreOne(text: string, query: string): number {
  if (text === query) {
    return 1000;
  }
  if (text.startsWith(query)) {
    return 800 - Math.min(text.length, 100);
  }
  const at = text.indexOf(query);
  if (at >= 0) {
    // 中文没有"词首"概念，子串命中即视为良好匹配；越靠前越好
    return 600 - Math.min(at, 200);
  }
  let last = -1;
  let gaps = 0;
  for (const ch of query) {
    const pos = text.indexOf(ch, last + 1);
    if (pos < 0) {
      return 0;
    }
    if (last >= 0) {
      gaps += pos - last - 1;
    }
    last = pos;
  }
  return Math.max(1, 400 - gaps * 5 - Math.min(text.length, 100));
}

/**
 * 多字段打分（取最高分）。
 *
 * <p>为什么要多字段：kb 候选的 {@code name} 是库编码（{@code c_1a2b3c4d}）、显示名在
 * {@code content} 里，只匹配 name 会让"按中文库名搜不到"；skill/doc 的
 * {@code content} 是描述，命中描述也算有用。
 */
export function scoreMention(fields: (string | undefined | null)[], query: string): number {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) {
    return 1;
  }
  let best = 0;
  for (const field of fields) {
    if (!field) {
      continue;
    }
    const score = scoreOne(field.toLowerCase(), q);
    if (score > best) {
      best = score;
    }
  }
  return best;
}

/** 列式索引的条目数（三列长度取最小值，防御性写法） */
export function mentionIndexSize(index: MentionCandidatePayload | null | undefined): number {
  if (!index) {
    return 0;
  }
  return Math.min(index.codes?.length ?? 0, Math.max(index.types?.length ?? 0, index.titles?.length ?? 0));
}
