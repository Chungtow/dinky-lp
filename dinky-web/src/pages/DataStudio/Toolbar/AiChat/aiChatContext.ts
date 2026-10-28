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

export type EditorTabType = 'task' | 'dataSource' | 'resource' | 'semanticFile';

/** 编辑区当前对象（绑定模型最内层：先认清「你正在看什么」） */
export interface EditorContext {
  tabId: string;
  tabType: EditorTabType;
  /** tab 标题（仅用于摘要行展示） */
  tabTitle?: string;
  /** 作业 tab */
  taskId?: number;
  /** 数据源/表 tab、以及作业自带的数据源 */
  databaseId?: number;
  schemaName?: string;
  tableName?: string;
  /** 语义文件 tab */
  semanticProject?: string;
  filePath?: string;
  /** 资源文件 tab */
  resourceId?: number;
  resourceName?: string;
  /** 资源文件全路径（`ResourceInfo.fullName`，含目录）——溯源「这个文件在哪个目录」 */
  resourceFullName?: string;
  /** 正文摘要（作业/语义文件取 statement；资源文件取 content），仅用于展示 */
  contentExcerpt?: string;
}

/** 溯源出的上级背景 */
export interface ContextLineage {
  /** 数据源名称（如表所属库所在的注册数据源） */
  datasourceName?: string;
  /** 数据源类型（MySQL / Hive / ...） */
  datasourceType?: string;
  /** 资源文件所属目录路径 */
  resourcePath?: string;
}

/** 语义项目冲突（唯一保留的冲突场景） */
export interface SemanticTreeConflict {
  /** 左侧语义树当前项目 */
  treeProject: string;
  /** 编辑区当前文件所属项目 */
  fileProject: string;
}

/** 手动覆盖（临时、仅本次会话有效） */
export interface ContextOverride {
  databaseId?: number;
  schemaName?: string;
  tableName?: string;
  semanticProject?: string;
}

/** 最终绑定结果 */
export interface ContextBinding {
  editor?: EditorContext;
  lineage?: ContextLineage;
  semanticTreeConflict?: SemanticTreeConflict;
  override?: ContextOverride;
  /**
   * 批次 E：默认检索范围（左侧栏「知识库」tab 当前选中的库编码）。
   *
   * <p>它只影响后端 {@code kb_search} 的默认 {@code filter.collection}，
   * <b>不影响</b>表结构注入——与语义项目同构：都只声明"范围"，正文仍由工具按需召回。
   */
  kbCollection?: string;
  /** 参与指纹计算的来源标记（顺序无关，内部会排序） */
  sources: string[];
  /** 降级/缺失说明，供摘要行展示 */
  notices: string[];
}

export interface ResolveBindingInput {
  /** 当前活动 tab（CenterTab，字段宽松） */
  activeTab?: any;
  /** 语义树当前选中的项目（来自 DVA model 的 currentSemanticProject） */
  currentSemanticProject?: string;
  /** 知识库面板当前选中的库（来自 DVA model 的 currentKbCollection，批次 E） */
  currentKbCollection?: string;
  /**
   * 用户在本会话内主动解除了知识库绑定（点 chip 上的 ×）。
   *
   * <p>为什么要这个开关而不是直接清 model：tab 里的选中是<b>浏览态</b>（用户还在看那个库），
   * 解绑只清<b>问答默认范围</b>（会话态）。两者混在一起会出现"点了解绑、一切 tab 又绑回来"（D-K18）。
   */
  kbBindingCleared?: boolean;
  /** 数据源清单（用于把 databaseId 溯源成数据源名） */
  datasourceList?: any[];
  /** 手动覆盖 */
  override?: ContextOverride;
}

/** 正文摘要截断长度 */
const EXCERPT_MAX = 400;

/**
 * 从 tab 解析编辑区对象。
 *
 * <p>字段来源差异（实测）：
 * - 项目-作业：`{taskId, key, dialect, statement, databaseId}`
 * - 数据源-表：`{selectDatabaseId, databaseId, schemaName, tableName, type}`
 * - 资源-文件：`{id, name, content}`
 * - 语义项目-文件：`{project, path, statement, mtime, readOnly}`
 */
