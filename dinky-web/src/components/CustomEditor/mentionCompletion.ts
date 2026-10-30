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

import type * as Monaco from 'monaco-editor';
import { findMentionToken } from '@/pages/DataStudio/Toolbar/AiChat/mentionPath';
import { parseMentionScope, scoreMention } from '@/pages/DataStudio/Toolbar/AiChat/mentionIndex';
import type { MentionCandidatePayload } from '@/pages/DataStudio/Toolbar/AiChat/mentionIndex';
import {
  fetchMentionCandidates,
  fetchMentionDetail,
  listFlinkSqlEnvs,
  listMentionTopics
} from '@/pages/DataStudio/Toolbar/AiChat/service';
import { getDataSourceList, showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import type { SkillHandleValidation } from '@/types/RegCenter/skill';

/**
 * Skill 编辑器里的 `@` 句柄补全 / 悬停 / 校验标记（E1~E3）。
 *
 * <p><b>为什么用 Monaco 原生补全 widget 而不是自建浮层</b>：AI Chat 的浮层与 20+ 组件内 state 耦合
 * （`AiChat/index.tsx:2451-2541`），抽取成本中高；而 Monaco 的补全 widget 免费提供键盘交互
 * （↑↓ / Enter / Esc / 滚动定位）。本批只把「候选从哪来、插入什么」接上，等于复用 C1 的成果。
 *
 * <p><b>候选与 AI Chat 同源</b>：单级类型（skill/doc/kb/semantic/topic/env）走
 * `/api/ai/mention/candidates` 的列式快照 + `scoreMention` 打分；路径式（`@source/`）走
 * 数据源清单 + `showDataSourceTable` 的库表树。**禁止另写查询**——两处口径不一致时，
 * 用户会先看到"编辑器里有、AI Chat 里没有"（或反之）的候选。
 *
 * <p><b>双语法</b>：叶子节点插入 `` `@type/code` ``（explicit，规范写法）；中间层级只插入
 * `@source/<ds>/`（不带反引号），否则"继续下钻"会被闭合反引号打断。
 *
 * <p><b>`@table/` / `@column/` 不注册</b>（计划 §3.6）：编辑器没有「当前面板 schema」上下文，
 * 硬给会产出用户预期之外的候选；输入这两个前缀时只给一条"请改用路径式"的提示项。
 */

export type MentionCompletionOptions = {
  /** 只在指定语言上生效（skill 正文固定为 `markdown`） */
  languageId: string;
};

/** 单级类型（无路径下钻，选中即叶子） */
const SINGLE_LEVEL_TYPES = ['skill', 'doc', 'kb'];

/** 候选项最大条数（再多用户也不会翻） */
const MAX_ITEMS = 60;

/** 候选快照的本地 TTL：与后端 60s 进程内快照对齐，避免每次按键都打接口 */
const CANDIDATE_TTL = 60_000;

type CandidateItem = {
  type: string;
  code: string;
  title: string;
  subtitle?: string | null;
};

let candidateCache: { at: number; payload: MentionCandidatePayload } | null = null;

let dataSourceCache: any[] | null = null;

const sourceTreeCache = new Map<string, any[]>();

let topicCache: string[] | null = null;

let envCache: string[] | null = null;

/** 拉一次候选快照（60s 内复用；失败也缓存空结果，避免每次按键都重试） */
async function loadCandidates(): Promise<CandidateItem[]> {
  const now = Date.now();
  if (candidateCache && now - candidateCache.at < CANDIDATE_TTL) {
    return toItems(candidateCache.payload);
  }
  try {
    const payload = await fetchMentionCandidates();
    candidateCache = { at: now, payload: payload ?? {} };
  } catch (e) {
    candidateCache = { at: now, payload: {} };
  }
  return toItems(candidateCache.payload);
}

/** 列式快照 → 条目数组（列对齐，缺列时降级为 code 作 title） */
function toItems(payload: MentionCandidatePayload): CandidateItem[] {
  const types = payload.types ?? [];
  const codes = payload.codes ?? [];
  const titles = payload.titles ?? [];
  const subs = payload.subs ?? [];
  const items: CandidateItem[] = [];
  for (let i = 0; i < codes.length; i += 1) {
    const code = codes[i];
    if (!code) {
      continue;
    }
    items.push({
      type: types[i] ?? '',
      code,
      title: titles[i] || code,
      subtitle: subs[i] ?? null
    });
  }
  return items;
}

async function loadDataSources(): Promise<any[]> {
  if (dataSourceCache) {
    return dataSourceCache;
  }
  try {
    dataSourceCache = (await getDataSourceList()) ?? [];
  } catch (e) {
    dataSourceCache = [];
  }
  return dataSourceCache;
}

async function loadSourceTree(dataSource: any): Promise<any[]> {
  const cached = sourceTreeCache.get(dataSource.name);
  if (cached) {
    return cached;
  }
  try {
    const tree = await showDataSourceTable(dataSource.id);
    if (tree) {
      sourceTreeCache.set(dataSource.name, tree as any[]);
      return tree as any[];
    }
  } catch (e) {
    // 失败不缓存：下一次输入重试（缓存空数组会让整个会话都拿不到库表）
  }
  return [];
}

/** 数据源名大小写不敏感解析（`@source/STS-lpdw/` 这类名字手打时大小写常不一致） */
function resolveDataSource(list: any[], typed: string): any | undefined {
  const keyword = (typed ?? '').toLowerCase();
  return list.find((item) => `${item?.name ?? ''}`.toLowerCase() === keyword);
}

/**
 * 光标是否在**未闭合**的围栏代码块里。
 *
 * <p>代码块里的 `@` 多是 SQL 示例或注释，弹候选会打断书写（计划风险 10）。
 * 判据：光标之前出现的 ``` 个数为奇数。
 */
function insideFencedCode(model: Monaco.editor.ITextModel, position: Monaco.Position): boolean {
  const offset = model.getOffsetAt(position);
  const before = model.getValue().slice(0, offset);
  const fences = before.match(/```/g);
  return (fences?.length ?? 0) % 2 === 1;
}

/** 正文指纹（保存后校验用它丢弃过期结果，计划风险 8） */
export function hashContent(content: string): string {
  let hash = 2166136261;
  for (let i = 0; i < content.length; i += 1) {
    hash ^= content.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return `${(hash >>> 0).toString(16)}:${content.length}`;
}

/**
 * 注册 `@` 补全 provider。
 *
 * @param monaco        monaco 实例（从 `CodeEdit` 的 `editorDidMount` 拿到）
 * @param options       生效语言
 * @returns disposable：skill tab 卸载时调用（**必须** dispose，否则重开 tab 会叠出多份候选）
 */
export function registerMentionCompletion(
  monaco: typeof Monaco,
  options: MentionCompletionOptions
): Monaco.IDisposable {
  return monaco.languages.registerCompletionItemProvider(options.languageId, {
    triggerCharacters: ['@', '/'],
    provideCompletionItems: async (model, position) => {
      const suggestions = await buildSuggestions(monaco, model, position);
      return { suggestions };
    }
  });
}

async function buildSuggestions(
  monaco: typeof Monaco,
  model: Monaco.editor.ITextModel,
  position: Monaco.Position
): Promise<Monaco.languages.CompletionItem[]> {
  if (insideFencedCode(model, position)) {
    return [];
  }
  const line = model.getLineContent(position.lineNumber);
  const caret = Math.min(position.column - 1, line.length);
  const tokenRange = findMentionToken(line, caret);
  if (!tokenRange) {
    return [];
  }
  // 行内已有闭合反引号时，token 会把末尾的 ` 一起吃进来，这里先剥掉再定位替换区间
  const rawTail = line.slice(tokenRange.start, tokenRange.end);
  const trailingBackticks = rawTail.match(/`+$/)?.[0] ?? '';
  const end = tokenRange.end - trailingBackticks.length;
  const token = rawTail.slice(0, rawTail.length - trailingBackticks.length);
  if (!token.startsWith('@')) {
    // `/` 也能触发 provider，但只有 `@` 开头的 token 才该给候选（计划风险 9）
    return [];
  }
  const query = token.slice(1);
  const scope = parseMentionScope(query);
  const range = new monaco.Range(position.lineNumber, tokenRange.start + 1, position.lineNumber, end + 1);
  const hasOpen = line[tokenRange.start - 1] === '`';
  const hasClose = line[end] === '`';
  const insert = (path: string, leaf: boolean): string => {
    const body = `@${path}`;
    if (!leaf) {
      // 中间层级：不加反引号，用户继续补 `/` 下钻
      return body;
    }
    return `${hasOpen ? '' : '`'}${body}${hasClose ? '' : '`'}`;
  };
  const item = (
    type: string,
    code: string,
    title: string,
    subtitle: string | undefined | null,
    leaf: boolean,
    score: number
  ): Monaco.languages.CompletionItem => ({
    label: { label: code, description: subtitle ?? type },
    kind: monaco.languages.CompletionItemKind.Reference,
    detail: `${type}${subtitle ? ` · ${subtitle}` : ''}`,
    documentation: subtitle ?? undefined,
    insertText: insert(`${type}/${code}`, leaf),
    filterText: title,
    sortText: `${String(MAX_ITEMS - Math.min(score, MAX_ITEMS)).padStart(3, '0')}`,
    range
  });

  // `@table/` `@column/` 已下线：`column` 不在 MentionScopeName 里，用原文前缀判断
  if (/^(table|column)[-/]/.test(query)) {
    // 历史类型：编辑器没有 schema 上下文，宁可不给候选（计划 §3.6）
    return [
      {
        label: { label: '@table/ @column/ 已下线', description: '请改用路径式引用' },
        kind: monaco.languages.CompletionItemKind.Text,
        detail: '请改用 @source/<数据源>/<库>/<表>',
        insertText: 'source/',
        range
      }
    ];
  }

  if (scope?.scope === 'source') {
    return sourceSuggestions(scope, item);
  }

  if (scope?.scope === 'topic' || scope?.scope === 'env') {
    return rosterSuggestions(scope, item);
  }

  const items = await loadCandidates();
  const keyword = scope ? scope.keyword : query;
  const wanted = scope ? scope.scope : '';
  if (scope?.scope === 'semantic' && scope.parts.length > 1) {
    // 项目内资产未预取（C1 边界），编辑器不做第二层
    return [];
  }
  return items
    .filter((it) => (wanted ? it.type === wanted : SINGLE_LEVEL_TYPES.includes(it.type) || it.type === 'semantic'))
    .map((it) => ({ it, score: scoreMention([it.code, it.title, it.subtitle ?? ''], keyword) }))
    .filter(({ score }) => score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, MAX_ITEMS)
    .map(({ it, score }) => item(it.type, it.code, it.title, it.subtitle, true, score));
}

/** `@source/` 路径下钻：数据源 → 库 → 表（与 AI Chat 的层级判定完全同构） */
async function sourceSuggestions(
  scope: ReturnType<typeof parseMentionScope>,
  item: (
    type: string,
    code: string,
    title: string,
    subtitle: string | undefined | null,
    leaf: boolean,
    score: number
  ) => Monaco.languages.CompletionItem
): Promise<Monaco.languages.CompletionItem[]> {
  if (!scope) {
    return [];
  }
  const sources = await loadDataSources();
  // 第一层：数据源名
  if (scope.parts.length <= 1 && !scope.trailing) {
    const keyword = (scope.parts[0] ?? '').toLowerCase();
    return sources
      .filter((ds) => !keyword || `${ds?.name ?? ''}`.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((ds) => item('source', ds.name, ds.name, ds.type ?? null, false, 800));
  }
  const dataSource = resolveDataSource(sources, scope.parts[0] ?? '');
  if (!dataSource) {
    return [];
  }
  const tree = await loadSourceTree(dataSource);
  const dsName = `${dataSource.name}`;
  // 第二层：库名
  if ((scope.trailing && scope.parts.length === 1) || (!scope.trailing && scope.parts.length === 2)) {
    const keyword = (scope.trailing ? '' : scope.parts[1] ?? '').toLowerCase();
    return tree
      .filter((schema) => !keyword || `${schema?.name ?? ''}`.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((schema) => item('source', `${dsName}/${schema.name}`, schema.name, dsName, false, 800));
  }
  // 第三层：表名（叶子，插入即带反引号）
  if ((scope.trailing && scope.parts.length === 2) || (!scope.trailing && scope.parts.length === 3)) {
    const dbKeyword = `${scope.parts[1] ?? ''}`.toLowerCase();
    const schema = tree.find((entry) => `${entry?.name ?? ''}`.toLowerCase() === dbKeyword);
    const keyword = (scope.trailing ? '' : scope.parts[2] ?? '').toLowerCase();
    return (schema?.tables ?? [])
      .filter((table: any) => !keyword || `${table?.name ?? ''}`.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((table: any) => item('source', `${dsName}/${schema.name}/${table.name}`, `${table.name}`, `${dsName}/${schema.name}`, true, 800));
  }
  return [];
}

/** `@topic/` `@env/`：名册接口（零连接），与 AI Chat 同源 */
async function rosterSuggestions(
  scope: ReturnType<typeof parseMentionScope>,
  item: (
    type: string,
    code: string,
    title: string,
    subtitle: string | undefined | null,
    leaf: boolean,
    score: number
  ) => Monaco.languages.CompletionItem
): Promise<Monaco.languages.CompletionItem[]> {
  if (!scope) {
    return [];
  }
  const keyword = (scope.keyword ?? '').toLowerCase();
  if (scope.scope === 'env') {
    if (!envCache) {
      try {
        envCache = ((await listFlinkSqlEnvs()) ?? []).map((env) => env.name);
      } catch (e) {
        envCache = [];
      }
    }
    return (envCache ?? [])
      .filter((name) => !keyword || name.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((name) => item('env', name, name, null, true, 800));
  }
  if (!topicCache) {
    try {
      topicCache = (await listMentionTopics()) ?? [];
    } catch (e) {
      topicCache = [];
    }
  }
  return (topicCache ?? [])
    .filter((name) => !keyword || name.toLowerCase().includes(keyword))
    .slice(0, MAX_ITEMS)
    .map((name) => item('topic', name, name, null, true, 800));
}

/**
 * 把校验结果画成 marker（独立 owner，不干扰既有 marker）。
 *
 * <p><b>只标 explicit</b>：裸写是历史兼容形态（存量 skill 全是裸写），逐个画波浪线会把编辑器
 * 变成一片黄色；bare 的提示统一由调用方汇总成一条 info。
 */
export function setMentionMarkers(
  monaco: typeof Monaco,
  model: Monaco.editor.ITextModel | null | undefined,
  validation: SkillHandleValidation | null | undefined
): void {
  if (!model) {
    return;
  }
  const markers: Monaco.editor.IMarkerData[] = (validation?.handles ?? [])
    .filter((handle) => handle.syntax === 'explicit' && handle.status !== 'ok')
    .map((handle) => {
      const maxColumn = model.getLineMaxColumn(Math.min(handle.line, model.getLineCount()));
      const startColumn = Math.min(handle.column, maxColumn);
      return {
        severity: monaco.MarkerSeverity.Warning,
        message: handle.message || handle.status,
        startLineNumber: handle.line,
        startColumn,
        endLineNumber: handle.line,
        endColumn: Math.min(startColumn + Math.max(1, handle.raw.length), maxColumn + 1)
      };
    });
  monaco.editor.setModelMarkers(model, 'dinky-mention', markers);
}

/** 注册悬停解析（E3）：命中 `@type/code` 时显示类型 · 名称 · 摘要 */
export function registerMentionHover(
  monaco: typeof Monaco,
  options: MentionCompletionOptions
): Monaco.IDisposable {
  return monaco.languages.registerHoverProvider(options.languageId, {
    provideHover: async (model, position) => {
      const line = model.getLineContent(position.lineNumber);
      const tokenRange = findMentionToken(line, Math.min(position.column - 1, line.length));
      if (!tokenRange) {
        return null;
      }
      const token = line.slice(tokenRange.start, tokenRange.end).replace(/`+$/, '');
      if (!token.startsWith('@')) {
        return null;
      }
      const body = token.slice(1);
      const slashAt = body.indexOf('/');
      if (slashAt <= 0) {
        return null;
      }
      const type = body.slice(0, slashAt).toLowerCase();
      const code = body.slice(slashAt + 1);
      if (!type || !code) {
        return null;
      }
      try {
        const detail = await fetchMentionDetail(type, code);
        if (!detail) {
          return {
            contents: [{ value: `**${type}** \`${code}\` — 资源不存在，或当前用户不可见` }]
          };
        }
        // fetchMentionDetail 的返回是复用 AI Chat 的条目形态：name = code，content = 摘要
        const subtitle = detail.content ? `\n\n${detail.content}` : '';
        return {
          contents: [{ value: `**${type} · ${detail.name || code}**${subtitle}` }]
        };
      } catch (e) {
        return null;
      }
    }
  });
}
