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
import { AiChatConfig, AiChatMessage, AiChatVerify, aiChatStream, getAiChatConfig } from './service';
import { l } from '@/utils/intl';
import {
  CopyOutlined,
  PlayCircleOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined
} from '@ant-design/icons';
import { connect } from '@umijs/max';
import { Alert, Button, Empty, Input, Select, Space, Tag, Tooltip, Typography, message } from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';

const CODE_BLOCK_REGEX = /```[a-zA-Z]*\s*\n?([\s\S]*?)```/g;

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
          taskId: tabParams?.taskId
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
          <Space size={4}>
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

      <Input.TextArea
        value={inputValue}
        onChange={(e) => setInputValue(e.target.value)}
        autoSize={{ minRows: 2, maxRows: 6 }}
        placeholder={l('datastudio.aiChat.inputPlaceholder')}
        onPressEnter={(e) => {
          if (!e.shiftKey) {
            e.preventDefault();
            handleSend('TEXT_TO_SQL');
          }
        }}
      />
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
