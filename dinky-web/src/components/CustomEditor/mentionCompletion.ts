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
import type {
  MentionCandidatePayload,
  MentionScope,
  MentionScopeName
} from '@/pages/DataStudio/Toolbar/AiChat/mentionIndex';
import { fetchMentionDetail, listFlinkSqlEnvs, listMentionTopics } from '@/pages/DataStudio/Toolbar/AiChat/service';
import { getDataSourceList, showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import { getData } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { buildTenantHeader } from '@/utils/function';
import type { SkillHandleValidation } from '@/types/RegCenter/skill';

/**
 * Skill 编辑器 `@` 句柄的**候选数据层**（不含任何 UI）。
 *
 * <p><b>为什么把数据与 UI 拆开</b>：AI Chat 的 `@` 之所以"丝滑"，是因为浮层的开合、键盘、插入
 * 全由它自己掌控（`AiChat/index.tsx` 的 `mentionOpen` 状态 + `handleInputKeyDown` + `pickMention`）。
 * 而在 Monaco 里如果走 `registerCompletionItemProvider`，开合与过滤都归 Monaco 的 suggest widget
 * 管——`quickSuggestions` / `suggestOnTriggerCharacters` / provider 的 Promise 状态任何一个不对，
 * 表现就是"不弹 / 一直 Loading / No suggestions"，从业务侧既看不到也改不动（UAT 2026-10-30 连续四轮）。
 *
 * <p>所以这里只负责"给定 query 返回候选数组"，由 `mentionController.ts` 用 Monaco 的
 * **content widget** 自己渲染、自己接管键盘，等于把 AI Chat 那套状态机原样搬到编辑器里。
 *
 * <p><b>与 AI Chat 同源</b>：单级类型走 `/api/ai/mention/candidates` 的列式快照 + `scoreMention`；
 * 路径式走数据源清单 + `showDataSourceTable` 的库表树。**禁止另写查询**。
 */

/** 候选条目（UI 无关） */
export type MentionItem = {
  /** 唯一标识（React key 用） */
  id: string;
  /** prefix = 裸 `@` 下的类型入口（`@source/` 这类）；resource = 具体资源 */
  kind: 'prefix' | 'resource';
  /** 协议类型：source / skill / doc / kb / semantic / topic / env */
  type: string;
  /** 稳定寻址（不含类型前缀）；prefix 项为类型名本身 */
  code: string;
  /** 主显示文本 */
  title: string;
  /** 右侧说明（分组 / 描述） */
  right?: string | null;
  /** true = 中间层级：选中后补 `/` 并保持面板打开 */
  chainable: boolean;
};

/** 单级类型（无路径下钻） */
const SINGLE_LEVEL_TYPES = ['skill', 'doc', 'kb'];

/** 裸 `@` 下的类型入口（顺序即展示顺序） */
const PREFIX_ENTRIES: { type: string; hint: string }[] = [
  { type: 'source', hint: '数据源 / 库 / 表' },
  { type: 'skill', hint: '技能' },
  { type: 'doc', hint: '知识文档' },
  { type: 'kb', hint: '知识库集合' },
  { type: 'semantic', hint: '语义项目' },
  { type: 'topic', hint: 'Kafka Topic' },
  { type: 'env', hint: 'Flink 环境' }
];

/** 候选条数上限（再多用户也不会翻） */
const MAX_ITEMS = 60;

/** 候选快照的本地 TTL：与后端 60s 进程内快照对齐 */
const CANDIDATE_TTL = 60_000;

/** 候选快照请求超时 */
const CANDIDATE_TIMEOUT = 1500;

/** 元数据（数据源清单 / 库表树）请求超时：可能要连数据源，给得更宽 */
const METADATA_TIMEOUT = 3000;

/**
 * 给请求加超时：面板可以短暂空一下，但绝不能卡住用户的输入。
 */
const withTimeout = async <T>(promise: Promise<T>, ms: number, fallback: T): Promise<T> => {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<T>((resolve) => {
        timer = setTimeout(() => resolve(fallback), ms);
      })
    ]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
};

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