export function resolveEditorContext(tab: any): EditorContext | undefined {
  if (!tab || !tab.tabType) {
    return undefined;
  }
  const params = tab.params ?? {};
  const common = {
    tabId: String(tab.id ?? ''),
    tabType: tab.tabType as EditorTabType,
    // tab 标题只用于摘要行展示（比裸 id 可读），不参与任何注入
    tabTitle: tab.title
  };

  switch (tab.tabType) {
    case 'task':
      return {
        ...common,
        taskId: toNumber(params.taskId),
        databaseId: toNumber(params.databaseId),
        schemaName: params.schemaName,
        tableName: params.tableName,
        contentExcerpt: truncate(params.statement)
      };
    case 'dataSource':
      return {
        ...common,
        // P4：params 已补 databaseId 别名；这里再兼容一次 selectDatabaseId，防止旧快照 tab 失效
        databaseId: toNumber(params.databaseId ?? params.selectDatabaseId),
        schemaName: params.schemaName,
        tableName: params.tableName
      };
    case 'resource':
      return {
        ...common,
        resourceId: toNumber(params.id),
        resourceName: params.name,
        resourceFullName: params.fullName,
        contentExcerpt: truncate(params.content)
      };
    case 'semanticFile':
      return {
        ...common,
        semanticProject: params.project,
        filePath: params.path,
        contentExcerpt: truncate(params.statement)
      };
    default:
      return undefined;
  }
}

/**
 * 汇总绑定结果（P4 一期：AI Chat 自动上下文绑定的**唯一解析出口**）。
 *
 * <p>为什么单独抽一个纯函数：请求体、顶部摘要行、上下文指纹三处必须同源，否则会出现
 * 「摘要显示表 A、实际下发表 B」。本文件整体只做纯计算——无 React 依赖、无副作用、无请求。
 *
 * <p>四条设计要点（对应计划文档 §3）：
 * <ol>
 *   <li><b>编辑区单通道</b>：只以「当前打开的 tab」为绑定来源，不采集左侧目录树选中态
 *       （用户 2026-10-23 明确：未打开文件时不管树选中）；</li>
 *   <li><b>溯源</b>：由编辑区对象反查上级背景——表 → 数据源/schema；资源文件 → 目录；</li>
 *   <li><b>唯一冲突</b>：语义项目存在「树切了项目、编辑区仍是上一个项目的文件」，
 *       此时以<b>文件所属项目</b>为准（正在看的内容优先），并产出 conflict 说明供 UI 提示；</li>
 *   <li><b>手动覆盖</b>：自动推导之外允许临时指定（原顶栏下拉迁移到 popover），覆盖优先级最高，
 *       保证「手动指定数据源」这一既有能力不退化。</li>
 * </ol>
 *
 * <p>语义项目的取舍：
 * - 编辑区是语义文件 → **以文件所属项目为准**；若与树项目不同，产出 conflict 说明；
 * - 编辑区不是语义文件但树有选中项目 → 沿用 P3 行为（树项目生效，保证原有能力不退化）；
 * - 都没有 → 不注入。
 */
