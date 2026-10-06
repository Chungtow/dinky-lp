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
import { isSql } from '@/pages/DataStudio/utils';
import { DataStudioActionType } from '@/pages/DataStudio/data.d';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import {
  getDataSourceList,
  showDataSourceTable
} from '@/pages/DataStudio/Toolbar/DataSource/service';
import ToolProcess from './components/ToolProcess';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import CraftDiff from './components/CraftDiff';
import SqlDiff from './components/SqlDiff';
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
  listSkills,
  listTableColumns,
  reportCraftWrite
} from './service';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
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

type AiChatProps = {
  tabs: any[];
  activeTab?: string;
  updateAction: (payload: any) => void;
};

const AiChat = (props: AiChatProps) => {
  const { tabs, activeTab, updateAction } = props;

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

  const currentTab = tabs?.find((tab) => tab.id === activeTab);
  const tabParams = currentTab?.params ?? {};
  const dialect: string = tabParams?.dialect ?? '';
  const tabDatabaseId: number | undefined = tabParams?.databaseId ?? undefined;
  /**
   * 阶段 2 前置（1.2 多数据源切换）：面板可覆盖作业自带的数据源，默认跟随当前作业。
   *
   * <p>置为 undefined 即回落到作业数据源——下拉的「清空」因此等价于「跟随当前作业」，
   * 而不是「没有数据源」。
   */
  const [datasourceId, setDatasourceId] = useState<number | undefined>();
  const databaseId: number | undefined = datasourceId ?? tabDatabaseId;
  const [datasourceList, setDatasourceList] = useState<any[]>([]);
  const currentSql: string = tabParams?.statement ?? '';
  const metaDataAvailable = Boolean(databaseId) && isSql(dialect?.toLowerCase());

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
    const schema = schemas?.find((item) => item.name === schemaName);
    return (schema?.tables ?? []).map((table: any) => ({ label: table.name, value: table.name }));
  }, [schemas, schemaName]);

  // ===== 阶段 1a：Context 三档（1.1）/ @ 引用（1.4）/ 选中片段（1.0.4）=====
  const [contextScope, setContextScope] = useState<'current' | 'all' | 'custom'>('all');
  const [customTables, setCustomTables] = useState<string[]>([]);
  const [mentions, setMentions] = useState<AiChatMentionItem[]>([]);
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
  /** 阶段 4a：团队 Skill 候选（仅管理员开启 skillEnable 时拉取）——输入 {@code @skill-} 时展示 */
  const [skillOptions, setSkillOptions] = useState<AiChatMentionItem[]>([]);
  const [recentMentions, setRecentMentions] = useState<string[]>(() => {
    try {
      return JSON.parse(localStorage.getItem(RECENT_MENTION_KEY) ?? '[]');
    } catch (e) {
      return [];
    }
  });
  /** 输入法组合态：中文输入过程中不触发 @ 浮层，避免误弹 */
  const composingRef = useRef<boolean>(false);
  const { editorRegistry } = useContext(DataStudioContext);

  /** 读取当前作业编辑区的选中片段（无选中时返回空串） */
  const readSelectedSql = () => editorRegistry?.getSelection(tabParams?.taskId) ?? '';

  // 阶段 2b：订阅当前作业编辑区的选区变化，驱动「修复/改写」操作条的显隐
  useEffect(() => {
    const taskId = tabParams?.taskId;
    if (!taskId || !editorRegistry) {
      setSelectedText('');
      return;
    }
    return editorRegistry.onSelectionChange(taskId, (text) => setSelectedText(text));
  }, [editorRegistry, tabParams?.taskId]);

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

  useEffect(() => {
    if (!columnQuery || !databaseId || !schemaName) {
      setColumnOptions([]);
      return;
    }
    const cacheKey = `${databaseId}|${schemaName}|${columnQuery.table}`;
    const cached = columnCacheRef.current.get(cacheKey);
    if (cached) {
      setColumnOptions(cached);
      return;
    }
    let cancelled = false;
    listTableColumns(databaseId, schemaName, columnQuery.table)
      .then((cols: any[]) => {
        const list = (cols ?? []).map((col: any) => ({
          type: 'column' as const,
          name: columnQuery.table,
          columnName: col?.name,
          schemaName,
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
  }, [columnQuery, databaseId, schemaName]);

  // 阶段 4a：skill 候选——仅在管理员开启 skillEnable 时拉取；关闭时清空（避免残留旧候选）
  useEffect(() => {
    if (!config?.skillEnable) {
      setSkillOptions([]);
      return;
    }
    listSkills()
      .then((items) =>
        // 阶段 4b：同一次查询同时返回 skill 与 doc（后端按 asset_type 区分），这里映射为候选的
        // type，使 @skill- / @doc- 两个前缀都能命中同一批数据
        setSkillOptions(
          items.map((item: any) => ({
            ...item,
            type: item.assetType === 'doc' ? 'doc' : 'skill'
          }))
        )
      )
      .catch(() => setSkillOptions([]));
  }, [config?.skillEnable]);

  const mentionCandidates = useMemo(() => {
    const list: (AiChatMentionItem & { group: string })[] = [];
    // provider 1：当前 schema 下的表
    const schema: any = schemas?.find((item: any) => item.name === schemaName);
    (schema?.tables ?? []).forEach((table: any) => {
      list.push({
        type: 'table',
        name: table.name,
        schemaName,
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
    // provider 5（阶段 4a 的 skill / 阶段 4b 扩展的 doc）：输入 @skill- / @doc- 时只给对应资产候选
    const assetPrefix = mentionQuery.trim().toLowerCase().startsWith('doc-')
      ? 'doc'
      : mentionQuery.trim().toLowerCase().startsWith('skill-')
        ? 'skill'
        : '';
    if (assetPrefix) {
      return skillOptions
        .filter((item) => item.type === assetPrefix)
        .map((item) => ({
          ...item,
          group: l('datastudio.aiChat.mention.groupSkill')
        }));
    }
    return list;
    // mentionOpen 作为依赖：每次打开浮层都重新读取最新的选中片段
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schemas, schemaName, tabs, activeTab, tabParams?.taskId, mentionOpen, mentionQuery, skillOptions]);

  /** 过滤 + 排序：最近用过 > 前缀匹配 > 其余 */
  const filteredMentions = useMemo(() => {
    const q = mentionQuery.trim().toLowerCase();
    // 阶段 4a 的 @skill-<名> / 阶段 4b 的 @doc-<名> —— 前缀之后才是名称，且此时只保留对应类型候选
    const assetPrefix = q.startsWith('doc-') ? 'doc' : q.startsWith('skill-') ? 'skill' : '';
    if (assetPrefix) {
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
          return (ra === -1 ? Number.MAX_SAFE_INTEGER : ra) - (rb === -1 ? Number.MAX_SAFE_INTEGER : rb);
        }
        const pa = a.name?.toLowerCase().startsWith(q) ? 0 : 1;
        const pb = b.name?.toLowerCase().startsWith(q) ? 0 : 1;
        return pa - pb;
      })
      .slice(0, 20);
  }, [mentionCandidates, mentionQuery, recentMentions]);

  /** 输入框变化：解析光标前的 {@code @query}（要求 @ 前为空白或行首） */
  const handleInputChange = (e: any) => {
    const value: string = e.target.value ?? '';
    setInputValue(value);
    // 同步清理：输入框中已删掉 @name 的引用，对应 chips 一并移除（UAT 反馈 2026-09-28）
    setMentions((prev) =>
      prev.length > 0
        ? prev.filter((m) => {
            // 字段引用的 token 是 @表名.字段名，只比对表名会误删同名表的其它字段引用；
            // 阶段 4a：skill 的 token 是 @skill-<名>
            const token =
              m.type === 'column'
                ? `@${m.name}.${m.columnName}`
                : m.type === 'skill'
                  ? `@skill-${m.name}`
                  : m.type === 'doc'
                    ? `@doc-${m.name}`
                    : `@${m.name}`;
            return value.includes(token);
          })
        : prev
    );
    if (composingRef.current) {
      return;
    }
    const match = /@([^\s@]*)$/.exec(value);
    if (match) {
      setMentionOpen(true);
      setMentionQuery(match[1] ?? '');
      setMentionIndex(0);
    } else {
      setMentionOpen(false);
      setMentionQuery('');
    }
  };

  /** 选中候选：把 {@code @query} 替换为 {@code @name}，并记录为已引用 */
  const pickMention = (item: AiChatMentionItem) => {
    const token =
      item.type === 'column'
        ? `@${item.name}.${item.columnName}`
        : item.type === 'skill'
          ? `@skill-${item.name}`
          : item.type === 'doc'
            ? `@doc-${item.name}`
            : `@${item.name}`;
    setInputValue(inputValue.replace(/@[^\s@]*$/, `${token} `));
    setMentions((prev) =>
      prev.some(
        (m) =>
          m.type === item.type && m.name === item.name && m.columnName === item.columnName
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
    // 体验优化（2026-10-01 UAT）：Backspace 在 @token 上时整块删除（@ 连同 表名[.字段]），
    // 而不是逐字符删——把引用当作一个「原子」。TextArea 是纯文本控件无法局部高亮，
    // 先用整块删除对齐原子引用体验；局部高亮需换 Mentions/contentEditable，另行评估。
    if (e.key === 'Backspace' && !composingRef.current) {
      const el = e.target as HTMLTextAreaElement;
      const start: number = el.selectionStart ?? 0;
      const end: number = el.selectionEnd ?? 0;
      // 光标贴着 token（选中态除外）：向前找 @token 头，向后吸收残余，保证整块删除
      if (start === end && start > 0) {
        const head = /@[A-Za-z0-9_.]+$/.exec(inputValue.slice(0, start));
        // 孤零零一个 @ 保持默认逐字符删除
        if (head && head[0].length > 1) {
          const tail = /^[A-Za-z0-9_.]*/.exec(inputValue.slice(end))?.[0] ?? '';
          const cut = start - head[0].length;
          const nextValue =
            inputValue.slice(0, cut) + inputValue.slice(end + tail.length);
          e.preventDefault();
          setInputValue(nextValue);
          // 与 onChange 的同步清理保持一致：token 没了，对应 chips 一并移除
          setMentions((prev) =>
            prev.filter((m) => {
              const token =
                m.type === 'column' ? `@${m.name}.${m.columnName}` : `@${m.name}`;
              return nextValue.includes(token);
            })
          );
          setMentionOpen(false);
          setMentionQuery('');
          requestAnimationFrame(() => {
            el.selectionStart = el.selectionEnd = cut;
          });
          return;
        }
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
          // 首轮不带 sessionId：后端据此判断需要下发 schema 上下文
          sessionId: history.length > 0 ? sessionIdRef.current : undefined,
          databaseId: metaDataAvailable ? databaseId : undefined,
          schemaName,
          tableName,
          dialect,
          // 阶段 1.0「作业上下文绑定」：编辑区内容全动作下发（早期仅 EXPLAIN 携带，
          // 导致正常提问时模型看不到用户正在写的代码），并带上作业 id 以支持排障提问
          sql: currentSql,
          taskId: tabParams?.taskId,
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
          profileId
        },
        ({ content, reasoning, sql, status, execResult, toolCall, toolResult, runId, confirmRequest }) => {
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
          const original = editorRegistry?.getContent(tabParams?.taskId) ?? '';
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
          schemaName,
          dialect,
          sql: currentSql,
          taskId: tabParams?.taskId,
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
    const taskId = tabParams?.taskId;
    if (!p2bDiff || taskId === undefined) {
      setP2bDiff(null);
      return;
    }
    const ok = editorRegistry?.applyToSelection(taskId, p2bDiff.modified) ?? false;
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
    const taskId = tabParams?.taskId;
    if (!craftDiff || taskId === undefined) {
      setCraftDiff(null);
      return;
    }
    const key = String(taskId);
    // 连续多轮 Craft 时保留最初的原文，撤销一次回到最初的模样
    if (craftBeforeRef.current[key] === undefined) {
      craftBeforeRef.current[key] = craftDiff.original;
    }
    const ok = editorRegistry?.applyFullContent(taskId, craftDiff.modified) ?? false;
    if (ok) {
      setCraftApplied((prev) => ({ ...prev, [key]: true }));
      message.success(l('datastudio.aiChat.craft.applied'));
      // 阶段 2（T2-5）：落写入审计——谁、哪个作业、改动前后 hash 与字符数变化
      reportCraftWrite({
        taskId,
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
    const taskId = tabParams?.taskId;
    if (taskId === undefined) {
      return;
    }
    const key = String(taskId);
    const before = craftBeforeRef.current[key];
    if (before !== undefined) {
      editorRegistry?.applyFullContent(taskId, before);
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
      navigator.clipboard
        .writeText(text)
        .then(() => message.success(l('datastudio.aiChat.copySuccess')), () => fallbackCopy(text));
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
            <Typography.Text type={'secondary'}>
              {l('datastudio.aiChat.disabled')}
            </Typography.Text>
          }
        />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: 8 }}>
      <Space direction={'vertical'} size={4} style={{ width: '100%' }}>
        {!metaDataAvailable && (
          <Alert
            type={'warning'}
            showIcon
            message={l('datastudio.aiChat.bindGuide')}
          />
        )}
        {/*
          阶段 2 前置（1.2 多数据源切换）：始终显示，不受 metaDataAvailable 约束——
          本功能最大的价值恰恰是「作业未绑定数据源时也能指定一个」，放到分支内就自废武功。
          清空（allowClear）回落到作业数据源，因为 databaseId = datasourceId ?? tabDatabaseId。
        */}
        <Select
          allowClear
          showSearch
          size={'small'}
          style={{ minWidth: 170 }}
          placeholder={l('datastudio.aiChat.datasource')}
          // 阶段 4b 修复（缺陷 1）：value 与 option 的 id 必须同类型。
          // 编辑器侧（RunToolbar/SelectDb.tsx 的 convertValue）把 databaseId 以**字符串**回写
          // tab params，而 options 用的是数字 item.id → antd 严格比较失败后会**回退渲染原始值**，
          // 表现为「代码编辑区选的是 hive-lpods，AI Chat 面板却显示 21」。这里统一按字符串比较，
          // 回传时再转回数字，保持 setDatasourceId 的 number 语义不变。
          value={databaseId === undefined || databaseId === null ? undefined : String(databaseId)}
          onChange={(value) => setDatasourceId(value === undefined ? undefined : Number(value))}
          optionFilterProp={'label'}
          options={(datasourceList ?? []).map((item: any) => ({
            label: item.name,
            value: String(item.id)
          }))}
        />
        {metaDataAvailable && (
          <Space size={4} wrap>
            <Select
              allowClear
              size={'small'}
              style={{ minWidth: 130 }}
              placeholder={l('datastudio.aiChat.schema')}
              value={schemaName}
              onChange={(value) => {
                setSchemaName(value);
                setTableName(undefined);
              }}
              options={(schemas ?? []).map((item: any) => ({
                label: item.name,
                value: item.name
              }))}
            />
            {/* 阶段 1a（1.1）：custom 档位下表下拉变多选，用于勾选本次要喂给模型的表 */}
            {contextScope === 'custom' ? (
              <Select
                mode={'multiple'}
                allowClear
                showSearch
                size={'small'}
                style={{ minWidth: 200, maxWidth: 320 }}
                placeholder={l('datastudio.aiChat.customTables')}
                value={customTables}
                onChange={(value: string[]) => setCustomTables(value ?? [])}
                options={tableOptions}
                maxTagCount={3}
              />
            ) : (
              <Select
                allowClear
                showSearch
                size={'small'}
                style={{ minWidth: 160 }}
                placeholder={l('datastudio.aiChat.table')}
                value={tableName}
                onChange={(value) => setTableName(value)}
                options={tableOptions}
              />
            )}
            <Tooltip title={l('datastudio.aiChat.scopeTip')}>
              <Segmented
                size={'small'}
                value={contextScope}
                onChange={(value) => setContextScope(value as 'current' | 'all' | 'custom')}
                options={[
                  { label: l('datastudio.aiChat.scopeCurrent'), value: 'current' },
                  { label: l('datastudio.aiChat.scopeAll'), value: 'all' },
                  { label: l('datastudio.aiChat.scopeCustom'), value: 'custom' }
                ]}
              />
            </Tooltip>
          </Space>
        )}
      </Space>

      <div
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
                    {item.type === 'column' ? `${item.name}.${item.columnName}` : item.name}
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
        {craftApplied[String(tabParams?.taskId ?? '')] && (
          <Tooltip title={l('datastudio.aiChat.craft.undo')}>
            <Button icon={<RollbackOutlined />} onClick={handleCraftUndo}>
              {l('datastudio.aiChat.craft.undo')}
            </Button>
          </Tooltip>
        )}
        <Tooltip title={l('datastudio.aiChat.explainTip')}>
          <Button
            icon={<PlayCircleOutlined />}
            disabled={loading || !currentSql?.trim()}
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
            ? writeConfirm?.title ?? l('datastudio.aiChat.confirm.skillTitle')
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
    activeTab: DataStudio.centerContent.activeTab
  }),
  mapDispatchToProps
)(AiChat);