/** 最近一次候选加载的诊断信息（空 = 正常）；UI 在候选为空时展示它 */
export let lastMentionDiagnostic = '';

async function httpGetJson(url: string): Promise<any> {
  const res = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: { ...buildTenantHeader() }
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/** 拉一次候选快照（60s 内复用；失败也缓存空结果，避免每次按键都重试） */
async function loadCandidates(): Promise<CandidateItem[]> {
  const now = Date.now();
  if (candidateCache && now - candidateCache.at < CANDIDATE_TTL) {
    return toItems(candidateCache.payload);
  }
  try {
    let body: any = await withTimeout(httpGetJson(API_CONSTANTS.AI_MENTION_CANDIDATES), CANDIDATE_TIMEOUT, null);
    if (!body) {
      // 原生 fetch 不通时再走一次 umi-request（两条链路互备）
      body = await withTimeout(getData(API_CONSTANTS.AI_MENTION_CANDIDATES), CANDIDATE_TIMEOUT, null);
    }
    if (!body) {
      lastMentionDiagnostic = '候选接口无响应（fetch + umi 均超时）';
      candidateCache = { at: now, payload: {} };
    } else {
      const payload = (body?.data ?? body ?? {}) as MentionCandidatePayload;
      lastMentionDiagnostic = payload?.codes?.length ? '' : '候选接口返回 0 条（检查是否有可见的 skill/doc/kb）';
      candidateCache = { at: now, payload };
    }
  } catch (e: any) {
    lastMentionDiagnostic = `候选接口失败：${e?.message ?? e}`;
    candidateCache = { at: now, payload: {} };
  }
  return toItems(candidateCache.payload);
}

/** 预热候选快照（编辑器挂载时调一次，第一个 `@` 就不用等网络） */
export function primeMentionCandidates(): void {
  void loadCandidates();
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
    dataSourceCache = (await withTimeout(getDataSourceList(), METADATA_TIMEOUT, [])) ?? [];
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
    const tree = await withTimeout(showDataSourceTable(dataSource.id), METADATA_TIMEOUT, null);
    if (tree) {
      sourceTreeCache.set(dataSource.name, tree as any[]);
      return tree as any[];
    }
  } catch (e) {
    // 失败不缓存：下一次输入重试（缓存空数组会让整个会话都拿不到库表）
  }
  return [];
}

/** 数据源名大小写不敏感解析（`@source/STS-lpdw/` 这类手打时大小写常不一致） */
function resolveDataSource(list: any[], typed: string): any | undefined {
  const keyword = (typed ?? '').toLowerCase();
  return list.find((item) => `${item?.name ?? ''}`.toLowerCase() === keyword);
}

/**
 * 只有类型名、还没有分隔符时（如 `@source`）也按该类型收敛。
 *
 * <p>共享的 `parseMentionScope` 只认 `type/xxx`；编辑器里用户常先敲完整类型名再想库名，
 * 没有这段兜底就会落到"跨类型模糊匹配"，关键字 `source` 匹配不到任何 code → 空列表
 * （UAT 2026-10-30 实测，正是这一条）。
 */
function bareTypeScope(query: string): MentionScope | null {
  const name = (query ?? '').trim().toLowerCase();
  const names: MentionScopeName[] = ['source', 'skill', 'doc', 'kb', 'semantic', 'topic', 'env'];
  if (!names.includes(name as MentionScopeName)) {
    return null;
  }
  return { scope: name as MentionScopeName, keyword: '', trailing: false, parts: [] };
}

/**
 * 光标是否在**未闭合**的围栏代码块里（里面的 `@` 是示例，不该弹候选）。
 */
