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
import {
  fetchMentionCandidates,
  fetchMentionDetail,
  listFlinkSqlEnvs,
  listMentionTopics,
  listPublishedSemanticAssets
} from '@/pages/DataStudio/Toolbar/AiChat/service';
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

/**
 * 候选拉取**失败**后的重试退避（毫秒）。
 *
 * <p>早期失败也会写 60s 空缓存，导致网络抖一下编辑器整整一分钟"什么候选都没有"，
 * 且表象与"后端没数据"完全一样，极难排查。现在：失败只说"这次没有"，5s 后按键即重试；
 * 成功才写 {@link CANDIDATE_TTL} 缓存。
 */
const CANDIDATE_FAIL_BACKOFF = 5_000;

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

/** 上次候选拉取失败的时间戳（0 = 无失败记录）；用于短期退避，避免每次按键都重试 */
let candidateFailAt = 0;

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
    // 候选是用户态数据，且服务端已声明 `Cache-Control: no-store`；这里再显式关掉
    // 浏览器缓存，避免历史缓存条目（旧 ETag）在修复后仍被复用。
    cache: 'no-store',
    headers: { ...buildTenantHeader() }
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/** 拉一次候选快照（成功缓存 60s；失败只退避 5s，不再写空缓存） */
async function loadCandidates(): Promise<CandidateItem[]> {
  const now = Date.now();
  if (candidateCache && now - candidateCache.at < CANDIDATE_TTL) {
    return toItems(candidateCache.payload);
  }
  // 失败退避窗口内直接返回空：避免每次按键都打一次注定失败的请求，
  // 同时不像旧实现那样把"空"锁死 60s。
  if (candidateFailAt && now - candidateFailAt < CANDIDATE_FAIL_BACKOFF) {
    return [];
  }
  try {
    let body: any = await withTimeout(httpGetJson(API_CONSTANTS.AI_MENTION_CANDIDATES), CANDIDATE_TIMEOUT, null);
    if (!body) {
      // 原生 fetch 不通时再走一次 umi-request（两条链路互备）
      body = await withTimeout(getData(API_CONSTANTS.AI_MENTION_CANDIDATES), CANDIDATE_TIMEOUT, null);
    }
    if (!body) {
      lastMentionDiagnostic = '候选接口无响应（fetch + umi 均超时）';
      candidateFailAt = Date.now();
    } else {
      const payload = (body?.data ?? body ?? {}) as MentionCandidatePayload;
      lastMentionDiagnostic = payload?.codes?.length ? '' : '候选接口返回 0 条（检查是否有可见的 skill/doc/kb）';
      candidateCache = { at: now, payload };
      candidateFailAt = 0;
    }
  } catch (e: any) {
    lastMentionDiagnostic = `候选接口失败：${e?.message ?? e}`;
    candidateFailAt = Date.now();
  }
  return candidateCache ? toItems(candidateCache.payload) : [];
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
    const matched: MentionItem[] = items
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
        // 语义项目是中间层级（选中继续列资产）；其余单级类型选中即完成
        chainable: it.type === 'semantic'
      }));
    // C1.5：表名服务端搜索（表不在预取索引里，≥2 字符才触发；失败静默，不影响其它候选）
    const tables = await searchTableItems(keyword);
    const merged = tables.filter((item) => !matched.some((hit) => hit.code === item.code));
    return [...prefixItems(), ...matched, ...merged].slice(0, MAX_ITEMS + PREFIX_ENTRIES.length);
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
  if (wanted === 'semantic') {
    // 路径式下钻（项目 → 资产）：与 AI Chat 同一份数据、同一套同名消歧规则
    return semanticItems(scope);
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

/** 语义项目内资产（与 AI Chat 的 `semanticAssets` 同形：`name` + `kind`） */
type SemanticAssetItem = {
  /** 资产名（句柄的最后一段） */
  name: string;
  /** 资产类别：决定同名消歧时用 `table` 还是 `metric` */
  kind: 'table' | 'metric';
  /** 一句话说明（右侧提示，没有就不显示） */
  oneLiner?: string;
};

/** 语义项目 → 资产清单缓存（60s，与候选快照同口径） */
const semanticAssetCache = new Map<string, { at: number; items: SemanticAssetItem[] }>();

/**
 * 读取某个已发布项目的资产清单（`@semantic/<项目>/` 的第二级）。
 *
 * <p><b>为什么必须懒加载</b>：后端 `SemanticProjectProvider` 只预取<b>项目级</b>——
 * 每个项目的资产清单都要读一次 HDFS（N 个项目 = N 次），不能塞进 60s 快照
 * （`SemanticProjectProvider:41-46`）。用户敲到 `/` 之后才拉，且按项目缓存。
 */
async function loadSemanticAssets(project: string): Promise<SemanticAssetItem[]> {
  const cached = semanticAssetCache.get(project);
  if (cached && Date.now() - cached.at < 60_000) {
    return cached.items;
  }
  const rows = await withTimeout(listPublishedSemanticAssets(project), METADATA_TIMEOUT, [] as any[]);
  const items: SemanticAssetItem[] = (rows ?? [])
    .filter((row: any) => `${row?.name ?? ''}`.trim() !== '')
    .map((row: any) => ({
      name: `${row.name}`.trim(),
      kind: `${row?.type ?? ''}`.toUpperCase() === 'METRIC' ? ('metric' as const) : ('table' as const),
      oneLiner: `${row?.oneLiner ?? ''}`.trim() || undefined
    }));
  if (items.length > 0) {
    semanticAssetCache.set(project, { at: Date.now(), items });
  }
  return items;
}

/**
 * `@semantic/` 路径下钻：**已发布项目（chainable）→ 项目内资产（叶子）**。
 *
 * <p><b>口径与 AI Chat 逐条一致</b>（用户 2026-10-31 原则）：同一份资产接口
 * （`/api/semantic/published/assets`）、同一套同名消歧规则——表与指标同名时生成长式句柄
 * `<项目>/table|metric/<名>`，否则用短式 `<项目>/<名>`（对照 `AiChat/index.tsx:1060-1075`）。
 *
 * <p><b>修掉的两个缺陷</b>：
 *
 * <ol>
 *   <li>`@semantic/<项目>/`（带尾斜杠）此前会落到通用打分分支，关键字里带着 `/` 必然 0 分 → 面板空白；
 *   <li>`@semantic/<项目>/<资产>` 此前被 `parts.length > 1` 硬拦成 `[]`（C1 边界）。
 * </ol>
 */
async function semanticItems(scope: MentionScope): Promise<MentionItem[]> {
  const parts = scope.parts;
  const projects = (await loadCandidates()).filter((it) => it.type === 'semantic');
  // 第一层：项目名（含"在项目名上继续筛选"）
  if (parts.length === 0 || (parts.length === 1 && !scope.trailing)) {
    const keyword = `${parts[0] ?? ''}`.toLowerCase();
    return projects
      .filter((it) => !keyword || it.code.toLowerCase().includes(keyword))
      .slice(0, MAX_ITEMS)
      .map((it) => ({
        id: `semantic:${it.code}`,
        kind: 'resource' as const,
        type: 'semantic' as const,
        code: it.code,
        title: it.code,
        right: it.subtitle ?? null,
        // 项目是中间层级：选中补 `/` 并保持面板打开，继续列资产（与 AI Chat 一致）
        chainable: true
      }));
  }
  const projectName = `${parts[0] ?? ''}`;
  const project =
    projects.find((it) => it.code === projectName) ??
    projects.find((it) => it.code.toLowerCase() === projectName.toLowerCase());
  if (!project) {
    lastMentionDiagnostic = `已发布语义项目里没有「${projectName}」`;
    return [];
  }
  const assets = await loadSemanticAssets(project.code);
  if (assets.length === 0) {
    lastMentionDiagnostic = `项目「${project.code}」下没有可引用资产（或资产索引未就绪 / 无 semantic:view 权限）`;
    return [];
  }
  // 第二层：资产（`<项目>/` = 全部；`<项目>/<kw>` = 过滤；`<项目>/table|metric/...` = 长式）
  const rest = parts.slice(1);
  let kind: '' | 'table' | 'metric' = '';
  let keyword = scope.trailing ? '' : `${rest[0] ?? ''}`;
  if (rest.length > 0 && /^(table|metric)$/i.test(rest[0])) {
    kind = rest[0].toLowerCase() as 'table' | 'metric';
    keyword = scope.trailing ? '' : `${rest[1] ?? ''}`;
  }
  const lowered = keyword.toLowerCase();
  const counts = new Map<string, number>();
  assets.forEach((asset) => counts.set(asset.name, (counts.get(asset.name) ?? 0) + 1));
  return assets
    .filter((asset) => !kind || asset.kind === kind)
    .filter((asset) => !lowered || asset.name.toLowerCase().includes(lowered))
    .slice(0, MAX_ITEMS)
    .map((asset) => {
      // 同名（表与指标）或用户已经在长式路径上：句柄要带上类别段，否则两边不可区分
      const longForm = kind !== '' || (counts.get(asset.name) ?? 0) > 1;
      const code = longForm ? `${project.code}/${asset.kind}/${asset.name}` : `${project.code}/${asset.name}`;
      return {
        id: `semantic:${code}`,
        kind: 'resource' as const,
        type: 'semantic' as const,
        code,
        title: asset.name,
        right: asset.oneLiner ?? (asset.kind === 'metric' ? '指标' : '表'),
        chainable: false
      };
    });
}

/**
 * C1.5 轻量表名搜索（2026-10-31）：表是万级、不进预取索引，只能按需问后端。
 *
 * <p>命中项仍是 `source` 类型（`code=<数据源>/<库>/<表>`），因此插入的是
 * `@source/<ds>/<db>/<tbl>`——与手工路径式下钻完全同构，校验 / 装配都不需要新分支。
 */
async function searchTableItems(keyword: string): Promise<MentionItem[]> {
  if (keyword.length < 2) {
    return [];
  }
  const payload: any = await withTimeout(
    fetchMentionCandidates(undefined, keyword, 10),
    METADATA_TIMEOUT,
    null
  );
  const types: string[] = payload?.types ?? [];
  const codes: string[] = payload?.codes ?? [];
  const titles: string[] = payload?.titles ?? [];
  const subs: string[] = payload?.subs ?? [];
  const items: MentionItem[] = [];
  for (let i = 0; i < codes.length; i += 1) {
    // 只保留表命中：快照类命中（skill/doc/semantic/kb）本地已有一份，重复出现会干扰排序
    if (`${types[i] ?? ''}` !== 'source' || !codes[i]) {
      continue;
    }
    items.push({
      id: `source:${codes[i]}`,
      kind: 'resource' as const,
      type: 'source' as const,
      code: codes[i],
      title: titles[i] || codes[i],
      right: subs[i] ?? '跨数据源表',
      chainable: false
    });
  }
  return items;
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