export function resolveBinding(input: ResolveBindingInput): ContextBinding {
  const {
    activeTab,
    currentSemanticProject,
    currentKbCollection,
    kbBindingCleared,
    datasourceList = [],
    override
  } = input;
  const editor = resolveEditorContext(activeTab);
  const notices: string[] = [];
  const sources: string[] = [];

  // 1) 主绑定来源
  if (editor) {
    sources.push(buildEditorSource(editor));
  } else {
    notices.push('empty');
  }

  // 2) 数据源（作业/表 tab 自带 + 覆盖）
  const effectiveDatabaseId = override?.databaseId ?? editor?.databaseId;
  if (effectiveDatabaseId) {
    sources.push(`ds:${effectiveDatabaseId}`);
  }
  if (override?.databaseId) {
    sources.push(`override:ds:${override.databaseId}`);
  }
  // 覆盖 schema / 表同样会改变后端注入的表清单（`buildSchemaContext` 按 schema + table 选表），
  // 因此必须进入指纹——否则「只换 schema」不会触发重注入，AI 仍按旧 schema 回答
  if (override?.schemaName) {
    sources.push(`override:schema:${override.schemaName}`);
  }
  if (override?.tableName) {
    sources.push(`override:table:${override.tableName}`);
  }

  // 3) 语义项目：文件优先，其次树；并检测冲突
  let semanticTreeConflict: SemanticTreeConflict | undefined;
  const fileProject = editor?.tabType === 'semanticFile' ? editor.semanticProject : undefined;
  if (fileProject && currentSemanticProject && fileProject !== currentSemanticProject) {
    semanticTreeConflict = { treeProject: currentSemanticProject, fileProject };
    sources.push(`conflict:tree=${currentSemanticProject}`);
  }
  const effectiveSemanticProject =
    override?.semanticProject ?? fileProject ?? currentSemanticProject ?? undefined;
  if (effectiveSemanticProject) {
    sources.push(`sem:${effectiveSemanticProject}`);
  }

  // 4) 批次 E：知识库默认范围。
  //    与语义项目并列进指纹：会话中途换库必须触发重注入，否则后端沿用旧快照，
  //    模型会拿着"上一轮的范围"回答（与 P4 的 `sem:` 同一条理由）。
  //    显式 @kb 永远优先于它，但那个判定在后端（D-K16）——前端只负责如实上报"正在看哪个库"。
  const kbCollection = kbBindingCleared ? undefined : currentKbCollection || undefined;
  if (kbCollection) {
    sources.push(`kb:${kbCollection}`);
  }

  // 5) 溯源
  const lineage: ContextLineage = {};
  if (effectiveDatabaseId) {
    const hit = datasourceList.find(
      (item: any) => Number(item?.id) === Number(effectiveDatabaseId)
    );
    if (hit) {
      lineage.datasourceName = hit.name;
      lineage.datasourceType = hit.type ?? hit.dbType;
    }
  }
  if (editor?.tabType === 'resource' && editor.resourceFullName) {
    // 资源文件 → 所属目录（U32：摘要要能显示「在哪个目录」）
    lineage.resourcePath = editor.resourceFullName;
  }
  if (editor?.tabType === 'resource' && !editor.resourceId) {
    // 旧快照 tab 没有 id（P4 之前的 params 只有 name/content）
    notices.push('resourceNoId');
  }

  return {
    editor,
    lineage,
    semanticTreeConflict,
    override,
    kbCollection,
    sources,
    notices
  };
}

/** 供请求体使用的「实际生效语义项目」 */
export function effectiveSemanticProjectOf(binding: ContextBinding): string | undefined {
  if (binding.override?.semanticProject) {
    return binding.override.semanticProject;
  }
  if (binding.editor?.tabType === 'semanticFile' && binding.editor.semanticProject) {
    return binding.editor.semanticProject;
  }
  const sem = binding.sources.find((s) => s.startsWith('sem:'));
  return sem ? sem.substring('sem:'.length) : undefined;
}

/**
 * 计算上下文指纹。
 *
 * <p>用途仅为「变化检测」（判断后端是否需要按首轮重注入），**不用于安全**，
 * 因此使用同步的 FNV-1a 而非 WebCrypto 的 SHA-256（后者是异步 API，会给发送链路引入无谓等待）。
 *
 * @param binding      绑定结果
 * @param extraSources 绑定之外、但同样改变注入内容的来源（如上下文范围档位、自选表清单）。
 *                     这类开关不属于「绑定到哪个对象」，放这里避免污染 ContextBinding 模型。
 */
export function buildFingerprint(
  binding: ContextBinding,
  extraSources: string[] = []
): string | undefined {
  const useful = [...binding.sources, ...extraSources].filter(Boolean).sort();
  if (useful.length === 0) {
    return undefined;
  }
  return `ctx_${fnv1a(useful.join('|'))}`;
}