export function insideFencedCode(model: Monaco.editor.ITextModel, position: Monaco.Position): boolean {
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

const prefixItems = (): MentionItem[] =>
  PREFIX_ENTRIES.map((entry) => ({
    id: `prefix-${entry.type}`,
    kind: 'prefix',
    type: entry.type,
    code: entry.type,
    title: `@${entry.type}/`,
    right: entry.hint,
    chainable: false
  }));

/**
 * 按 `@` 之后的 query 收集候选（UI 无关，供 content widget 渲染）。
 *
 * @param query `@` 之后的全部文本（不含 `@`），例如 `''`、`source/`、`source/meta-dolphin/`
 */
export async function collectMentionItems(query: string): Promise<MentionItem[]> {
  // `@table/` `@column/` 已下线（编辑器没有"当前 schema"上下文，硬给会给出用户预期外的表）
  if (/^(table|column)[-/]/i.test((query ?? '').trim())) {
    return [
      {
        id: 'legacy-table',
        kind: 'resource',
        type: 'source',
        code: '',
        title: '@table/ 已下线',
        right: '请改用 @source/<数据源>/<库>/<表>',
        chainable: false
      }
    ];
  }
  const scope = parseMentionScope(query) ?? bareTypeScope(query);
  if (!scope) {
    // 无前缀：类型入口 + 跨类型模糊匹配（与 AI Chat 的空输入体验一致）
    const items = await loadCandidates();
    const keyword = (query ?? '').trim();
    const matched = items
      .filter((it) => SINGLE_LEVEL_TYPES.includes(it.type) || it.type === 'semantic')
      .map((it) => ({ it, score: scoreMention([it.code, it.title, it.subtitle ?? ''], keyword) }))
      .filter(({ score }) => score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, MAX_ITEMS)
      .map(({ it }) => ({
        id: `${it.type}:${it.code}`,
        kind: 'resource' as const,
        type: it.type,
        code: it.code,
        title: it.code,
        right: it.subtitle ?? it.title,
        chainable: false
      }));
    return [...prefixItems(), ...matched];
  }

  // `@table/` 已下线（`column` 不在 MentionScopeName 里，只会走无前缀分支）
  if (scope.scope === 'table') {
    return [
      {
        id: 'legacy-table',
        kind: 'resource',
        type: 'source',
        code: '',
        title: '@table/ 已下线',
        right: '请改用 @source/<数据源>/<库>/<表>',
        chainable: false
      }
    ];
  }

  if (scope.scope === 'source') {
    return sourceItems(scope);
  }

  if (scope.scope === 'topic' || scope.scope === 'env') {
    return rosterItems(scope);
  }

  const items = await loadCandidates();
  const wanted = scope.scope;
  if (wanted === 'semantic' && scope.parts.length > 1) {
    // 项目内资产未预取（C1 边界），编辑器只到项目层
    return [];
  }
  const keyword = scope.keyword;
  return items
    .filter((it) => it.type === wanted)
    .map((it) => ({ it, score: scoreMention([it.code, it.title, it.subtitle ?? ''], keyword) }))
    .filter(({ score }) => score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, MAX_ITEMS)
    .map(({ it }) => ({
      id: `${it.type}:${it.code}`,
      kind: 'resource' as const,
      type: it.type,
      code: it.code,
      title: it.code,
      right: it.subtitle ?? it.title,
      chainable: false
    }));
}

/** `@source/` 路径下钻：数据源（chainable）→ 库（chainable）→ 表（叶子） */
async function sourceItems(scope: MentionScope): Promise<MentionItem[]> {
  const parts = scope.parts;
  const sources = await loadDataSources();
  if (sources.length === 0) {
    lastMentionDiagnostic = '数据源清单为空（可能是权限 / 租户无数据源）';
  }
  // 第一层：数据源名
  if (parts.length === 0 || (parts.length === 1 && !scope.trailing)) {
    const keyword = (parts[0] ?? '').toLowerCase();
    return sources
      .filter((ds) => !keyword || `${ds?.name ?? ''}`.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((ds) => ({
        id: `source:${ds.name}`,
        kind: 'resource' as const,
        type: 'source' as const,
        code: ds.name,
        title: ds.name,
        right: ds.type ?? '数据源',
        chainable: true
      }));
  }
  const dataSource = resolveDataSource(sources, parts[0] ?? '');
  if (!dataSource) {
    lastMentionDiagnostic = `本租户下没有数据源「${parts[0]}」`;
    return [];
  }
  const tree = await loadSourceTree(dataSource);
  const dsName = `${dataSource.name}`;
  // 第二层：库名
  if ((scope.trailing && parts.length === 1) || (!scope.trailing && parts.length === 2)) {
    const keyword = (scope.trailing ? '' : parts[1] ?? '').toLowerCase();
    return tree
      .filter((schema) => !keyword || `${schema?.name ?? ''}`.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((schema) => ({
        id: `source:${dsName}/${schema.name}`,
        kind: 'resource' as const,
        type: 'source' as const,
        code: `${dsName}/${schema.name}`,
        title: schema.name,
        right: dsName,
        chainable: true
      }));
  }
  // 第三层：表名（叶子）
  const dbKeyword = `${parts[1] ?? ''}`.toLowerCase();
  const schema = tree.find((entry) => `${entry?.name ?? ''}`.toLowerCase() === dbKeyword);
  if (!schema) {
    lastMentionDiagnostic = `数据源「${dsName}」下没有库「${parts[1]}」`;
    return [];
  }
  const keyword = (scope.trailing ? '' : parts[2] ?? '').toLowerCase();
  return (schema?.tables ?? [])
    .filter((table: any) => !keyword || `${table?.name ?? ''}`.toLowerCase().includes(keyword))
    .slice(0, MAX_ITEMS)
    .map((table: any) => ({
      id: `source:${dsName}/${schema.name}/${table.name}`,
      kind: 'resource' as const,
      type: 'source' as const,
      code: `${dsName}/${schema.name}/${table.name}`,
      title: table.name,
      right: `${dsName}/${schema.name}`,
      chainable: false
    }));
}

/** `@topic/` `@env/`：名册接口（零连接） */
async function rosterItems(scope: MentionScope): Promise<MentionItem[]> {
  const keyword = (scope.keyword ?? '').toLowerCase();
  if (scope.scope === 'env') {
    if (!envCache) {
      try {
        const envs = await withTimeout(listFlinkSqlEnvs(), METADATA_TIMEOUT, []);
        envCache = (envs ?? []).map((env) => env.name);
      } catch (e) {
        envCache = [];
      }
    }
    return (envCache ?? [])
      .filter((name) => !keyword || name.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((name) => ({
        id: `env:${name}`,
        kind: 'resource' as const,
        type: 'env' as const,
        code: name,
        title: name,
        right: 'Flink 环境',
        chainable: false
      }));
  }
  if (!topicCache) {
    try {
      topicCache = (await withTimeout(listMentionTopics(), METADATA_TIMEOUT, [])) ?? [];
    } catch (e) {
      topicCache = [];
    }
  }
  return (topicCache ?? [])
    .filter((name) => !keyword || name.toLowerCase().includes(keyword))
    .slice(0, MAX_ITEMS)
    .map((name) => ({
      id: `topic:${name}`,
      kind: 'resource' as const,
      type: 'topic' as const,
      code: name,
      title: name,
      right: 'Kafka Topic',
      chainable: false
    }));
}

/**
 * 把校验结果画成 marker（独立 owner，不干扰既有 marker）。
 *
 * <p><b>只标 explicit</b>：裸写是历史兼容形态，逐个画波浪线会把编辑器变成一片黄色；
 * bare 的提示统一由调用方汇总成一条 info。
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
  options: { languageId: string }
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
        // fetchMentionDetail 复用 AI Chat 条目形态：name = code，content = 摘要
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
