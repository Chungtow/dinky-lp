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

import { executeSql } from '@/pages/DataStudio/service';
import { hasFlinkContext, isSql } from '@/pages/DataStudio/utils';
import { DataStudioActionType } from '@/pages/DataStudio/data.d';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import {
  getDataSourceList,
  showDataSourceTable
} from '@/pages/DataStudio/Toolbar/DataSource/service';
import { decideMentionBackspace } from './mentionBackspace';
import type { MentionBackspaceArm } from './mentionBackspace';
import { finalizePathMention, replaceMentionToken } from './mentionPath';
import { Authorized } from '@/hooks/useAccess';
import { PermissionConstants } from '@/types/Public/constants';
import ToolProcess from './components/ToolProcess';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import CraftDiff from './components/CraftDiff';
import SqlDiff from './components/SqlDiff';
import ContextBar from './ContextBar';
import type { ContextScope } from './ContextBar';
import { buildFingerprint, effectiveSemanticProjectOf, resolveBinding } from './aiChatContext';
import './index.less';
import {
  AiChatConfig,
  AiChatConfirmFrame,
  AiChatMentionItem,
  AiChatMessage,
  AiChatToolStep,
  AiChatVerify,
  aiChatStream,
  cancelAiChat,
  confirmAiChat,
  getAiChatConfig,
  listFlinkSqlEnvs,
  listMentionTopics,
  listPublishedSemanticAssets,
  listPublishedSemanticProjects,
  listSkills,
  listTableColumns,
  reportCraftWrite
} from './service';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
import { resolveEditorKey } from '@/pages/DataStudio/editorKey';
import { getExecError, getLatestExecError } from '@/services/BusinessCrud';
import { l } from '@/utils/intl';
import {
  CopyOutlined,
  PlayCircleOutlined,
  RobotOutlined,
  RollbackOutlined,
  SendOutlined,
  StopOutlined
} from '@ant-design/icons';
import { connect } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Input,
  Modal,
  Segmented,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  message
} from 'antd';
import React, { useContext, useEffect, useMemo, useRef, useState } from 'react';

const CODE_BLOCK_REGEX = /```[a-zA-Z]*\s*\n?([\s\S]*?)```/g;
/** 「最近使用」的 @ 引用名（localStorage key） */
const RECENT_MENTION_KEY = 'dinky.ai-chat.recent-mentions';

/** 纯修饰键（按下它们不算"打断 @串两下删除"） */
const MODIFIER_KEYS = new Set(['Shift', 'Control', 'Alt', 'Meta', 'CapsLock', 'Dead']);

/**
 * 按用户输入的数据源名解析出**真实数据源对象**（大小写不敏感）。
 *
 * <p>数据源名允许含大写（如 `STS-lpdw`），而 `@source/` 的层级解析早期把整串小写化后用
 * `name === typed` 精确匹配，导致第二层（库）永远查不到 → 静默 return、不给候选。
 * 统一走这里解析，命中后一律使用**规范名** `ds.name`。见 `20261018` 计划 F6。
 */
const resolveSourceDs = (list: any[], typed: string) => {
  if (!typed) {
    return undefined;
  }
  return (
    list.find((item: any) => item.name === typed) ??
    list.find((item: any) => `${item.name}`.toLowerCase() === typed.toLowerCase())
  );
};

type AiChatProps = {
  tabs: any[];
  activeTab?: string;
  updateAction: (payload: any) => void;
  /**
   * P3b：语义面板当前选中的项目（来自 model）。
   *
   * <p>为何不直接用 {@code tabParams.project}：在语义目录树上切项目**不会**切中间 tab，
   * tabParams 会一直停在上次打开的文件所属项目上（UAT 2026-10-22）。
   */
  currentSemanticProject?: string;
};