/** 摘要行文案（由调用方传入 i18n 函数；返回结构化数据便于 popover 展示） */
export function describeBinding(binding: ContextBinding): {
  items: Array<{ key: string; value: string }>;
  empty: boolean;
} {
  const items: Array<{ key: string; value: string }> = [];
  const { editor, lineage } = binding;

  if (editor) {
    if (editor.tabType === 'task') {
      // 作业名比裸 id 可读（U30：摘要行显示「作业 t_etl_xxx」），无标题时回落 id
      items.push({
        key: 'task',
        value: editor.tabTitle || (editor.taskId ? `#${editor.taskId}` : '-')
      });
    } else if (editor.tabType === 'dataSource') {
      const table = [editor.schemaName, editor.tableName].filter(Boolean).join('.');
      items.push({ key: 'table', value: table || '-' });
    } else if (editor.tabType === 'resource') {
      items.push({ key: 'resource', value: editor.resourceName || '-' });
      if (lineage?.resourcePath) {
        items.push({ key: 'resourcePath', value: lineage.resourcePath });
      }
    } else if (editor.tabType === 'semanticFile') {
      const file = [editor.semanticProject, editor.filePath].filter(Boolean).join(' / ');
      items.push({ key: 'semanticFile', value: file || '-' });
    }
  }

  if (lineage?.datasourceName) {
    const ds = lineage.datasourceType
      ? `${lineage.datasourceName}(${lineage.datasourceType})`
      : lineage.datasourceName;
    items.push({ key: 'datasource', value: ds });
  }

  const sem = effectiveSemanticProjectOf(binding);
  if (sem && editor?.tabType !== 'semanticFile') {
    // 「未打开语义文件、只有树在 B」与「手动指定了 B」是两回事：
    // 前者要让用户知道口径来自树而不是正在看的文件（U35），因此用不同标签区分
    const fromTree = !binding.override?.semanticProject;
    items.push({ key: fromTree ? 'semanticProjectFromTree' : 'semanticProject', value: sem });
  }

  // 批次 E：知识库默认范围。这里显示的是编码（摘要行的其它项也都是 id/名字的折中），
  // 库名由 AI Chat 面板的 chip 单独显示（它需要额外的接口数据，不适合塞进纯函数解析）。
  if (binding.kbCollection) {
    items.push({ key: 'kbCollection', value: binding.kbCollection });
  }

  return { items, empty: items.length === 0 };
}

// ---------------------------------------------------------------------------
// 内部工具
// ---------------------------------------------------------------------------

function buildEditorSource(editor: EditorContext): string {
  switch (editor.tabType) {
    case 'task':
      return `editor:task:${editor.taskId ?? '-'}`;
    case 'dataSource': {
      const table = [editor.schemaName, editor.tableName].filter(Boolean).join('.');
      return `editor:ds:${editor.databaseId ?? '-'}:${table}`;
    }
    case 'resource':
      return `editor:res:${editor.resourceId ?? editor.resourceName ?? '-'}`;
    case 'semanticFile':
      return `editor:sem:${editor.semanticProject ?? '-'}:${editor.filePath ?? '-'}`;
    default:
      return 'editor:unknown';
  }
}

function toNumber(value: any): number | undefined {
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  const num = Number(value);
  return Number.isNaN(num) ? undefined : num;
}

function truncate(text: any): string | undefined {
  if (typeof text !== 'string' || text.length === 0) {
    return undefined;
  }
  return text.length > EXCERPT_MAX ? `${text.slice(0, EXCERPT_MAX)}…` : text;
}

/** FNV-1a 32 位哈希，返回 8 位十六进制串（足够做变化检测）。 */
function fnv1a(input: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i += 1) {
    // eslint-disable-next-line no-bitwise
    hash ^= input.charCodeAt(i);
    // eslint-disable-next-line no-bitwise
    hash = Math.imul(hash, 0x01000193);
  }
  // eslint-disable-next-line no-bitwise
  return (hash >>> 0).toString(16).padStart(8, '0');
}
