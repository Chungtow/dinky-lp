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

import { isSql } from '@/pages/DataStudio/utils';
import { DataStudioActionType } from '@/pages/DataStudio/data.d';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import {
  AiChatConfig,
  AiChatMentionItem,
  AiChatMessage,
  AiChatVerify,
  aiChatStream,
  getAiChatConfig
} from './service';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
import { l } from '@/utils/intl';
import {
  CopyOutlined,
  PlayCircleOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined
} from '@ant-design/icons';
import { connect } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Input,
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
  const databaseId: number | undefined = tabParams?.databaseId ?? undefined;
  const currentSql: string = tabParams?.statement ?? '';
  const metaDataAvailable = Boolean(databaseId) && isSql(dialect?.toLowerCase());

  useEffect(() => {
    getAiChatConfig()
      .then((res) => setConfig(res))
      .catch(() => setConfig({}));
  }, []);

  useEffect(() => {
    if (!metaDataAvailable || !databaseId) {
      setSchemas([]);
      return;
    }
    showDataSourceTable(databaseId).then((res) => setSchemas(res ?? []));
  }, [databaseId, metaDataAvailable]);

  const tableOptions = useMemo(() => {
    const schema = schemas?.find((item) => item.name === schemaName);
    return (schema?.tables ?? []).map((table: any) => ({ label: table.name, value: table.name }));
  }, [schemas, schemaName]);

  // ===== 阶段 1a：Context 三档（1.1）/ @ 引用（1.4）/ 选中片段（1.0.4）=====
  const [contextScope, setContextScope] = useState<'current' | 'all' | 'custom'>('all');
  const [customTables, setCustomTables] = useState<string[]>([]);
  const [mentions, setMentions] = useState<AiChatMentionItem[]>([]);
  const [mentionOpen, setMentionOpen] = useState<boolean>(false);
  const [mentionQuery, setMentionQuery] = useState<string>('');
  const [mentionIndex, setMentionIndex] = useState<number>(0);
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

  /**
   * {@code @} 候选来源：<b>可插拔 provider 注册表</b>。
   *
   * <p>阶段 1a 内置 table / job / selection 三个 provider；后续「语料包」（阶段 1 计划 §3.4）
   * 可作为新 provider 接入，浮层与协议均无需改动。
   */
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
    return list;
    // mentionOpen 作为依赖：每次打开浮层都重新读取最新的选中片段
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schemas, schemaName, tabs, activeTab, tabParams?.taskId, mentionOpen]);

  /** 过滤 + 排序：最近用过 > 前缀匹配 > 其余 */
  const filteredMentions = useMemo(() => {
    const q = mentionQuery.trim().toLowerCase();
    const matched = q
      ? mentionCandidates.filter((c) => c.name?.toLowerCase().includes(q))
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
    setInputValue(inputValue.replace(/@[^\s@]*$/, `@${item.name} `));
    setMentions((prev) =>
      prev.some((m) => m.type === item.type && m.name === item.name) ? prev : [...prev, item]
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

  const handleSend = async (action: 'TEXT_TO_SQL' | 'EXPLAIN') => {
    const text = inputValue.trim();
    if (action === 'TEXT_TO_SQL' && !text) {
      return;
    }
    if (action === 'EXPLAIN' && !currentSql?.trim()) {
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
          mentions: mentions.length > 0 ? mentions : undefined
        },
        ({ content, reasoning, sql, status, execResult }) => {
          if (reasoning) {
            appendReasoning(reasoning);
          }
          if (content) {
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
        },
        (errorMessage) => {
          message.error(errorMessage);
          appendToLastAssistant(`\n[ERROR] ${errorMessage}`);
        },
        controller.signal
      );
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        message.error(e?.message ?? String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleStop = () => {
    abortRef.current?.abort();
    setLoading(false);
  };

  const handleInsertSql = (sql: string) => {
    updateAction({
      actionType: DataStudioActionType.TASK_INSERT_SQL,
      params: { sql: sql.endsWith('\n') ? sql : `${sql}\n` }
    });
    message.success(l('datastudio.aiChat.inserted'));
  };

  const handleRunSql = (sql: string) => {
    // 执行依赖当前 Tab 已保存为作业（TASK_RUN_SUBMIT 需要 taskId），否则点击无反应
    if (!tabParams?.taskId) {
      message.warning(l('datastudio.aiChat.needSavedTask'));
      return;
    }
    handleInsertSql(sql);
    updateAction({
      actionType: DataStudioActionType.TASK_RUN_SUBMIT,
      params: { taskId: tabParams?.taskId }
    });
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
          <div key={`text-${lastIndex}`} style={{ whiteSpace: 'pre-wrap' }}>
            {content.slice(lastIndex, match.index)}
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
        <div key={`text-${lastIndex}`} style={{ whiteSpace: 'pre-wrap' }}>
          {content.slice(lastIndex)}
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
                  key={`${item.type}-${item.name}-${index}`}
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
                  <span style={{ fontSize: 12 }}>{item.name}</span>
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
                key={`${item.type}-${item.name}`}
                closable
                onClose={() =>
                  setMentions((prev) =>
                    prev.filter((m) => !(m.type === item.type && m.name === item.name))
                  )
                }
              >
                {item.name}
              </Tag>
            ))}
          </Space>
        </div>
      )}
      <Space style={{ marginTop: 8 }}>
        <Tooltip title={l('datastudio.aiChat.modelTip')}>
          <Tag
            icon={<RobotOutlined />}
            color={config?.hasApiKey ? 'success' : 'warning'}
            style={{ marginRight: 0 }}
          >
            {config?.model || l('datastudio.aiChat.unconfigured')}
          </Tag>
        </Tooltip>
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
