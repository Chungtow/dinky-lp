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

import React from 'react';
import { Button, Collapse, Space, Table, Tag, Typography } from 'antd';
import { CopyOutlined, ImportOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { FlinkJobDraft } from '../../flinkJobDraft';

const { Paragraph, Text } = Typography;

type Props = {
  draft: FlinkJobDraft;
  onApplyDraft: (draft: FlinkJobDraft) => void;
  onRunSql: (sql: string) => void;
};

/**
 * 四段式作业卡片（M1-D）。
 *
 * <p>四段各自可折叠、可复制：漏看「自定义配置」或「建表 DDL」是这类作业最常见的踩坑点，
 * 因此不合并成一坨代码块，而是分段呈现 + 底部两个动作（写入草稿 / 直接试跑）。
 */
const FlinkJobCard: React.FC<Props> = ({ draft, onApplyDraft, onRunSql }) => {
  const precheckStatusColor = (status?: string) => {
    if (status === 'pass') return 'success';
    if (status === 'fail') return 'error';
    return 'warning';
  };

  const section = (key: string, header: string, node: React.ReactNode, extra?: React.ReactNode) => ({
    key,
    label: (
      <Space size={6}>
        <Text strong>{header}</Text>
        {extra}
      </Space>
    ),
    children: node
  });

  const copy = (text: string) => {
    navigator.clipboard?.writeText(text);
  };

  const items = [
    section(
      'sql',
      '1. FlinkSQL',
      <div>
        <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>{draft.sql}</pre>
        <Button size={'small'} type={'link'} icon={<CopyOutlined />} onClick={() => copy(draft.sql ?? '')}>
          复制 SQL
        </Button>
      </div>
    ),
    section(
      'customConfig',
      '2. 自定义配置',
      draft.customConfig && draft.customConfig.length > 0 ? (
        <Table
          size={'small'}
          pagination={false}
          rowKey={'key'}
          dataSource={draft.customConfig}
          columns={[
            { title: 'key', dataIndex: 'key', width: '55%' },
            { title: 'value', dataIndex: 'value' }
          ]}
        />
      ) : (
        <Text type={'secondary'}>无需自定义配置</Text>
      )
    ),
    section(
      'ddl',
      '3. 建表 DDL',
      draft.ddl && draft.ddl.length > 0 ? (
        draft.ddl.map((item, index) => (
          <div key={`ddl-${index}`} style={{ marginBottom: 8 }}>
            <Text type={'secondary'}>
              {item.engine ?? 'db'} / {item.database ?? '-'}.{item.table ?? '-'}
            </Text>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>
              {item.statement}
            </pre>
          </div>
        ))
      ) : (
        <Text type={'secondary'}>无（目标表已存在）</Text>
      )
    ),
    section(
      'precheck',
      '4. 前置检查',
      draft.precheck && draft.precheck.length > 0 ? (
        <Table
          size={'small'}
          pagination={false}
          rowKey={(record) => `${record.item ?? ''}-${record.how ?? ''}`}
          dataSource={draft.precheck}
          columns={[
            { title: '检查项', dataIndex: 'item' },
            { title: '方法', dataIndex: 'how' },
            {
              title: '结论',
              dataIndex: 'status',
              width: 96,
              render: (status?: string) => <Tag color={precheckStatusColor(status)}>{status ?? 'unknown'}</Tag>
            }
          ]}
        />
      ) : (
        <Text type={'warning'}>未提供前置检查，执行前请自行确认</Text>
      )
    )
  ];

  return (
    <div
      style={{
        border: '1px solid rgba(0,0,0,0.08)',
        borderRadius: 6,
        padding: 10,
        margin: '8px 0',
        background: 'rgba(0,0,0,0.01)'
      }}
    >
      <Space size={6} style={{ marginBottom: 6 }}>
        <Text strong>FlinkSQL 作业草案</Text>
        {draft.name ? <Tag color={'blue'}>{draft.name}</Tag> : null}
      </Space>
      <Paragraph type={'secondary'} style={{ marginBottom: 6, fontSize: 12 }}>
        写入草稿只会填充编辑器与自定义配置，<Text strong>不会提交作业</Text>；执行前请确认前置检查结论。
      </Paragraph>
      <Collapse size={'small'} defaultActiveKey={['sql']} items={items} />
      <Space size={8} style={{ marginTop: 8 }}>
        <Button size={'small'} type={'primary'} icon={<ImportOutlined />} onClick={() => onApplyDraft(draft)}>
          写入草稿
        </Button>
        <Button size={'small'} icon={<PlayCircleOutlined />} onClick={() => onRunSql(draft.sql ?? '')}>
          写入并执行
        </Button>
      </Space>
    </div>
  );
};

export default FlinkJobCard;