const AiChat = (props: AiChatProps) => {
  const { tabs, activeTab, updateAction, currentSemanticProject } = props;

  const [messages, setMessages] = useState<AiChatMessage[]>([]);
  const [inputValue, setInputValue] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [config, setConfig] = useState<AiChatConfig>();
  const [schemas, setSchemas] = useState<any[]>([]);
  const [schemaName, setSchemaName] = useState<string>();
  const [tableName, setTableName] = useState<string>();
  // 每条 assistant 消息的"思考过程"是否展开
  const [expandedReasoning, setExpandedReasoning] = useState<Record<number, boolean>>({});
  const abortRef = useRef<AbortController>();
  // 面板级会话标识：首轮不带 sessionId（后端才携带 schema 上下文），后续带上以复用上下文
  const sessionIdRef = useRef<string>(`${Date.now()}`);
  /**
   * P4：上一次发送时的**上下文指纹**。
   *
   * <p>取代 P3b 的 `lastSemanticProjectRef`：指纹覆盖「切 tab / 换语义项目 / 临时覆盖数据源」
   * 全部来源，变化即说明本轮必须让后端按首轮重注入（见 `handleSend` 的 contextChanged）。
   */
  const lastFingerprintRef = useRef<string | undefined>(undefined);

  const currentTab = tabs?.find((tab) => tab.id === activeTab);
  const tabParams = currentTab?.params ?? {};
  const dialect: string = tabParams?.dialect ?? '';
  /**
   * P4 一期：手动覆盖（仅本次会话有效），置空即回落到「跟随编辑区自动绑定」。
   *
   * <p>`datasourceId` 既是「面板覆盖作业自带数据源」，也是原「手动指定数据源」能力的载体
   * （U43：作业未绑数据源时也能指定一个），因此保留其语义，优先级收敛进绑定模型：
   * `手动覆盖 > 编辑区文件项目 > 语义树当前项目`。
   */
  const [datasourceId, setDatasourceId] = useState<number | undefined>();
  /** P4：语义项目临时覆盖（「改用树项目」与手动指定都落在这里） */
  const [semanticProjectOverride, setSemanticProjectOverride] = useState<string | undefined>();
  const [datasourceList, setDatasourceList] = useState<any[]>([]);
  /**
   * P4 一期：**唯一上下文解析出口**。
   *
   * <p>请求体、摘要行、上下文指纹三处必须同源，否则会出现「摘要显示表 A、实际下发表 B」。
   * 解析本身是纯函数（`aiChatContext.ts`）：无请求、无副作用、无 React 依赖。
   */
  const binding = useMemo(
    () =>
      resolveBinding({
        activeTab: currentTab,
        currentSemanticProject,
        datasourceList,
        override: {
          databaseId: datasourceId,
          schemaName,
          tableName,
          semanticProject: semanticProjectOverride
        }
      }),
    [
      currentTab,
      currentSemanticProject,
      datasourceList,
      datasourceId,
      schemaName,
      tableName,
      semanticProjectOverride
    ]
  );
  /** 实际生效的数据源：手动覆盖优先，否则取编辑区对象自带的（task / dataSource tab） */
  const databaseId: number | undefined = binding.override?.databaseId ?? binding.editor?.databaseId;
  /** 实际生效的 schema / 表：覆盖优先，否则由编辑区对象溯源（表 tab 自带） */
  const effectiveSchemaName: string | undefined =
    binding.override?.schemaName ?? binding.editor?.schemaName;
  const effectiveTableName: string | undefined =
    binding.override?.tableName ?? binding.editor?.tableName;
  /**
   * 编辑区正文：作业 / 语义文件取 `statement`，**资源文件取 `content`**。
   *
   * <p>此前一律读 `statement`，资源 tab 因此永远拿到空正文（U32：问「这个文件讲了什么」答不出）。
   */
  const currentSql: string =
    currentTab?.tabType === 'resource' ? (tabParams?.content ?? '') : (tabParams?.statement ?? '');
  /** 语义文件 tab（dbt on HDFS）不是 SQL 作业：EXPLAIN / 执行对它无意义，AI 仍可读写全文 */
  const isSqlJobTab = currentTab?.tabType === 'task';
  /**
   * 是否可把「数据源 → 库表元数据」下发给后端。
   *
   * <p>P4：数据源-表 tab 本身没有 dialect，但**它自己就是元数据对象**
   * （U31：打开表 tab 直接问字段）。若仍按 `isSql(dialect)` 判定，这条路径拿不到任何 schema。
   */
  const metaDataAvailable =
    Boolean(databaseId) && (isSql(dialect?.toLowerCase()) || currentTab?.tabType === 'dataSource');
  /**
   * P0：FlinkSQL 上下文可用性（与 metaDataAvailable 正交）。
   *
   * <p>FlinkSQL 作业没有数据源，schema 来自 Flink Catalog，由后端 `{{flinkContext}}` 区块承载；
   * 因此这里**不能**靠放开 isSql 来判定，否则会把「未绑定数据源」的误导文案重新引出来。
   * 该标志只用于：① 抑制误导性告警；② 展示「已加载 FlinkSQL 上下文」提示。
   */
  const flinkContextAvailable = hasFlinkContext(dialect?.toLowerCase());

  useEffect(() => {
    getAiChatConfig()
      .then((res) => setConfig(res))
      .catch(() => setConfig({}));
  }, []);

  /**
   * 阶段 3：所选 LLM 实例（多 LLM 实例配置）。
   *
   * <p>{@code undefined} = 使用默认实例（与后端"缺省回落"语义一致）；可用列表由 {@code getConfig}
   * 下发且**已脱敏**（只含 hasApiKey，不含密钥）。
   */
  const [profileId, setProfileId] = useState<string | undefined>(undefined);
  const profileList = useMemo(() => config?.profiles ?? [], [config]);
  const activeProfileId = profileId ?? config?.defaultProfileId ?? 'default';
  const activeProfile = useMemo(
    () => profileList.find((item) => item.id === activeProfileId),
    [profileList, activeProfileId]
  );

  // 数据源清单：复用注册中心已有的「启用中数据源」接口，不新增后端接口
  useEffect(() => {
    getDataSourceList()
      .then((res: any) => setDatasourceList(res?.data ?? res ?? []))
      .catch(() => setDatasourceList([]));
  }, []);

  useEffect(() => {
    if (!metaDataAvailable || !databaseId) {
      setSchemas([]);
      return;
    }
    showDataSourceTable(databaseId).then((res) => setSchemas(res ?? []));
    // 切换数据源后旧的 schema / table 选择已失效，必须清空，否则会把不存在于新数据源的
    // 定位信息下发给后端（表现为「表找不到」而非「选错了数据源」）
    setSchemaName(undefined);
    setTableName(undefined);
    setCustomTables([]);
  }, [databaseId, metaDataAvailable]);

  const tableOptions = useMemo(() => {
    // 用「实际生效」的 schema（覆盖优先、否则编辑区自带的表 tab）——
    // 否则打开表 tab 时下拉是空的，等于要求用户再手选一遍（P4 要消灭的正是这一步）
    const schema = schemas?.find((item) => item.name === effectiveSchemaName);
    return (schema?.tables ?? []).map((table: any) => ({ label: table.name, value: table.name }));
  }, [schemas, effectiveSchemaName]);

  // ===== 阶段 1a：Context 三档（1.1）/ @ 引用（1.4）/ 选中片段（1.0.4）=====
  const [contextScope, setContextScope] = useState<'current' | 'all' | 'custom'>('all');
  const [customTables, setCustomTables] = useState<string[]>([]);
  const [mentions, setMentions] = useState<AiChatMentionItem[]>([]);

  /** 清空全部手动覆盖，回到「自动跟随编辑区」（popover 里的「恢复自动跟随」） */
  const resetContextOverride = () => {
    setDatasourceId(undefined);
    setSchemaName(undefined);
    setTableName(undefined);
    setCustomTables([]);
    setSemanticProjectOverride(undefined);
  };

  /**
   * P4：手动覆盖只在「当前编辑区对象」内有效，换 tab 即失效。
   *
   * <p>否则上一个 tab 的表名 / 数据源会被当成当前对象的定位信息下发（表现为「表找不到」
   * 而非「没选」），而且与「自动跟随编辑区」的承诺直接矛盾。
   */
  useEffect(() => {
    resetContextOverride();
    // 只按 activeTab 触发；resetContextOverride 每次渲染都是新函数，纳入依赖会陷入无限循环
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);
  /**
   * 阶段 2：对话模式。Craft 需管理员开启（{@code config.craftModeEnable}）才可选，
   * 未开启时恒为 ask——与后端「未开启即回落 ask」的双重校验形成闭环。
   */
  const [mode, setMode] = useState<'ask' | 'craft'>('ask');
  /** 阶段 2：Craft 待拍板的改动；非空即展示 diff 预览 */
  const [craftDiff, setCraftDiff] = useState<{ original: string; modified: string } | null>(null);
  /**
   * 阶段 2：AI 改动前的快照（tab → 原文）。
   *
   * <p>不能只依赖 monaco 的 undo 栈——tab 卸载 / 组件 dispose 后就失效了，
   * 因此「撤销」按钮必须走这条独立的快照通道（计划 §3.3 第 3 点）。
   */
  const craftBeforeRef = useRef<Record<string, string>>({});
  /** 哪些 tab 当前处于「AI 已改动、可撤销」状态 */
  const [craftApplied, setCraftApplied] = useState<Record<string, boolean>>({});
  /** 阶段 2b（局部改写）：编辑区当前选中片段（驱动「修复/改写」操作条显隐） */
  const [selectedText, setSelectedText] = useState<string>('');
  /** 阶段 2b：Fix / Rewrite 待拍板的改写结果；非空即展示 diff 预览 */
  const [p2bDiff, setP2bDiff] = useState<{
    action: 'FIX_SQL' | 'REWRITE_SQL';
    original: string;
    modified: string;
  } | null>(null);
  const [p2bLoading, setP2bLoading] = useState<boolean>(false);
  /** 阶段 2c-0：本次运行的 id（SSE 下发，用于二次确认与服务端中断） */
  const runIdRef = useRef<string>('');
  /** 阶段 2c-0：待用户拍板的写语句执行确认（非空即弹确认框） */
  const [writeConfirm, setWriteConfirm] = useState<AiChatConfirmFrame | null>(null);
  /**
   * 阶段 4b：删除类确认（{@code kind=skill_delete}）要求**手输目标名**才能确认（防误删）。
   * 这里保存输入框内容，与后端下发的 {@code targetName} 完全一致时才允许点「确认执行」
   * （后端亦会二次校验，不一致按拒绝处理）。
   */
  const [confirmTypedName, setConfirmTypedName] = useState<string>('');
  const [mentionOpen, setMentionOpen] = useState<boolean>(false);
  const [mentionQuery, setMentionQuery] = useState<string>('');
  const [mentionIndex, setMentionIndex] = useState<number>(0);
  /**
   * 阶段 2 前置（字段级 {@code @表.字段}）：二级候选。
   *
   * <p>按需加载（输入 {@code @表名.} 才请求）+ 按「数据源|schema|表」缓存，
   * 避免每敲一个字符都打一次元数据接口。
   */
  const [columnOptions, setColumnOptions] = useState<AiChatMentionItem[]>([]);
  const columnCacheRef = useRef<Map<string, AiChatMentionItem[]>>(new Map());
  /**
   * @串退格的「两下删除」判据：第一下删分隔符时埋点，第二下才整块删。
   *
   * <p>为何需要它：光标前是 `@table/foo` 时，「刚输完」与「打到一半」的文本完全一样，无法只靠文本区分；
   * 判据与理由见 `mentionBackspace.ts`。
   */
  const atomicDeleteArmRef = useRef<MentionBackspaceArm | null>(null);
  /** 阶段 4a：团队 Skill 候选（仅管理员开启 skillEnable 时拉取）——输入 {@code @skill/} 时展示 */
  const [skillOptions, setSkillOptions] = useState<AiChatMentionItem[]>([]);
  // P1：跨源引用（@source/ / @env/）的候选与懒加载缓存。
  // @source/ 是四级渐进披露（数据源 → 库 → 表），只在用户逐层往下敲时才拉下一层，
  // 避免"刚敲 @source/ 就把全部数据源的全部库表都拉下来"。
  const [dataSources, setDataSources] = useState<any[]>([]);
  const [envOptions, setEnvOptions] = useState<AiChatMentionItem[]>([]);
  /** P1-A：{@code @topic/} 候选（名册版：仅含已被作业使用的 topic，不连 Kafka） */
  const [topicOptions, setTopicOptions] = useState<AiChatMentionItem[]>([]);
  /** 数据源名 → 该数据源的 schemas（含 tables）缓存 */
  const sourceTreeRef = useRef<Map<string, any[]>>(new Map());
  /**
   * P3b：{@code @semantic/} 候选——已发布项目（一级）与所选项目内的资产（二级）。
   *
   * <p>只读 `_published`：AI 只能引用不可变快照，编辑区内容不进候选。
   */
  const [semanticProjects, setSemanticProjects] = useState<any[]>([]);
  const [semanticAssets, setSemanticAssets] = useState<AiChatMentionItem[]>([]);
  /** 已发布项目清单只拉一次（体量小、与输入关键字无关） */
  const semanticProjectsLoadedRef = useRef<boolean>(false);
  /** 缓存写入后触发候选重算（ref 本身不会触发重渲染） */
  const [sourceTreeTick, setSourceTreeTick] = useState<number>(0);
  const [recentMentions, setRecentMentions] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem(RECENT_MENTION_KEY) ?? '[]');
    } catch (e) {
      return [];
    }
  });
  /** 输入法组合态：中文输入过程中不触发 @ 浮层，避免误弹 */
  const composingRef = useRef<boolean>(false);
  /**
   * 输入框引用。
   *
   * <p>UAT 2026-10-23：{@code @} 候选的解析与替换必须基于**光标位置**——
   * 选中中间层级后文本尾部带空格，若仍按「全文末尾」解析，光标回退补 {@code /} 时永远不弹候选。
   */
  const inputRef = useRef<any>(null);
  /** 消息列表容器（自动滚动到底部用） */
  const messageListRef = useRef<HTMLDivElement>(null);
  /**
   * 是否「贴在底部」。用户手动上翻看历史时置 false，滚回底部附近恢复 true——
   * 流式回答每来一个 chunk 都会滚动，不能把正在看历史的用户强行拽回底部。
   */
  const stickToBottomRef = useRef<boolean>(true);
  const { editorRegistry } = useContext(DataStudioContext);

  /** 消息列表滚动：距底 < 40px 视为「仍在底部」，恢复自动跟随 */
  const handleMessageListScroll = (e: any) => {
    const el = e.currentTarget as HTMLDivElement;
    stickToBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  };

  /**
   * 自动跟随最新输出行。
   *
   * <p>流式场景每个 chunk 都会 {@code setMessages}，因此依赖 messages 即可持续跟随；
   * 刻意不用 {@code scroll-behavior: smooth}——高频滚动下 smooth 会互相打断、反而滞后。
   */
  useEffect(() => {
    const el = messageListRef.current;
    if (el && stickToBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages]);

  /**
   * 当前 tab 的编辑器注册键：作业 tab 用 taskId；语义文件 tab 用 semantic:<project>:<path>。
   * 必须与注册方（SqlTask / SemanticFile）同源，否则选区静默为空。
   */
  const editorKey = resolveEditorKey(tabParams);

  /** 读取当前编辑区的选中片段（无选中时返回空串） */
  const readSelectedSql = () => editorRegistry?.getSelection(editorKey) ?? '';

  // 阶段 2b：订阅当前作业编辑区的选区变化，驱动「修复/改写」操作条的显隐
  useEffect(() => {
    if (!editorKey || !editorRegistry) {
      setSelectedText('');
      return;
    }
    return editorRegistry.onSelectionChange(editorKey, (text) => setSelectedText(text));
  }, [editorRegistry, editorKey]);

  /**
   * {@code @} 候选来源：<b>可插拔 provider 注册表</b>。
   *
   * <p>阶段 1a 内置 table / job / selection 三个 provider；后续「语料包」（阶段 1 计划 §3.4）
   * 可作为新 provider 接入，浮层与协议均无需改动。
   */
  /** 解析 {@code @表名.字段前缀}：命中则进入字段二级候选模式 */
  const columnQuery = useMemo(() => {
    const m = /^([A-Za-z0-9_]+)\.([A-Za-z0-9_]*)$/.exec(mentionQuery.trim());
    return m ? { table: m[1], keyword: m[2] ?? '' } : null;
  }, [mentionQuery]);

  /**
   * P1：解析 {@code @source/} 的层级查询。
   *
   * <p>为什么区分 trailing（尾随斜杠）：{@code @source/ds/db} 表示"正在输入库名"（该层筛选），
   * 而 {@code @source/ds/db/} 表示"库已选定，要看表列表"（下一层）——两者字符串接近但语义不同。
   */
  const sourceQuery = useMemo(() => {
    // 2026-10-18 修复：**不再整体小写化**。数据源名可能含大写（`STS-lpdw`），整体小写会让
    // 第二层的精确匹配永远落空（明明有库却不出候选）。大小写不敏感交给 i 标志与逐处比较。
    const m = /^source[-/]([^\s]*)$/i.exec(mentionQuery.trim());
    if (!m) {
      return null;
    }
    const raw = m[1] ?? '';
    return {
      trailing: raw.endsWith('/'),
      parts: raw.split('/').filter((part) => part !== '')
    };
  }, [mentionQuery]);

  /**
   * P3b：解析 {@code @semantic/} 的层级查询（两级：已发布项目 → 项目内资产）。
   *
   * <p>与 {@code @source/} 的差异：语义项目没有"库"这一级（只有两段），且第二层的
   * name 要拼成 {@code <project>/<asset>} 路径——后端 {@code AiChatMention.name} 就是这个存法（D19）。
   */
  const semanticQuery = useMemo(() => {
    const m = /^semantic[-/]([^\s]*)$/i.exec(mentionQuery.trim());
    if (!m) {
      return null;
    }
    const raw = m[1] ?? '';
    return {
      trailing: raw.endsWith('/'),
      parts: raw.split('/').filter((part) => part !== '')
    };
  }, [mentionQuery]);

  /** 一级：首次输入 {@code @semantic/} 时拉取"已发布项目"（只拉一次，与关键字无关） */
  useEffect(() => {
    if (!semanticQuery || semanticProjectsLoadedRef.current) {
      return;
    }
    semanticProjectsLoadedRef.current = true;
    listPublishedSemanticProjects()
      .then((rows) => setSemanticProjects(rows ?? []))
      .catch(() => setSemanticProjects([]));
  }, [semanticQuery]);

  /**
   * 二级：拉取"已确定项目"的资产。
   *
   * <p><b>只在项目名已确定时才请求</b>（UAT 2026-10-22 反馈）：
   * ① 输入了分隔符（{@code @semantic/sem_demo2/}）或已在输入资产名——此时 parts[0] 是完整项目名；
   * ② 该名字确实在已发布项目清单里——不在清单内就是"还在打字"，候选面板按关键字过滤即可，
   *    <b>不请求后端</b>。否则每敲一个字符都会拿 "s"/"se" 当项目名请求，后端 442 触发全局错误提示。
   */
  const semanticProjectName = semanticQuery?.parts[0] ?? '';
  const semanticProjectReady =
    !!semanticQuery &&
    !!semanticProjectName &&
    (semanticQuery.trailing || semanticQuery.parts.length > 1) &&
    semanticProjects.some((item: any) => item.name === semanticProjectName);
  /** 资产请求序号：切项目/连续输入时丢弃过期响应，避免慢响应把新项目的候选清空 */
  const semanticAssetReqRef = useRef(0);
  useEffect(() => {
    if (!semanticProjectReady) {
      return;
    }
    const seq = ++semanticAssetReqRef.current;
    setSemanticAssets([]);
    listPublishedSemanticAssets(semanticProjectName)
      .then((rows) => {
        if (seq !== semanticAssetReqRef.current) {
          return;
        }
        setSemanticAssets(
          (rows ?? []).map((row: any) => ({
            type: 'semantic' as const,
            name: `${semanticProjectName}/${row.name}`,
            // 记住资产类别：同名（表 / 指标）时用于生成长式句柄（见 mentionCandidates 第 9 个 provider）
            assetType: `${row.type ?? ''}`.toUpperCase() === 'METRIC' ? 'metric' : 'table'
          }))
        );
      })
      .catch(() => {
        if (seq === semanticAssetReqRef.current) {
          setSemanticAssets([]);
        }
      });
  }, [semanticProjectReady, semanticProjectName]);

  useEffect(() => {
    // P4：按「实际生效」的 schema 取字段——表 tab 自动带上 schema 后，@表.字段 才能直接用
    if (!columnQuery || !databaseId || !effectiveSchemaName) {
      setColumnOptions([]);
      return;
    }
    const cacheKey = `${databaseId}|${effectiveSchemaName}|${columnQuery.table}`;
    const cached = columnCacheRef.current.get(cacheKey);
    if (cached) {
      setColumnOptions(cached);
      return;
    }
    let cancelled = false;
    listTableColumns(databaseId, effectiveSchemaName, columnQuery.table)
      .then((cols: any[]) => {
        const list = (cols ?? []).map((col: any) => ({
          type: 'column' as const,
          name: columnQuery.table,
          columnName: col?.name,
          schemaName: effectiveSchemaName,
          group: l('datastudio.aiChat.mention.groupColumn')
        }));
        columnCacheRef.current.set(cacheKey, list);
        if (!cancelled) {
          setColumnOptions(list);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setColumnOptions([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [columnQuery, databaseId, effectiveSchemaName]);

  // 阶段 4a：skill 候选——仅在管理员开启 skillEnable 时拉取；关闭时清空（避免残留旧候选）
  useEffect(() => {
    if (!config?.skillEnable) {
      setSkillOptions([]);
      return;
    }
    listSkills()
      .then((items) =>
        // 阶段 4b：同一次查询同时返回 skill 与 doc（后端按 asset_type 区分），这里映射为候选的
        // type，使 @skill/ / @doc/ 两个前缀都能命中同一批数据
        setSkillOptions(
          items.map((item: any) => ({
            ...item,
            type: item.assetType === 'doc' ? 'doc' : 'skill'
          }))
        )
      )
      .catch(() => setSkillOptions([]));
  }, [config?.skillEnable]);

  // P1：{@code @source/} 第一层候选——数据源清单（首次用到时才拉，且只拉一次）
  useEffect(() => {
    if (!sourceQuery || dataSources.length > 0) {
      return;
    }
    getDataSourceList()
      .then((list: any[]) => setDataSources(list ?? []))
      .catch(() => setDataSources([]));
  }, [sourceQuery, dataSources.length]);

  // P1：{@code @source/<数据源>/...} 第二/三层候选——按数据源名懒加载并缓存其 schemas。
  // 复用数据源面板同一个接口（showDataSourceTable）：一次拉取同时返回库与表，因此一个数据源只需请求一次。
  useEffect(() => {
    if (!sourceQuery || sourceQuery.parts.length < 1) {
      return;
    }
    const typed = sourceQuery.parts[0];
    // 2026-10-18 修复：按大小写不敏感解析，并以**规范名** `ds.name` 作缓存 key
    //（此前用精确匹配，`STS-lpdw` 这类含大写的名字直接查不到 → 静默 return → 第二层永远空）。
    const ds = resolveSourceDs(dataSources, typed);
    if (!ds || sourceTreeRef.current.has(ds.name)) {
      return;
    }
    let cancelled = false;
    showDataSourceTable(ds.id)
      .then((res: any[]) => {
        if (cancelled) {
          return;
        }
        // 失败时服务层返回 null：**不写缓存**，留给下一次输入重试（此前会缓存成 []，
        // 配合 has() 判断导致一次失败后整个会话都不再拉取）。
        if (res === null || res === undefined) {
          return;
        }
        sourceTreeRef.current.set(ds.name, res);
        setSourceTreeTick((tick) => tick + 1);
      })
      .catch(() => {
        // 同上：不缓存失败结果
      });
    return () => {
      cancelled = true;
    };
  }, [sourceQuery, dataSources]);

  // P1：{@code @env/} 候选——FlinkSQL 环境任务清单（与右侧「FlinkSQL 环境」下拉同源）
  useEffect(() => {
    if (!/^env[-/]/.test(mentionQuery.trim().toLowerCase()) || envOptions.length > 0) {
      return;
    }
    listFlinkSqlEnvs()
      .then((list) =>
        setEnvOptions(
          (list ?? []).map((env: any) => ({
            type: 'env' as const,
            name: env.name,
            group: l('datastudio.aiChat.mention.groupEnv')
          }))
        )
      )
      .catch(() => setEnvOptions([]));
  }, [mentionQuery, envOptions.length]);

  // P1-A：{@code @topic/} 候选——首次用到时拉一次（名册接口，不计入 @source/ 的展开上限）
  useEffect(() => {
    if (!/^topic[-/]/.test(mentionQuery.trim().toLowerCase()) || topicOptions.length > 0) {
      return;
    }
    listMentionTopics()
      .then((names) =>
        setTopicOptions(
          (names ?? []).map((name: string) => ({
            type: 'topic' as const,
            name,
            group: l('datastudio.aiChat.mention.groupTopic')
          }))
        )
      )
      .catch(() => setTopicOptions([]));
  }, [mentionQuery, topicOptions.length]);

  const mentionCandidates = useMemo(() => {
    const list: (AiChatMentionItem & { group: string })[] = [];
    // provider 1：当前 schema 下的表（P4：按实际生效的 schema，表 tab 打开即有候选）
    const schema: any = schemas?.find((item: any) => item.name === effectiveSchemaName);
    (schema?.tables ?? []).forEach((table: any) => {
      list.push({
        type: 'table',
        name: table.name,
        schemaName: effectiveSchemaName,
        group: l('datastudio.aiChat.mention.groupTable')
      });
    });
    // provider 2：其它已打开的作业
    (tabs ?? []).forEach((tab: any) => {
      if (tab?.id === activeTab) {
        return;
      }
      const statement = tab?.params?.statement;
      if (!statement) {
        return;
      }
      list.push({
        type: 'job',
        name: tab?.title ?? tab?.id,
        content: statement,
        group: l('datastudio.aiChat.mention.groupJob')
      });
    });
    // provider 3：当前编辑区选中的片段
    const selection = readSelectedSql();
    if (selection.trim()) {
      list.push({
        type: 'selection',
        name: l('datastudio.aiChat.mention.selectionName'),
        content: selection,
        group: l('datastudio.aiChat.mention.groupSelection')
      });
    }
    // provider 4（阶段 2 前置）：字段级引用——输入 @表名. 时改为给出该表的字段候选
    if (columnQuery) {
      return columnOptions;
    }
    // provider 6（P1）：@source/ 跨数据源引用——逐层懒加载：数据源 → 库 → 表。
    // 名字存的是"路径"（ds / ds/db / ds/db/tbl），与后端 AiChatMention.name 及（手打时）的文本一致。
    if (sourceQuery) {
      // 2026-10-18 修复：统一按**规范名**（真实 `ds.name`）取缓存与拼路径，各层关键字大小写不敏感。
      const ds = resolveSourceDs(dataSources, sourceQuery.parts[0] ?? '');
      const dsName = ds?.name ?? '';
      const tree: any[] = dsName ? (sourceTreeRef.current.get(dsName) ?? []) : [];
      // 第一层：数据源名（或在数据源名上筛选）
      if (sourceQuery.parts.length <= 1 && !sourceQuery.trailing) {
        const keyword = (sourceQuery.parts[0] ?? '').toLowerCase();
        return dataSources
          .filter((item: any) => !keyword || `${item.name}`.toLowerCase().includes(keyword))
          .map((item: any) => ({
            type: 'source' as const,
            name: item.name,
            // UAT 2026-10-23：数据源是**中间层级**——选中即补成 `ds/` 并自动列出库候选，
            // 用户不用回退光标补斜杠（此前只能停在数据源，想下钻必须整串重打）
            chainable: true,
            group: l('datastudio.aiChat.mention.groupSource')
          }))
          .slice(0, 20);
      }
      // 第二层：库名（ds/ 或在库名上筛选）
      if (
        (sourceQuery.trailing && sourceQuery.parts.length === 1) ||
        (!sourceQuery.trailing && sourceQuery.parts.length === 2)
      ) {
        const keyword = (sourceQuery.trailing ? '' : (sourceQuery.parts[1] ?? '')).toLowerCase();
        return tree
          .filter((schema: any) => !keyword || `${schema.name}`.toLowerCase().includes(keyword))
          .map((schema: any) => ({
            type: 'source' as const,
            name: `${dsName}/${schema.name}`,
            // 库同样是中间层级：选中 → `ds/db/` → 自动列出表候选
            chainable: true,
            group: l('datastudio.aiChat.mention.groupSourceDb')
          }))
          .slice(0, 30);
      }
      // 第三层：表名（ds/db/ 或在表名上筛选）
      if (
        (sourceQuery.trailing && sourceQuery.parts.length === 2) ||
        (!sourceQuery.trailing && sourceQuery.parts.length === 3)
      ) {
        const dbKeyword = `${sourceQuery.parts[1] ?? ''}`.toLowerCase();
        const schema = tree.find((item: any) => `${item.name}`.toLowerCase() === dbKeyword);
        const keyword = (sourceQuery.trailing ? '' : (sourceQuery.parts[2] ?? '')).toLowerCase();
        return (schema?.tables ?? [])
          .filter((table: any) => !keyword || `${table.name}`.toLowerCase().includes(keyword))
          .map((table: any) => ({
            type: 'source' as const,
            name: `${dsName}/${schema.name}/${table.name}`,
            group: l('datastudio.aiChat.mention.groupSourceTable')
          }))
          .slice(0, 50);
      }
      return [];
    }
    // provider 7（P1）：@env/ 环境任务
    if (/^env[-/]/.test(mentionQuery.trim().toLowerCase())) {
      return envOptions;
    }
    // provider 8（P1-A）：@topic/ 候选——名册里"已被作业使用"的 topic（零连接 Kafka）。
    // 平台里存在但无任何作业消费的 topic ，需等批次 2 的 list_topics（AdminClient）。
    if (/^topic[-/]/.test(mentionQuery.trim().toLowerCase())) {
      return topicOptions;
    }
    // provider 5（阶段 4a 的 skill / 阶段 4b 扩展的 doc）：输入 @skill/ / @doc/ 时只给对应资产候选
    // 阶段 4b 补：@table/<表名> 与旧语法 @表名 等价，但显式前缀在「表名与 skill 名相同」时
    // 可消除歧义。此处只影响候选过滤：输入 @table/ 时只给表候选。
    // 2026-10-06：分隔符由「连字符」统一为「斜杠」（名字本身允许连字符，@skill/dw-sql-review
    // 比 @skill-dw-sql-review 更易切分）；但仍兼容旧写法 @skill- / @doc- / @table-。
    const assetQuery = mentionQuery.trim().toLowerCase();
    const assetPrefix = /^doc[-/]/.test(assetQuery)
      ? 'doc'
      : /^skill[-/]/.test(assetQuery)
        ? 'skill'
        : /^table[-/]/.test(assetQuery)
          ? 'table'
          : '';
    if (assetPrefix) {
      // ⚠️ 2026-10-06 修复（UAT 反馈）：候选来源不能一律取 skillOptions——
      // skillOptions 只装 /api/skill/list 返回的 skill / doc 资产，**根本没有 table**，
      // 所以 @table/ 恒为空（"输入 @table/ 不出任何表候选"）。
      // 表候选来自 provider 1（当前 schema 的 tables），即上面的 list；skill / doc 才取 skillOptions。
      const source = assetPrefix === 'table' ? list : skillOptions;
      const group =
        assetPrefix === 'table'
          ? l('datastudio.aiChat.mention.groupTable')
          : l('datastudio.aiChat.mention.groupSkill');
      return source.filter((item) => item.type === assetPrefix).map((item) => ({ ...item, group }));
    }
    // provider 9（P3b）：@semantic/ 语义资产——两级：已发布项目 → 项目内资产。
    // 名字存"路径"（project 或 project/asset），与后端 AiChatMention.name 一致（D19）。
    if (semanticQuery) {
      const project = semanticQuery.parts[0] ?? '';
      const projectGroup = (item: any) =>
        item?.indexState && item.indexState !== 'ready'
          ? `${l('datastudio.aiChat.mention.groupSemantic')}·${l(
              'datastudio.aiChat.mention.semanticIndexPending'
            )}`
          : l('datastudio.aiChat.mention.groupSemantic');
      // 第二层：项目内资产（已选定项目，可继续按资产名筛选）
      if (project && (semanticQuery.trailing || semanticQuery.parts.length > 1)) {
        const keyword = (
          semanticQuery.trailing ? '' : (semanticQuery.parts[1] ?? '')
        ).toLowerCase();
        // UAT 2026-10-22：表与指标可能同名（U23 场景）。同名时若仍插入短式，两者文本完全一样，
        // 既分不清也无法命中——直接生成**长式句柄**（@semantic/<p>/table|metric/<名>），选中即无歧义。
        const seen = new Set<string>();
        const duplicated = new Set<string>();
        semanticAssets.forEach((item) => {
          const asset = item.name.split('/').pop() ?? '';
          if (seen.has(asset)) {
            duplicated.add(asset);
          } else {
            seen.add(asset);
          }
        });
        return semanticAssets
          .filter((item) => {
            const asset = item.name.split('/').pop() ?? '';
            return !keyword || asset.toLowerCase().includes(keyword);
          })
          .map((item) => {
            const asset = item.name.split('/').pop() ?? '';
            if (!duplicated.has(asset)) {
              return { ...item, group: l('datastudio.aiChat.mention.groupSemanticAsset') };
            }
            const kind = item.assetType === 'metric' ? 'metric' : 'table';
            return {
              ...item,
              name: `${project}/${kind}/${asset}`,
              group: `${l('datastudio.aiChat.mention.groupSemanticAsset')}·${
                kind === 'metric'
                  ? l('datastudio.aiChat.mention.kindMetric')
                  : l('datastudio.aiChat.mention.kindTable')
              }`
            };
          })
          .slice(0, 50);
      }
      // 第一层（含"在项目名上继续筛选"）：已发布项目
      const keyword = project.toLowerCase();
      return semanticProjects
        .filter((item: any) => !keyword || `${item.name}`.toLowerCase().includes(keyword))
        .map((item: any) => ({
          type: 'semantic' as const,
          name: item.name,
          // 项目是中间层级：选中即 `project/` → 自动列该项目下的资产
          chainable: true,
          group: projectGroup(item)
        }))
        .slice(0, 30);
    }
    // UAT 2026-10-22：只输入 `@` 时（query 为空）候选框一片空白，用户不知道能引用什么。
    // 给一组"前缀入口"，选中后保持浮层打开、继续选下一级（见 pickMention 的 type='prefix' 分支）。
    if (!mentionQuery.trim()) {
      const prefixItem = (name: string, group: string): AiChatMentionItem & { group: string } => ({
        type: 'prefix',
        name,
        group
      });
      return [
        prefixItem('source/', l('datastudio.aiChat.mention.groupSource')),
        prefixItem('skill/', l('datastudio.aiChat.mention.groupSkill')),
        prefixItem('semantic/', l('datastudio.aiChat.mention.groupSemantic')),
        prefixItem('topic/', l('datastudio.aiChat.mention.groupTopic')),
        prefixItem('env/', l('datastudio.aiChat.mention.groupEnv')),
        ...list
      ].slice(0, 30);
    }
    return list;
    // mentionOpen 作为依赖：每次打开浮层都重新读取最新的选中片段
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    schemas,
    effectiveSchemaName,
    tabs,
    activeTab,
    tabParams?.taskId,
    mentionOpen,
    mentionQuery,
    skillOptions,
    sourceQuery,
    dataSources,
    envOptions,
    topicOptions,
    sourceTreeTick,
    semanticQuery,
    semanticProjects,
    semanticAssets
  ]);

  /** 过滤 + 排序：最近用过 > 前缀匹配 > 其余 */
  const filteredMentions = useMemo(() => {
    const q = mentionQuery.trim().toLowerCase();
    // 阶段 4a 的 @skill/<名> / 4b 的 @doc/<名> / 4b 补充的 @table/<表名>——
    // 前缀之后才是名称，此时只保留对应类型的候选（旧分隔符「-」同样识别）
    const assetPrefix = /^doc[-/]/.test(q)
      ? 'doc'
      : /^skill[-/]/.test(q)
        ? 'skill'
        : /^table[-/]/.test(q)
          ? 'table'
          : /^source[-/]/.test(q)
            ? 'source'
            : /^topic[-/]/.test(q)
              ? 'topic'
              : /^env[-/]/.test(q)
                ? 'env'
                : /^semantic[-/]/.test(q)
                  ? 'semantic'
                  : '';
    if (assetPrefix) {
      if (assetPrefix === 'source' || assetPrefix === 'semantic') {
        // P1/P3b：路径式前缀的候选已在 mentionCandidates 里按「层级 + 该层关键字」算好，
        // 而名字是路径（ds/db/tbl 或 project/asset）——再拿整串 query 过滤一次只会把候选全过滤掉。
        // （历史教训：@table/ 曾因漏这个特例而恒空。）
        return mentionCandidates.slice(0, 20);
      }
      const assetKey = q.slice(assetPrefix.length + 1);
      return mentionCandidates
        .filter((c) => c.type === assetPrefix && c.name?.toLowerCase().includes(assetKey))
        .slice(0, 20);
    }
    const matched = q
      ? mentionCandidates.filter((c) =>
          c.type === 'column'
            ? `${c.name}.${c.columnName}`.toLowerCase().includes(q)
            : c.name?.toLowerCase().includes(q)
        )
      : mentionCandidates;
    return [...matched]
      .sort((a, b) => {
        const ra = recentMentions.indexOf(a.name);
        const rb = recentMentions.indexOf(b.name);
        if (ra !== -1 || rb !== -1) {
          return (
            (ra === -1 ? Number.MAX_SAFE_INTEGER : ra) - (rb === -1 ? Number.MAX_SAFE_INTEGER : rb)
          );
        }
        const pa = a.name?.toLowerCase().startsWith(q) ? 0 : 1;
        const pb = b.name?.toLowerCase().startsWith(q) ? 0 : 1;
        return pa - pb;
      })
      .slice(0, 20);
  }, [mentionCandidates, mentionQuery, recentMentions]);

  /** 取输入框当前光标位（拿不到按末尾处理，等价于改造前行为） */
  const caretOf = (): number => {
    const el = inputRef.current?.resizableTextArea?.textArea as HTMLTextAreaElement | undefined;
    return typeof el?.selectionStart === 'number' ? el.selectionStart : inputValue.length;
  };

  /**
   * 把「光标所在的 @token」替换为 replacement，并把光标移到替换文本之后。
   *
   * <p>定位与替换规则抽在 `mentionPath.ts`（纯函数）：改造前用的是
   * {@code inputValue.replace(/@[^\s@]*$/, ...)}，只认**全文末尾**，所以「选中数据源后光标回退、
   * 在已有引用上继续补层级」永远替换不到（尾部还留着插入时的空格）。
   */
  const applyMentionText = (replacement: string) => {
    const next = replaceMentionToken(inputValue, caretOf(), replacement);
    setInputValue(next.value);
    requestAnimationFrame(() => {
      const el = inputRef.current?.resizableTextArea?.textArea as HTMLTextAreaElement | undefined;
      if (el) {
        el.focus();
        el.selectionStart = el.selectionEnd = next.caret;
      }
    });
  };

  /**
   * P1：mention → 输入框 token。
   *
   * <p><b>为什么抽成函数</b>：token 规则原本在「候选选中 / onChange 清理 / 退格整块删除」
   * 三处各写了一遍；新增三类引用（source / topic / env）若继续复制，任何一处漏改都会表现为
   * 「chip 与文本不同步」（删了文本而 chip 不消失）。
   */
  const mentionToken = (m: AiChatMentionItem): string => {
    switch (m.type) {
      case 'column':
        return `@${m.name}.${m.columnName}`;
      case 'skill':
        return `@skill/${m.name}`;
      case 'doc':
        return `@doc/${m.name}`;
      case 'source':
        return `@source/${m.name}`;
      case 'topic':
        return `@topic/${m.name}`;
      case 'env':
        return `@env/${m.name}`;
      case 'semantic':
        // P3b：name 已是路径（project 或 project/asset），直接拼前缀即可
        return `@semantic/${m.name}`;
      default:
        return `@${m.name}`;
    }
  };

  /** 输入框变化：解析光标前的 {@code @query}（要求 @ 前为空白或行首） */
  const handleInputChange = (e: any) => {
    const value: string = e.target.value ?? '';
    setInputValue(value);
    // 同步清理：输入框中已删掉 @name 的引用，对应 chips 一并移除（UAT 反馈 2026-09-28）
    setMentions((prev) =>
      prev.length > 0
        ? prev.filter((m) => {
            // 字段引用的 token 是 @表名.字段名，只比对表名会误删同名表的其它字段引用；
            // 阶段 4a：skill 的 token 是 @skill/<名>（2026-10-06 起），旧写法 @skill-<名> 仍视为引用
            const token = mentionToken(m);
            // 阶段 4b 补：table 类型有三种合法 token——旧语法 `@表名` 与显式前缀 `@table-表名`
            // / `@table/表名`，任一仍存在于输入框即视为被引用（否则用前缀选中后会被这里误删）
            if (m.type === 'table') {
              return (
                value.includes(`@${m.name}`) ||
                value.includes(`@table-${m.name}`) ||
                value.includes(`@table/${m.name}`)
              );
            }
            if (m.type === 'skill' || m.type === 'doc') {
              const legacy = `@${m.type}-${m.name}`;
              return value.includes(token) || value.includes(legacy);
            }
            return value.includes(token);
          })
        : prev
    );
    if (composingRef.current) {
      return;
    }
    // UAT 2026-10-23：按**光标前**文本解析，而不是全文末尾——否则「选中数据源后把光标移回名字
    // 末尾补一个 /」不会弹下一级候选（尾部还留着上一次插入的空格，整串不在末尾）
    const caret =
      typeof e.target.selectionStart === 'number' ? e.target.selectionStart : value.length;
    const beforeCaret = value.slice(0, caret);
    const match = /@([^\s@]*)$/.exec(beforeCaret);
    if (match) {
      setMentionOpen(true);
      setMentionQuery(match[1] ?? '');
      setMentionIndex(0);
    } else {
      setMentionOpen(false);
      setMentionQuery('');
    }
  };

  /**
   * 选中候选项。
   *
   * <p>三种落法（UAT 2026-10-23 分级改造）：
   * <ol>
   *   <li>{@code prefix}：一级前缀入口，只补前缀、保持浮层；</li>
   *   <li><b>中间层级</b>（{@code chainable}：数据源 / 库 / 语义项目）：补成 {@code 路径/} 并保持浮层，
   *       下一级候选由 {@code mentionQuery} 解析自动给出——「选中即自动进下一级」，
   *       不需要把光标移回名字末尾补斜杠；</li>
   *   <li>终端候选（表 / 资产 / skill / topic / env / 作业 / 片段）：{@code @token} + 空格 + chip + 关浮层。</li>
   * </ol>
   *
   * @param forceFinal 强制按终端落地——用户按空格表示「就在当前这一层收尾」时使用
   */
  const pickMention = (item: AiChatMentionItem, forceFinal = false) => {
    // UAT 2026-10-22：选中"一级前缀入口"（`@` → `@source/` 等）时不生成 chip，
    // 而是把前缀补进输入框并**保持浮层打开**，让用户接着选下一级（与手打前缀完全等价）
    if (item.type === 'prefix') {
      applyMentionText(`@${item.name}`);
      setMentionQuery(item.name);
      setMentionIndex(0);
      setMentionOpen(true);
      return;
    }
    // 阶段 4b 补：以 @table/ 前缀输入时生成 `@table/<表名>`；旧语法（直接 @ 选表）仍生成
    // `@<表名>`——保证 C2「与迭代前完全一致」这条兼容性红线不被破坏。
    // 2026-10-06：分隔符统一为斜杠，选中后一律生成新语法；手打旧语法（连字符）后端仍解析。
    const viaTablePrefix = /^table[-/]/.test(mentionQuery.trim().toLowerCase());
    const token =
      item.type === 'table' && viaTablePrefix ? `@table/${item.name}` : mentionToken(item);

    // 中间层级：补斜杠、保持浮层（不建 chip、不记最近使用——这一层还不是一次「引用」）
    if (item.chainable && !forceFinal) {
      applyMentionText(`${token}/`);
      // 程序改 value 不会触发 onChange，这里手动同步 query（与上面 prefix 分支同理）
      setMentionQuery(`${token.replace(/^@/, '')}/`);
      setMentionIndex(0);
      setMentionOpen(true);
      return;
    }

    applyMentionText(`${token} `);
    setMentions((prev) =>
      prev.some(
        (m) => m.type === item.type && m.name === item.name && m.columnName === item.columnName
      )
        ? prev
        : [...prev, item]
    );
    setMentionOpen(false);
    setMentionQuery('');
    const nextRecent = [item.name, ...recentMentions.filter((n) => n !== item.name)].slice(0, 10);
    setRecentMentions(nextRecent);
    try {
      localStorage.setItem(RECENT_MENTION_KEY, JSON.stringify(nextRecent));
    } catch (e) {
      /* localStorage 不可用时忽略，不影响主流程 */
    }
  };

  /** 浮层打开时接管方向键 / 回车 / Tab / Esc */
  const handleInputKeyDown = (e: any) => {
    // @串的退格体验（2026-10-09 调整；此前是「2026-10-01 一按就整块删」）：
    //   · 输入**中途**按退格 → 逐字符删（UAT 反馈：打错一个字只能整串重打）；
    //   · @串**已输完**（其后跟着分隔符，如空格）→ 第一下只删分隔符、第二下整块删。
    // 判据（为何需要"两下"这个窗口：上述两种情形的文本完全一样）与分隔符取值理由见 mentionBackspace.ts；
    // 这里只负责「应用判决 + 同步 chips 与浮层状态」。
    if (e.key === 'Backspace') {
      if (!composingRef.current) {
        const el = e.target as HTMLTextAreaElement;
        const start: number = el.selectionStart ?? 0;
        const end: number = el.selectionEnd ?? 0;
        const decision = decideMentionBackspace(inputValue, start, end, atomicDeleteArmRef.current);
        if (decision.action === 'atomic') {
          // 第二下：整块删（@ 连同 表名[.字段] / 多级路径）
          const nextValue =
            inputValue.slice(0, decision.removeStart) + inputValue.slice(decision.removeEnd);
          e.preventDefault();
          atomicDeleteArmRef.current = null;
          setInputValue(nextValue);
          // 与 onChange 的同步清理保持一致：token 没了，对应 chips 一并移除
          setMentions((prev) => prev.filter((m) => nextValue.includes(mentionToken(m))));
          setMentionOpen(false);
          setMentionQuery('');
          requestAnimationFrame(() => {
            el.selectionStart = el.selectionEnd = decision.removeStart;
          });
          return;
        }
        // separator：本次走浏览器默认行为（只删那个分隔符），但要记住「下一按可整块删」
        atomicDeleteArmRef.current = decision.action === 'separator' ? decision.arm : null;
      }
    } else if (!MODIFIER_KEYS.has(e.key)) {
      // 任何其它**非修饰键**都会打断「两下删除」的序列（避免隔了几步再按退格仍误删整个 @串）；
      // 单独按 Shift/Ctrl/Alt/Meta 不算（否则按住 Shift 再退格会莫名失效）
      atomicDeleteArmRef.current = null;
    }
    // UAT 2026-10-23：空格 = 「就在当前这一层收尾」（中间层级改造的配套出口）。
    //   ① 已输入到 `路径/`（刚选完上级、还没选下级）→ 按当前路径收尾（去掉尾斜杠）；
    //   ② 高亮项本身是中间层级（打了关键字想停在这一层）→ 直接用该项收尾；
    // 其余情形空格照常输入。注意：这段必须在下面的「候选为空就 return」之前，
    // 否则「下一级还没加载出候选」时用户就无法在上一层收尾。
    if (e.key === ' ' && mentionOpen && !e.shiftKey && !composingRef.current) {
      const finalized = finalizePathMention(mentionQuery);
      if (finalized) {
        e.preventDefault();
        pickMention(finalized as AiChatMentionItem, true);
        return;
      }
      const highlighted = filteredMentions[mentionIndex];
      if (highlighted?.chainable) {
        e.preventDefault();
        pickMention(highlighted, true);
        return;
      }
    }
    if (!mentionOpen || filteredMentions.length === 0) {
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setMentionIndex((i) => (i + 1) % filteredMentions.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setMentionIndex((i) => (i - 1 + filteredMentions.length) % filteredMentions.length);
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      e.preventDefault();
      pickMention(filteredMentions[mentionIndex]);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setMentionOpen(false);
    }
  };

  const appendToLastAssistant = (text: string) => {
    setMessages((prev) => {
      const next = [...prev];
      const last = next[next.length - 1];
      if (last && last.role === 'assistant') {
        next[next.length - 1] = { ...last, content: last.content + text };
      }
      return next;
    });
  };

  /** 更新最后一条 assistant 消息的 SQL 校验状态（阶段 0：正确性闭环） */
  const updateLastVerify = (patch: AiChatVerify) => {
    setMessages((prev) => {
      const next = [...prev];
      const last = next[next.length - 1];
      if (last && last.role === 'assistant') {
        next[next.length - 1] = { ...last, verify: { ...(last.verify ?? {}), ...patch } };
      }
      return next;
    });
  };

  /** 追加模型的思考过程（reasoning），与正文分开存放 */
  const appendReasoning = (text: string) => {
    setMessages((prev) => {
      const next = [...prev];
      const last = next[next.length - 1];
      if (last && last.role === 'assistant') {
        next[next.length - 1] = { ...last, reasoning: (last.reasoning ?? '') + text };
      }
      return next;
    });
  };

  /**
   * 追加或更新一条工具步骤（阶段 1b）。
   *
   * <p>后端按 toolCallId 先后下发 toolCall（已发起）与 toolResult（已结束），这里据此把同一条
   * 记录从"执行中"就地更新为"成功/失败"，而不是新增两条——消息是流式追加的，不支持重排。
   */
  const upsertToolStep = (step: AiChatToolStep) => {
    setMessages((prev) => {
      const next = [...prev];
      const last = next[next.length - 1];
      if (!last || last.role !== 'assistant') {
        return next;
      }
      const tools = [...(last.tools ?? [])];
      const index = tools.findIndex((item) => item.toolCallId === step.toolCallId);
      if (index >= 0) {
        tools[index] = { ...tools[index], ...step };
      } else {
        tools.push(step);
      }
      next[next.length - 1] = { ...last, tools };
      return next;
    });
  };

  const handleSend = async (action: 'TEXT_TO_SQL' | 'EXPLAIN') => {
    const text = inputValue.trim();
    if (action === 'TEXT_TO_SQL' && !text) {
      return;
    }
    // 解释：有选中片段时解释选中片段，否则解释全文（阶段 2b 体验优化）
    if (action === 'EXPLAIN' && !(readSelectedSql().trim() || currentSql?.trim())) {
      message.warning(l('datastudio.aiChat.noSqlToExplain'));
      return;
    }

    const userContent = text || l('datastudio.aiChat.explainCurrentSql');
    const history = messages.map((item) => ({ role: item.role, content: item.content }));
    /**
     * P4：上下文指纹——本轮「绑定来源集合」的哈希。
     *
     * <p>仅用于变化检测（不是安全边界），所以用同步的 FNV-1a，不给发送链路引入异步等待。
     * 范围档位与自选表清单不在绑定模型里，但它们同样改变注入内容，因此作为附加来源纳入。
     */
    const fingerprint = buildFingerprint(binding, [
      `scope:${contextScope}`,
      contextScope === 'custom' ? `custom:${[...customTables].sort().join(',')}` : ''
    ]);
    /**
     * 指纹变化 = 语境变了，本轮必须让后端**重发上下文**。
     *
     * <p>后端只在「首轮」（{@code sessionId} 为空）或指纹与上次不同时注入 schema 与语义清单，
     * 后续轮次一律沿用首轮内容（`(schema 已在首轮提供，请沿用)`）。
     * 因此切 tab / 换语义项目 / 临时覆盖数据源时，本轮不带 sessionId，等价于开新一轮上下文
     * （UAT 2026-10-22：切项目后 AI 仍按旧项目清单一）。
     */
    const contextChanged = lastFingerprintRef.current !== fingerprint;
    lastFingerprintRef.current = fingerprint;
    if (contextChanged) {
      sessionIdRef.current = `${Date.now()}`;
    }

    // 主动发问时必定要看到回答：强制恢复「贴底跟随」，即使用户刚才在翻历史
    stickToBottomRef.current = true;
    setMessages((prev) => [
      ...prev,
      { role: 'user', content: userContent },
      { role: 'assistant', content: '' }
    ]);
    setInputValue('');
    // 引用是一次性的：发送后清空，避免下一轮提问误带上一轮的表
    setMentions([]);
    setLoading(true);

    const controller = new AbortController();
    abortRef.current = controller;
    /** 阶段 2：累积本轮流式正文，Craft 结束后据此抽取完整代码块 */
    let streamed = '';

    try {
      await aiChatStream(
        {
          action,
          message: text,
          history,
          // 首轮不带 sessionId：后端据此判断需要下发 schema 上下文；
          // 上下文指纹变化的那一轮同样不带（见上面 contextChanged 说明）
          sessionId: history.length > 0 && !contextChanged ? sessionIdRef.current : undefined,
          databaseId: metaDataAvailable ? databaseId : undefined,
          schemaName: effectiveSchemaName,
          tableName: effectiveTableName,
          dialect,
          // 阶段 1.0「作业上下文绑定」：编辑区内容全动作下发（早期仅 EXPLAIN 携带，
          // 导致正常提问时模型看不到用户正在写的代码），并带上作业 id 以支持排障提问。
          // P4：资源文件 tab 的正文取 params.content；作业/语义文件仍取 statement
          sql: currentSql,
          taskId: binding.editor?.taskId,
          // 阶段 1a（1.0.4）：选中片段优先；无选中时后端回退为上面的 sql 全文
          selectedSql: readSelectedSql() || undefined,
          // 阶段 1a（1.1）：上下文范围档位
          contextScope,
          customTables:
            contextScope === 'custom' && customTables.length > 0 ? customTables : undefined,
          // 阶段 1a（1.4）：@ 显式引用，后端最高优先级且不裁剪
          mentions: mentions.length > 0 ? mentions : undefined,
          // 阶段 2：Craft 改写模式。后端仍会独立校验配置开关，未开启一律回落 ask
          mode,
          // 阶段 3：所选 LLM 实例；不传 / 传不存在的值 → 后端回落默认实例
          profileId,
          // P3b：「项目级资产清单常驻」（D17）。P4 起改由绑定模型统一决策：
          // 手动覆盖 > 语义文件 tab 所属项目 > 语义树当前项目；都没有则不注入
          semanticProject: effectiveSemanticProjectOf(binding),
          // P4：上下文指纹——后端据此判断本轮是否按首轮重注入（切 tab 时 sessionId 不变）
          contextFingerprint: fingerprint
        },
        ({
          content,
          reasoning,
          sql,
          status,
          execResult,
          toolCall,
          toolResult,
          runId,
          confirmRequest
        }) => {
          // 阶段 2c-0：记录运行 id；收到写语句确认请求即弹框
          if (runId) {
            runIdRef.current = runId;
          }
          if (confirmRequest) {
            setWriteConfirm(confirmRequest);
          }
          if (reasoning) {
            appendReasoning(reasoning);
          }
          if (content) {
            streamed += content;
            appendToLastAssistant(content);
          }
          if (sql) {
            updateLastVerify({ sql });
          }
          if (status) {
            updateLastVerify({ status: status as AiChatVerify['status'] });
          }
          if (execResult) {
            updateLastVerify(execResult);
          }
          if (toolCall) {
            upsertToolStep(toolCall);
          }
          if (toolResult) {
            upsertToolStep(toolResult);
          }
        },
        (errorMessage) => {
          message.error(errorMessage);
          appendToLastAssistant(`\n[ERROR] ${errorMessage}`);
        },
        controller.signal
      );

      // 阶段 2（Craft）：流结束后抽取 AI 产出的完整内容，出 diff 预览交给用户拍板
      if (mode === 'craft') {
        const modified = extractFirstCodeBlock(streamed);
        if (modified) {
          const original = editorRegistry?.getContent(editorKey) ?? '';
          // 阶段 2c-2 收尾：内容**未发生实际变化**时不弹采纳框。
          // Craft 模板要求模型「输出有且仅有一个完整代码块」，因此在执行类 / 咨询类提问下，
          // 模型常原样返回编辑器内容，此前会无意义地弹出采纳框（即使采纳也不会改变任何内容）。
          if (modified.trim() === original.trim()) {
            message.info(l('datastudio.aiChat.craft.noChange'));
          } else {
            setCraftDiff({ original, modified });
          }
        } else {
          message.warning(l('datastudio.aiChat.craft.noCodeBlock'));
        }
      }
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        message.error(e?.message ?? String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * 停止本次运行（阶段 2c-0）：先请求服务端中断（让工具循环 / 等待确认真正取消），再断开 SSE 流。
   */
  const handleStop = () => {
    const runId = runIdRef.current;
    if (runId) {
      // 旁路：中断请求失败不阻断本地断流
      cancelAiChat(runId);
    }
    abortRef.current?.abort();
    setLoading(false);
  };

  /** 阶段 2c-0：回传写语句执行确认结果（确认即执行；拒绝 / 超时则不执行） */
  const handleWriteConfirm = async (approve: boolean) => {
    const current = writeConfirm;
    // 阶段 4b：删除类确认需带手输名称（后端校验一致才执行）——先取出再清空本地状态
    const typedName = confirmTypedName;
    setWriteConfirm(null);
    setConfirmTypedName('');
    if (!current?.runId) {
      return;
    }
    await confirmAiChat(current.runId, approve, typedName || undefined);
  };

  /**
   * 阶段 4b：本次确认是否为 **skill 类**（写 skill 文件 / 删除 skill）。
   *
   * <p>「写确认」这条通道从 2c-0 起是给 SQL 用的；4b 的 skill 写工具复用了同一通道，后端会
   * 额外下发 {@code kind}。缺省或 {@code kind='sql'} 走原有 SQL 渲染（兼容旧后端）。
   */
  const isSkillConfirm = !!writeConfirm?.kind && writeConfirm.kind !== 'sql';

  /**
   * 阶段 2b：对编辑区选中片段发起 Fix（基于最近执行报错）/ Rewrite（综合优化）。
   *
   * <p>单轮、只产出 SQL、<b>不执行</b>；结果经 diff 预览由用户确认后，仅替换选中片段。
   */
  const handleFixRewrite = async (action: 'FIX_SQL' | 'REWRITE_SQL') => {
    const selected = readSelectedSql().trim();
    if (!selected) {
      message.warning(l('datastudio.aiChat.p2b.noSelection'));
      return;
    }
    setP2bLoading(true);
    let rewriteSql = '';
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await aiChatStream(
        {
          action,
          message: action === 'FIX_SQL' ? '请修复这段 SQL' : '请对这段 SQL 做综合优化改写',
          databaseId: metaDataAvailable ? databaseId : undefined,
          // P4（U37）：修复/改写此前漏传表定位与语义项目，导致「优化 SQL」拿不到字段与口径；
          // 现在与 handleSend 同源（都取绑定结果），不再各自拼一套
          schemaName: effectiveSchemaName,
          tableName: effectiveTableName,
          semanticProject: effectiveSemanticProjectOf(binding),
          dialect,
          sql: currentSql,
          taskId: binding.editor?.taskId,
          selectedSql: selected,
          // 阶段 2b：把前端暂存的「最近一次执行报错」带上（编辑器执行报错不落后端库）；
          // 按 taskId 取不到时回退到全局最近一条，避免因 tab/taskId 不匹配而漏带
          executionError: getExecError(tabParams?.taskId) || getLatestExecError() || undefined,
          // 阶段 3：所选 LLM 实例（**所有 action 一致透传**，非仅取数类）
          profileId
        },
        ({ sql }) => {
          if (sql) {
            rewriteSql = sql;
          }
        },
        (errorMessage) => message.error(errorMessage),
        controller.signal
      );
      if (rewriteSql.trim()) {
        setP2bDiff({ action, original: selected, modified: rewriteSql });
      } else {
        message.warning(l('datastudio.aiChat.p2b.noCodeBlock'));
      }
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        message.error(e?.message ?? String(e));
      }
    } finally {
      setP2bLoading(false);
    }
  };

  /** 阶段 2b：采纳——仅替换编辑器中选中的片段（选区外不动，保留 undo 栈） */
  const handleP2bAccept = () => {
    if (!p2bDiff || !editorKey) {
      setP2bDiff(null);
      return;
    }
    const ok = editorRegistry?.applyToSelection(editorKey, p2bDiff.modified) ?? false;
    if (ok) {
      message.success(l('datastudio.aiChat.p2b.applied'));
      setSelectedText('');
    } else {
      message.warning(l('datastudio.aiChat.p2b.applyFailed'));
    }
    setP2bDiff(null);
  };

  /**
   * 抽取助手回复中的第一个代码块内容。
   *
   * <p>Craft 的 prompt 要求模型输出「有且仅有一个」完整代码块，因此取第一个即可；
   * 取不到时返回 undefined —— 调用方提示并<b>跳过改写</b>，绝不拿正文去替换编辑器内容。
   */
  const extractFirstCodeBlock = (text: string): string | undefined => {
    const m = /```[a-zA-Z0-9]*\s*\n([\s\S]*?)```/.exec(text ?? '');
    return m ? m[1] : undefined;
  };

  /** 阶段 2（Craft）：采纳——整块替换编辑器内容，并留改动前快照供撤销 */
  const handleCraftAccept = () => {
    if (!craftDiff || !editorKey) {
      setCraftDiff(null);
      return;
    }
    const key = editorKey;
    // 连续多轮 Craft 时保留最初的原文，撤销一次回到最初的模样
    if (craftBeforeRef.current[key] === undefined) {
      craftBeforeRef.current[key] = craftDiff.original;
    }
    const ok = editorRegistry?.applyFullContent(editorKey, craftDiff.modified) ?? false;
    if (ok) {
      setCraftApplied((prev) => ({ ...prev, [key]: true }));
      message.success(l('datastudio.aiChat.craft.applied'));
      // 阶段 2（T2-5）：落写入审计——谁、哪个作业、改动前后 hash 与字符数变化
      reportCraftWrite({
        // 语义文件 tab 没有作业 id（任务态才写审计关联），此处允许为空
        taskId: tabParams?.taskId,
        sessionId: sessionIdRef.current,
        before: craftDiff.original,
        after: craftDiff.modified
      });
    } else {
      message.warning(l('datastudio.aiChat.craft.applyFailed'));
    }
    setCraftDiff(null);
  };

  /** 阶段 2（Craft）：拒绝——丢弃本次改动，编辑器内容完全不变 */
  const handleCraftReject = () => setCraftDiff(null);

  /** 阶段 2（Craft）：撤销——回退到 AI 改动前的内容 */
  const handleCraftUndo = () => {
    if (!editorKey) {
      return;
    }
    const key = editorKey;
    const before = craftBeforeRef.current[key];
    if (before !== undefined) {
      editorRegistry?.applyFullContent(editorKey, before);
      delete craftBeforeRef.current[key];
      message.success(l('datastudio.aiChat.craft.undoDone'));
    }
    setCraftApplied((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const handleInsertSql = (sql: string) => {
    updateAction({
      actionType: DataStudioActionType.TASK_INSERT_SQL,
      params: { sql: sql.endsWith('\n') ? sql : `${sql}\n` }
    });
    message.success(l('datastudio.aiChat.inserted'));
  };

  /**
   * AI 产出 SQL 的「执行」——复刻编辑器点运行的链路。
   *
   * <p><b>此前这里是空壳</b>：只把 SQL 插入编辑器，并不真正执行——`TASK_RUN_SUBMIT` 仅被
   * Service 面板消费用于「切到输出 Tab」，全链路没有任何地方触发 `submitTask`，所以点了没反应。
   * 现在与 `SqlTask#handleSubmit` 的执行段保持一致：提交 `/api/task/submitTask` 后，执行日志经
   * WebSocket 落到「输出」面板，结果集经 `TASK_PREVIEW_RESULT` 落入「结果」面板。
   *
   * <p>前提与编辑器运行一致：当前 Tab 必须<b>已保存为作业</b>（`submitTask` 需要 taskId 且校验属主）。
   */
  const handleRunSql = async (sql: string) => {
    const taskId = tabParams?.taskId;
    if (!taskId) {
      message.warning(l('datastudio.aiChat.needSavedTask'));
      return;
    }
    // 一并写入编辑器：结果不理想时，用户可直接在编辑器里复用 / 微调这条 SQL
    handleInsertSql(sql);
    // 打开下方「输出」面板（执行日志经 WebSocket FlinkSubmit/{taskId} 推送到该面板）
    updateAction({
      actionType: DataStudioActionType.TASK_RUN_SUBMIT,
      params: { taskId }
    });
    try {
      const result = await executeSql(l('pages.datastudio.editor.exec'), taskId, sql);
      if (result?.success && isSql(dialect) && result?.data?.result?.success) {
        updateAction({
          actionType: DataStudioActionType.TASK_PREVIEW_RESULT,
          params: {
            taskId,
            dialect,
            columns: result.data.result.columns,
            rowData: result.data.result.rowData
          }
        });
      }
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        message.error(e?.message ?? String(e));
      }
    }
  };

  /**
   * 复制到剪贴板。
   *
   * <p>navigator.clipboard 仅在 secure context（HTTPS / localhost）下存在；内网以 IP + HTTP
   * 访问时为 undefined，此前直接使用导致点击「复制」静默失败。此处降级为 textarea +
   * execCommand 兜底。
   */
  const copyToClipboard = (text: string) => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(
        () => message.success(l('datastudio.aiChat.copySuccess')),
        () => fallbackCopy(text)
      );
      return;
    }
    fallbackCopy(text);
  };

  const fallbackCopy = (text: string) => {
    try {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(textarea);
      ok
        ? message.success(l('datastudio.aiChat.copySuccess'))
        : message.warning(l('datastudio.aiChat.copyFailed'));
    } catch (e) {
      message.warning(l('datastudio.aiChat.copyFailed'));
    }
  };

  /** 渲染 SQL 校验状态条（生成 → 执行校验 → 报错自动修复） */
  const renderVerify = (verify: AiChatVerify) => {
    if (!verify.status && !verify.sql) {
      return null;
    }
    const { status, rowCount, costMs, error } = verify;
    let color = 'default';
    let text = '';
    if (status === 'verifying') {
      color = 'processing';
      text = l('datastudio.aiChat.verify.verifying');
    } else if (status === 'verified') {
      color = 'success';
      text = `${l('datastudio.aiChat.verify.verified')}（${rowCount ?? 0} 行 / ${costMs ?? 0}ms）`;
    } else if (status === 'retrying') {
      color = 'warning';
      text = l('datastudio.aiChat.verify.retrying');
    } else if (status === 'failed') {
      color = 'error';
      text = l('datastudio.aiChat.verify.failed');
    } else if (status === 'rejected') {
      color = 'warning';
      text = l('datastudio.aiChat.verify.rejected');
    } else {
      return null;
    }
    return (
      <div style={{ marginTop: 6 }}>
        <Tag color={color} style={{ marginInlineEnd: 0 }}>
          {text}
        </Tag>
        {status === 'failed' || status === 'rejected' ? (
          <div
            style={{
              whiteSpace: 'pre-wrap',
              marginTop: 4,
              padding: 6,
              borderRadius: 4,
              background: 'rgba(0,0,0,0.04)',
              fontSize: 12,
              color: 'rgba(0,0,0,0.65)'
            }}
          >
            {error}
          </div>
        ) : null}
      </div>
    );
  };

  const renderContent = (content: string) => {
    const nodes: React.ReactNode[] = [];
    let lastIndex = 0;
    let match: RegExpExecArray | null;
    CODE_BLOCK_REGEX.lastIndex = 0;
    while ((match = CODE_BLOCK_REGEX.exec(content)) !== null) {
      if (match.index > lastIndex) {
        nodes.push(
          <div key={`text-${lastIndex}`} className={'ai-chat-md'}>
            <ReactMarkdown remarkPlugins={[remarkGfm]}>
              {content.slice(lastIndex, match.index)}
            </ReactMarkdown>
          </div>
        );
      }
      const code = match[1];
      nodes.push(
        <div
          key={`code-${match.index}`}
          style={{
            background: 'rgba(0,0,0,0.04)',
            borderRadius: 4,
            padding: 8,
            margin: '6px 0'
          }}
        >
          <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{code}</pre>
          <Space size={4} style={{ marginTop: 6 }}>
            <Button size={'small'} type={'link'} onClick={() => handleInsertSql(code)}>
              {l('datastudio.aiChat.insertToEditor')}
            </Button>
            <Button size={'small'} type={'link'} onClick={() => handleRunSql(code)}>
              {l('datastudio.aiChat.run')}
            </Button>
            <Button
              size={'small'}
              type={'link'}
              icon={<CopyOutlined />}
              onClick={() => copyToClipboard(code)}
            />
          </Space>
        </div>
      );
      lastIndex = CODE_BLOCK_REGEX.lastIndex;
    }
    if (lastIndex < content.length) {
      nodes.push(
        <div key={`text-${lastIndex}`} className={'ai-chat-md'}>
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{content.slice(lastIndex)}</ReactMarkdown>
        </div>
      );
    }
    return nodes;
  };

  if (config && config.enable === false) {
    return (
      <div style={{ padding: 24 }}>
        <Empty
          image={<RobotOutlined style={{ fontSize: 40 }} />}
          description={
            <Typography.Text type={'secondary'}>{l('datastudio.aiChat.disabled')}</Typography.Text>
          }
        />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 8 }}>
      <Space direction={'vertical'} size={4} style={{ width: '100%' }}>
        {/* P0：FlinkSQL 有其专属上下文（无数据源也要给出「已加载」的正确预期，而不是「请绑定数据源」） */}
        {flinkContextAvailable && (
          <Alert type={'info'} showIcon message={l('datastudio.aiChat.flinkContextLoaded')} />
        )}
        {/*
          P4 一期：顶栏由「黄条 + 数据源/Schema/表 三个常驻 Select」改为**一行只读摘要 + popover**。
          - 默认自动跟随编辑区（作业 / 表 / 资源文件 / 语义文件），不再要求手选；
          - 黄条已移除：未绑定时摘要行直接说明「打开作业/表后再提问」，不阻断对话；
          - 原控件全部搬进 popover 作为「临时覆盖」，因此「手动指定数据源」能力不退化（U42/U43）。
        */}
        <ContextBar
          binding={binding}
          datasourceList={datasourceList}
          overrideDatasourceId={datasourceId}
          onDatasourceChange={setDatasourceId}
          metaDataAvailable={metaDataAvailable}
          schemas={schemas}
          overrideSchemaName={schemaName}
          overrideTableName={tableName}
          onSchemaChange={(value) => {
            setSchemaName(value);
            // 换 schema 后旧表名已失效，必须清空（否则下发的表在新 schema 里不存在）
            setTableName(undefined);
          }}
          onTableChange={setTableName}
          contextScope={contextScope}
          onScopeChange={(value) => setContextScope(value as ContextScope)}
          customTables={customTables}
          onCustomTablesChange={setCustomTables}
          tableOptions={tableOptions}
          onUseTreeProject={() =>
            setSemanticProjectOverride(binding.semanticTreeConflict?.treeProject)
          }
          onResetOverride={resetContextOverride}
          overridden={
            datasourceId !== undefined ||
            schemaName !== undefined ||
            tableName !== undefined ||
            semanticProjectOverride !== undefined ||
            customTables.length > 0
          }
        />
      </Space>

      <div
        // 消息区：流式回答时自动跟随最新输出行（用户手动上翻时暂停跟随，见 handleMessageListScroll）
        ref={messageListRef}
        onScroll={handleMessageListScroll}
        style={{
          flex: 1,
          overflowY: 'auto',
          margin: '8px 0',
          border: '1px solid rgba(0,0,0,0.06)',
          borderRadius: 4,
          padding: 8
        }}
      >
        {messages.length === 0 ? (
          <Empty
            image={<RobotOutlined style={{ fontSize: 40 }} />}
            description={l('datastudio.aiChat.placeholder')}
          />
        ) : (
          messages.map((item, index) => (
            <div
              key={index}
              style={{
                textAlign: item.role === 'user' ? 'right' : 'left',
                marginBottom: 12
              }}
            >
              <div
                style={{
                  display: 'inline-block',
                  maxWidth: '92%',
                  textAlign: 'left',
                  background: item.role === 'user' ? 'rgba(22,119,255,0.08)' : 'rgba(0,0,0,0.03)',
                  borderRadius: 6,
                  padding: '6px 10px'
                }}
              >
                {item.role === 'assistant' && item.reasoning ? (
                  <div style={{ marginBottom: 6 }}>
                    <Typography.Link
                      style={{ fontSize: 12 }}
                      onClick={() =>
                        setExpandedReasoning((prev) => ({ ...prev, [index]: !prev[index] }))
                      }
                    >
                      {expandedReasoning[index]
                        ? l('datastudio.aiChat.hideReasoning')
                        : l('datastudio.aiChat.showReasoning')}
                    </Typography.Link>
                    {expandedReasoning[index] ? (
                      <div
                        style={{
                          whiteSpace: 'pre-wrap',
                          marginTop: 4,
                          padding: 6,
                          borderRadius: 4,
                          background: 'rgba(0,0,0,0.04)',
                          fontSize: 12,
                          color: 'rgba(0,0,0,0.55)'
                        }}
                      >
                        {item.reasoning}
                      </div>
                    ) : null}
                  </div>
                ) : null}
                {item.role === 'assistant' && item.tools && item.tools.length > 0 ? (
                  <ToolProcess steps={item.tools} />
                ) : null}
                {item.role === 'assistant' ? (
                  renderContent(item.content)
                ) : (
                  <span style={{ whiteSpace: 'pre-wrap' }}>{item.content}</span>
                )}
                {item.role === 'assistant' && item.verify ? renderVerify(item.verify) : null}
              </div>
            </div>
          ))
        )}
      </div>

      {/* 阶段 2b（局部改写）：编辑区有非空选中时，输入框上方浮出「修复/改写」操作条 */}
      {selectedText.trim() && (
        <div
          style={{
            marginBottom: 4,
            padding: '4px 8px',
            background: 'rgba(22,119,255,0.06)',
            border: '1px solid rgba(22,119,255,0.25)',
            borderRadius: 4,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8
          }}
        >
          <span
            style={{
              fontSize: 12,
              color: 'rgba(0,0,0,0.65)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap'
            }}
            title={selectedText}
          >
            {l('datastudio.aiChat.p2b.selectionPrefix')} {selectedText.length}{' '}
            {l('datastudio.aiChat.p2b.chars')}
          </span>
          <Space size={4} style={{ flexShrink: 0 }}>
            <Button
              size={'small'}
              type={'primary'}
              ghost
              loading={p2bLoading}
              onClick={() => handleFixRewrite('FIX_SQL')}
            >
              {l('datastudio.aiChat.p2b.fix')}
            </Button>
            <Button
              size={'small'}
              loading={p2bLoading}
              onClick={() => handleFixRewrite('REWRITE_SQL')}
            >
              {l('datastudio.aiChat.p2b.rewrite')}
            </Button>
          </Space>
        </div>
      )}
      {/* 阶段 1a（1.4）：@ 引用浮层——固定贴输入框上方，不追随光标（省去坐标计算，更稳） */}
      <div style={{ position: 'relative' }}>
        {mentionOpen && (
          <div
            style={{
              position: 'absolute',
              bottom: '100%',
              left: 0,
              right: 0,
              zIndex: 20,
              marginBottom: 4,
              maxHeight: 220,
              overflowY: 'auto',
              background: 'var(--primary-color, #fff)',
              border: '1px solid rgba(0,0,0,0.12)',
              borderRadius: 4,
              boxShadow: '0 4px 12px rgba(0,0,0,0.12)'
            }}
          >
            {filteredMentions.length === 0 ? (
              <div style={{ padding: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
                {metaDataAvailable
                  ? l('datastudio.aiChat.mention.noMatch')
                  : l('datastudio.aiChat.mention.noDataSource')}
                {/* P1：跨源引用的用法提示——@source/ 有层级要求（且受展开上限），
                    @topic/ 候选只含已被作业使用的 topic，空结果时给一句用法比只显示"无匹配项"有用 */}
                {/^(source|topic|env)[-/]/.test(mentionQuery.trim().toLowerCase()) ? (
                  <div style={{ marginTop: 4 }}>{l('datastudio.aiChat.mention.sourceUsage')}</div>
                ) : null}
              </div>
            ) : (
              filteredMentions.map((item, index) => (
                <div
                  key={`${item.type}-${item.name}-${item.columnName ?? ''}-${index}`}
                  // 用 onMouseDown 而非 onClick：避免先触发输入框 blur 导致浮层关闭
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pickMention(item);
                  }}
                  onMouseEnter={() => setMentionIndex(index)}
                  style={{
                    padding: '6px 8px',
                    cursor: 'pointer',
                    display: 'flex',
                    justifyContent: 'space-between',
                    gap: 8,
                    background: index === mentionIndex ? 'rgba(22,119,255,0.10)' : 'transparent'
                  }}
                >
                  <span style={{ fontSize: 12 }}>
                    {item.type === 'column'
                      ? `${item.name}.${item.columnName}`
                      : // 中间层级在列表里就带 `/`：让「选中后会进下一级」这件事可见，
                        // 与一级前缀入口（source/、semantic/）的视觉语言保持一致
                        item.chainable
                        ? `${item.name}/`
                        : item.name}
                  </span>
                  <span style={{ fontSize: 11, color: 'rgba(0,0,0,0.45)', flexShrink: 0 }}>
                    {recentMentions.includes(item.name)
                      ? l('datastudio.aiChat.mention.recent')
                      : item.group}
                  </span>
                </div>
              ))
            )}
          </div>
        )}
        <Input.TextArea
          // @ 候选的解析/替换依赖光标位置（支持「光标回退补 /」与中间层级续接）
          ref={inputRef}
          value={inputValue}
          onChange={handleInputChange}
          onCompositionStart={() => {
            composingRef.current = true;
          }}
          onCompositionEnd={(e: any) => {
            composingRef.current = false;
            handleInputChange(e);
          }}
          onKeyDown={handleInputKeyDown}
          autoSize={{ minRows: 2, maxRows: 6 }}
          placeholder={l('datastudio.aiChat.inputPlaceholder')}
          onPressEnter={(e) => {
            if (!e.shiftKey && !mentionOpen) {
              e.preventDefault();
              handleSend('TEXT_TO_SQL');
            }
          }}
        />
      </div>
      {/* 阶段 1a（1.4）：已引用项——输入框下方 chips，可逐个删除 */}
      {mentions.length > 0 && (
        <div style={{ marginTop: 4 }}>
          <Space size={4} wrap>
            {mentions.map((item) => (
              <Tag
                key={`${item.type}-${item.name}-${item.columnName ?? ''}`}
                color={'blue'}
                closable
                onClose={() =>
                  setMentions((prev) =>
                    prev.filter(
                      (m) =>
                        !(
                          m.type === item.type &&
                          m.name === item.name &&
                          m.columnName === item.columnName
                        )
                    )
                  )
                }
              >
                {item.type === 'column' ? `${item.name}.${item.columnName}` : item.name}
              </Tag>
            ))}
          </Space>
        </div>
      )}
      <Space wrap style={{ marginTop: 8, rowGap: 6 }}>
        {/* 模型名跟随「当前所选实例」——此前写死默认实例的 config.model，切换下拉不会更新；
            实例本身缺 model 时再回落到默认实例，避免出现空白 */}
        <Tooltip
          title={`${l('datastudio.aiChat.modelTip')}${
            activeProfile?.name ? ` · ${activeProfile.name}` : ''
          }`}
        >
          <Tag
            icon={<RobotOutlined />}
            color={(activeProfile?.hasApiKey ?? config?.hasApiKey) ? 'success' : 'warning'}
            style={{ marginRight: 0 }}
          >
            {activeProfile?.model || config?.model || l('datastudio.aiChat.unconfigured')}
          </Tag>
        </Tooltip>
        {/* 阶段 3：多 LLM 实例选择——仅当管理员配置了多个实例时才渲染（单实例保持界面简洁） */}
        {profileList.length > 1 && (
          <Tooltip title={l('datastudio.aiChat.profileTip')}>
            <Select
              size={'small'}
              style={{ minWidth: 130 }}
              value={activeProfileId}
              onChange={(v) => setProfileId(v as string)}
              options={profileList.map((item) => ({ label: item.name ?? item.id, value: item.id }))}
            />
          </Tooltip>
        )}
        {/* 阶段 3：该实例声明不支持工具调用时，提示将退化为纯问答（避免"工具链静默失效"） */}
        {activeProfile && activeProfile.supportsTools === false && (
          <Tooltip title={l('datastudio.aiChat.profileNoToolsTip')}>
            <Tag color={'warning'} style={{ marginRight: 0 }}>
              {l('datastudio.aiChat.profileNoTools')}
            </Tag>
          </Tooltip>
        )}
        {/* 阶段 2：Ask / Craft 切换——仅管理员开启 Craft 时渲染（不展示无功能的控件） */}
        {config?.craftModeEnable && (
          <Tooltip title={l('datastudio.aiChat.modeTip')}>
            <Segmented
              value={mode}
              onChange={(v) => setMode(v as 'ask' | 'craft')}
              options={[
                { label: 'Ask', value: 'ask' },
                { label: 'Craft', value: 'craft' }
              ]}
            />
          </Tooltip>
        )}
        {craftApplied[editorKey ?? ''] && (
          <Tooltip title={l('datastudio.aiChat.craft.undo')}>
            <Button icon={<RollbackOutlined />} onClick={handleCraftUndo}>
              {l('datastudio.aiChat.craft.undo')}
            </Button>
          </Tooltip>
        )}
        <Tooltip title={l('datastudio.aiChat.explainTip')}>
          <Button
            icon={<PlayCircleOutlined />}
            disabled={loading || !isSqlJobTab || !currentSql?.trim()}
            onClick={() => handleSend('EXPLAIN')}
          >
            {l('datastudio.aiChat.explain')}
          </Button>
        </Tooltip>
        {loading ? (
          <Button danger icon={<StopOutlined />} onClick={handleStop}>
            {l('datastudio.aiChat.stop')}
          </Button>
        ) : (
          <Button
            type={'primary'}
            icon={<SendOutlined />}
            disabled={!inputValue.trim()}
            onClick={() => handleSend('TEXT_TO_SQL')}
          >
            {l('datastudio.aiChat.send')}
          </Button>
        )}
      </Space>
      {/* 阶段 2（Craft）：改动预览——未点采纳时编辑器内容绝不变动 */}
      <CraftDiff
        open={!!craftDiff}
        original={craftDiff?.original ?? ''}
        modified={craftDiff?.modified ?? ''}
        language={dialect || 'flinksql'}
        onAccept={handleCraftAccept}
        onReject={handleCraftReject}
      />
      {/* 阶段 2b（局部改写）：选中片段 vs AI 稿，采纳后仅替换选中片段 */}
      <SqlDiff
        open={!!p2bDiff}
        original={p2bDiff?.original ?? ''}
        modified={p2bDiff?.modified ?? ''}
        language={dialect || 'flinksql'}
        onAccept={handleP2bAccept}
        onReject={() => setP2bDiff(null)}
      />
      {/*
        阶段 2c-0：写语句（DML/DDL）执行前的二次确认——后端挂起等待，未确认绝不执行。
        阶段 4b：本通道被 skill 写工具复用（kind=skill_file / skill_delete），故标题与按钮
        文案按 kind 调整；内容区的分支渲染见下方。
      */}
      <Modal
        title={
          isSkillConfirm
            ? (writeConfirm?.title ?? l('datastudio.aiChat.confirm.skillTitle'))
            : l('datastudio.aiChat.writeConfirm.title')
        }
        open={!!writeConfirm}
        maskClosable={false}
        onCancel={() => handleWriteConfirm(false)}
        footer={
          <Space>
            <Button onClick={() => handleWriteConfirm(false)}>
              {l('datastudio.aiChat.writeConfirm.reject')}
            </Button>
            <Authorized path={PermissionConstants.DATASTUDIO_AI_CHAT_WRITE}>
              <Button
                danger
                type={'primary'}
                // 阶段 4b：删除类确认要求手输名称与后端下发的目标名完全一致才能点击（防误删）
                disabled={
                  !!writeConfirm?.requireTypedName &&
                  confirmTypedName.trim() !== (writeConfirm?.targetName ?? '')
                }
                onClick={() => handleWriteConfirm(true)}
              >
                {writeConfirm?.kind === 'skill_delete'
                  ? l('datastudio.aiChat.confirm.deleteAccept')
                  : l('datastudio.aiChat.writeConfirm.accept')}
              </Button>
            </Authorized>
          </Space>
        }
      >
        {/* 阶段 4b：按 kind 分支。此前无分支 → 用 SQL 的字段渲染 skill 载荷，屏幕上就只剩
            「空白的语句内容 + 语句类型 UNKNOWN」（sqlType 由后端在无 risk 时置为该字面量）。 */}
        {isSkillConfirm ? (
          <>
            <div style={{ marginBottom: 8 }}>
              <Alert
                type={writeConfirm?.kind === 'skill_delete' ? 'error' : 'info'}
                showIcon
                message={
                  writeConfirm?.kind === 'skill_delete'
                    ? l('datastudio.aiChat.confirm.skillDeleteTip')
                    : l('datastudio.aiChat.confirm.skillFileTip')
                }
              />
            </div>
            <div style={{ marginBottom: 8, fontSize: 12, lineHeight: '22px' }}>
              <Typography.Text type={'secondary'}>
                {writeConfirm?.kind === 'skill_delete'
                  ? l('datastudio.aiChat.confirm.skillName')
                  : l('datastudio.aiChat.confirm.skillTarget')}
              </Typography.Text>
              <Typography.Text code>{writeConfirm?.targetName ?? '-'}</Typography.Text>
              {writeConfirm?.kind === 'skill_file' && writeConfirm?.relativePath ? (
                <>
                  <Typography.Text type={'secondary'}>
                    {l('datastudio.aiChat.confirm.skillPath')}
                  </Typography.Text>
                  <Typography.Text code>{writeConfirm.relativePath}</Typography.Text>
                </>
              ) : null}
              {writeConfirm?.kind === 'skill_file' && !writeConfirm?.beforeContent ? (
                <Typography.Text type={'secondary'}>
                  {l('datastudio.aiChat.confirm.newFileHint')}
                </Typography.Text>
              ) : null}
            </div>
            {writeConfirm?.kind === 'skill_delete' && writeConfirm?.requireTypedName ? (
              <div style={{ marginBottom: 8, fontSize: 12 }}>
                <Typography.Text type={'secondary'}>
                  {l('datastudio.aiChat.confirm.typeNameHint')}
                </Typography.Text>
                <Input
                  size={'small'}
                  style={{ width: 240, marginLeft: 8 }}
                  value={confirmTypedName}
                  placeholder={writeConfirm?.targetName}
                  onChange={(e) => setConfirmTypedName(e.target.value)}
                />
              </div>
            ) : null}
            {writeConfirm?.kind === 'skill_file' ? (
              <pre
                style={{
                  maxHeight: '40vh',
                  overflow: 'auto',
                  background: 'rgba(0,0,0,0.04)',
                  padding: 8,
                  borderRadius: 4,
                  margin: 0,
                  whiteSpace: 'pre-wrap'
                }}
              >
                {writeConfirm?.afterContent ?? ''}
              </pre>
            ) : null}
          </>
        ) : (
          <>
            <div style={{ marginBottom: 8 }}>
              <Alert
                type={writeConfirm?.sqlType === 'DDL' ? 'error' : 'warning'}
                showIcon
                message={
                  writeConfirm?.sqlType === 'DDL'
                    ? l('datastudio.aiChat.writeConfirm.ddlTip')
                    : l('datastudio.aiChat.writeConfirm.dmlTip')
                }
              />
            </div>
            {/* 阶段 2c-1：变更风险块（语句类型 / 目标对象 / AI 估计影响）——
            「AI 估计」必须显式标注，不得渲染成精确值 */}
            <div style={{ marginBottom: 8, fontSize: 12, lineHeight: '22px' }}>
              {/* 阶段 2c-2：自动纠错重试中——告知这是第几次尝试、上次为什么失败，
              让用户在「每次写库仍必确认」的前提下知情决策 */}
              {writeConfirm?.attempt && writeConfirm.attempt > 1 ? (
                <div style={{ marginBottom: 4 }}>
                  <Typography.Text type={'warning'}>
                    {l('datastudio.aiChat.writeConfirm.retryPrefix')}
                    {writeConfirm.attempt}
                    {l('datastudio.aiChat.writeConfirm.retrySuffix')}
                  </Typography.Text>
                  {writeConfirm.previousError ? (
                    <Typography.Text type={'secondary'}>
                      {' '}
                      {l('datastudio.aiChat.writeConfirm.previousError')}
                      {writeConfirm.previousError}
                    </Typography.Text>
                  ) : null}
                </div>
              ) : null}
              <div>
                <Typography.Text type={'secondary'}>
                  {l('datastudio.aiChat.writeConfirm.risk.sqlType')}
                </Typography.Text>
                <Tag color={writeConfirm?.sqlType === 'DDL' ? 'red' : 'orange'}>
                  {writeConfirm?.sqlType ?? '-'}
                </Tag>
                {writeConfirm?.risk?.target ? (
                  <>
                    <Typography.Text type={'secondary'}>
                      {l('datastudio.aiChat.writeConfirm.risk.target')}
                    </Typography.Text>
                    <Typography.Text code>{writeConfirm.risk.target}</Typography.Text>
                  </>
                ) : null}
              </div>
              {writeConfirm?.risk?.modelEstimate ? (
                <div>
                  <Typography.Text type={'secondary'}>
                    {l('datastudio.aiChat.writeConfirm.risk.estimate')}
                  </Typography.Text>
                  <Typography.Text>{writeConfirm.risk.modelEstimate}</Typography.Text>
                  <Typography.Text type={'secondary'}>
                    {l('datastudio.aiChat.writeConfirm.risk.estimateNote')}
                  </Typography.Text>
                </div>
              ) : null}
            </div>
            <pre
              style={{
                maxHeight: '40vh',
                overflow: 'auto',
                background: 'rgba(0,0,0,0.04)',
                padding: 8,
                borderRadius: 4,
                margin: 0,
                whiteSpace: 'pre-wrap'
              }}
            >
              {writeConfirm?.sql}
            </pre>
          </>
        )}
      </Modal>
    </div>
  );
};

export default connect(
  ({ DataStudio }: { DataStudio: any }) => ({
    tabs: DataStudio.centerContent.tabs,
    activeTab: DataStudio.centerContent.activeTab,
    // P3b：语义树当前项目（切换语义项目不切 tab，见 model.tsx 的 saveCurrentSemanticProject 注释）
    currentSemanticProject: DataStudio.currentSemanticProject
  }),
  mapDispatchToProps
)(AiChat);
