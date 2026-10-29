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

import { l } from '@/utils/intl';
import { DownOutlined, WarningOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Divider,
  Popover,
  Segmented,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography
} from 'antd';
import React from 'react';
import type { ContextBinding } from './aiChatContext';
import { describeBinding, effectiveSemanticProjectOf } from './aiChatContext';

/** 上下文范围档位（与请求体 contextScope 同源） */
export type ContextScope = 'current' | 'all' | 'custom';

export interface ContextBarProps {
  /** 解析后的绑定结果（唯一出口产物） */
  binding: ContextBinding;
  /** 数据源清单（popover 里手动覆盖时的候选） */
  datasourceList: any[];
  /** 手动覆盖：数据源（undefined = 跟随编辑区） */
  overrideDatasourceId?: number;
  onDatasourceChange: (id?: number) => void;
  /** 是否有可下发的元数据（有数据源且是 SQL 类作业 / 表 tab） */
  metaDataAvailable: boolean;
  /** 当前数据源的 schema 列表（含 tables） */
  schemas: any[];
  /** 手动覆盖：schema（undefined = 跟随编辑区） */
  overrideSchemaName?: string;
  onSchemaChange: (name?: string) => void;
  /** 手动覆盖：表（undefined = 跟随编辑区） */
  overrideTableName?: string;
  onTableChange: (name?: string) => void;
  /** 范围档位（原 Segmented） */
  contextScope: ContextScope;
  onScopeChange: (scope: ContextScope) => void;
  /** custom 档位下勾选的表 */
  customTables: string[];
  onCustomTablesChange: (tables: string[]) => void;
  /** 当前 schema 下的表候选 */
  tableOptions: { label: string; value: string }[];
  /** 语义项目冲突：一键改用语义树当前项目 */
  onUseTreeProject: () => void;
  /** 清空全部手动覆盖，回到「自动跟随编辑区」 */
  onResetOverride: () => void;
  /** 是否存在任何手动覆盖（用于展示「恢复自动跟随」） */
  overridden: boolean;
  /**
   * 批次 E：解除「默认知识库」绑定（只清本会话的问答范围，不动左侧栏的选中）。
   *
   * <p>为什么不做成"恢复自动跟随"那种整体重置：知识库绑定与表上下文是两件事，
   * 用户可能只想让这一轮不限定库、而保留数据源/表绑定。
   */
  onClearKbBinding?: () => void;
}

/** 详情项标签的 i18n key（结构由 describeBinding 产出，文案由这里决定） */
const labelKey = (key: string) => `datastudio.aiChat.context.label.${key}`;

/**
 * P4 一期：AI Chat 顶部「上下文摘要行 + popover」（计划 §3.5 B 方案）。
 *
 * <p>取代原来的「黄条 + 数据源/Schema/表三个常驻 Select」：
 * - 默认**自动跟随编辑区**（由 `aiChatContext.resolveBinding` 解析），不再要求手选；
 * - 摘要行让「本轮 AI 到底拿到了什么」可见（可解释性出口）；
 * - 点 `⌄` 展开 popover：①详情（含语义项目冲突提示与一键切换）；②**临时覆盖**（原控件搬入）；
 *   ③范围档位（原 Segmented 搬入）。
 *
 * <p>本组件是**受控纯展示组件**：状态与请求体全部由 `index.tsx` 持有，这里只负责展示
 * 与把用户操作回抛，避免出现「UI 一套上下文、请求另一套上下文」。
 */
const ContextBar = (props: ContextBarProps) => {
  const {
    binding,
    datasourceList,
    overrideDatasourceId,
    onDatasourceChange,
    metaDataAvailable,
    schemas,
    overrideSchemaName,
    onSchemaChange,
    overrideTableName,
    onTableChange,
    contextScope,
    onScopeChange,
    customTables,
    onCustomTablesChange,
    tableOptions,
    onUseTreeProject,
    onResetOverride,
    overridden,
    onClearKbBinding
  } = props;

  const { items, empty } = describeBinding(binding);
  const conflict = binding.semanticTreeConflict;
  /** 冲突是否已被「改用树项目」化解（决定提示文案是「按 A 回答」还是「已改用 B」） */
  const usingTree = Boolean(
    conflict && effectiveSemanticProjectOf(binding) === conflict.treeProject
  );

  /**
   * 摘要行文案：只读、单行、超长省略。
   *
   * <p>空绑定时给「打开作业/表后再提问」而不是原来的黄条——不阻断对话，
   * 也顺带解释了「为什么这一轮 AI 什么都不知道」。
   */
  const summary = empty
    ? l('datastudio.aiChat.context.empty')
    : items.map((item) => `${l(labelKey(item.key))} ${item.value}`).join(' ｜ ');

  /** 降级提示（empty 已由摘要行承载，不再重复） */
  const notices = (binding.notices ?? []).filter((code) => code !== 'empty');

  const content = (
    <div style={{ width: 320 }}>
      <Typography.Text type={'secondary'} style={{ fontSize: 12 }}>
        {l('datastudio.aiChat.context.detail')}
      </Typography.Text>
      <div style={{ marginTop: 4 }}>
        {empty ? (
          <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
            {l('datastudio.aiChat.context.emptyDetail')}
          </div>
        ) : (
          items.map((item) => (
            <div
              key={item.key}
              style={{ display: 'flex', gap: 6, fontSize: 12, lineHeight: '20px' }}
            >
              <span style={{ color: 'rgba(0,0,0,0.45)', flexShrink: 0 }}>
                {l(labelKey(item.key))}
              </span>
              <span style={{ wordBreak: 'break-all' }}>{item.value}</span>
            </div>
          ))
        )}
      </div>

      {/* P4 唯一的冲突场景：树项目 ≠ 编辑区文件所属项目。默认按文件（正在看的内容优先），
          这里给出说明与「改用树项目」出口（U34/U35）。
          点了「改用」之后本轮实际按树项目回答，提示文案必须跟着变——
          否则会出现「下面写着按 A 回答，实际按 B 回答」的自相矛盾 */}
      {conflict ? (
        <Alert
          style={{ marginTop: 8 }}
          type={usingTree ? 'info' : 'warning'}
          showIcon
          message={
            usingTree
              ? l('datastudio.aiChat.context.conflictUsingTree', undefined, {
                  project: conflict.treeProject,
                  file: conflict.fileProject
                })
              : l('datastudio.aiChat.context.conflict', undefined, {
                  tree: conflict.treeProject,
                  file: conflict.fileProject
                })
          }
          action={
            usingTree ? undefined : (
              <Button size={'small'} type={'link'} onClick={onUseTreeProject}>
                {l('datastudio.aiChat.context.useTree', undefined, {
                  project: conflict.treeProject
                })}
              </Button>
            )
          }
        />
      ) : null}

      {notices.map((code) => (
        <div key={code} style={{ marginTop: 6, fontSize: 12, color: '#faad14' }}>
          {l(`datastudio.aiChat.context.notice.${code}`)}
        </div>
      ))}

      <Divider style={{ margin: '8px 0' }} />
      <Typography.Text type={'secondary'} style={{ fontSize: 12 }}>
        {l('datastudio.aiChat.context.override')}
      </Typography.Text>

      <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 6 }}>
        {/*
          数据源**始终**可覆盖，不受 metaDataAvailable 约束——本功能最大的价值恰恰是
          「作业未绑定数据源时也能指定一个」。清空即回落到编辑区自带的数据源。
        */}
        <Select
          allowClear
          showSearch
          size={'small'}
          style={{ minWidth: 170 }}
          placeholder={l('datastudio.aiChat.datasource')}
          // 与 option 的 id 同类型比较：编辑器侧会把 databaseId 以字符串回写 tab params，
          // 数字与字符串混用会让 antd 严格比较失败、回退渲染原始值（显示成裸 id）
          value={
            overrideDatasourceId === undefined || overrideDatasourceId === null
              ? undefined
              : String(overrideDatasourceId)
          }
          onChange={(value) => onDatasourceChange(value === undefined ? undefined : Number(value))}
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
              value={overrideSchemaName}
              onChange={(value) => onSchemaChange(value)}
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
              value={overrideTableName}
              onChange={(value) => onTableChange(value)}
              options={tableOptions}
            />
          </Space>
        )}

        {/* 阶段 1a（1.1）：custom 档位下表下拉变多选，用于勾选本次要喂给模型的表 */}
        {metaDataAvailable && contextScope === 'custom' && (
          <Select
            mode={'multiple'}
            allowClear
            showSearch
            size={'small'}
            style={{ minWidth: 200 }}
            placeholder={l('datastudio.aiChat.customTables')}
            value={customTables}
            onChange={(value: string[]) => onCustomTablesChange(value ?? [])}
            options={tableOptions}
            maxTagCount={3}
          />
        )}

        {metaDataAvailable && (
          <Tooltip title={l('datastudio.aiChat.scopeTip')}>
            <Segmented
              size={'small'}
              value={contextScope}
              onChange={(value) => onScopeChange(value as ContextScope)}
              options={[
                { label: l('datastudio.aiChat.scopeCurrent'), value: 'current' },
                { label: l('datastudio.aiChat.scopeAll'), value: 'all' },
                { label: l('datastudio.aiChat.scopeCustom'), value: 'custom' }
              ]}
            />
          </Tooltip>
        )}

        {overridden && (
          <Button
            size={'small'}
            type={'link'}
            style={{ padding: 0, alignSelf: 'flex-start' }}
            onClick={onResetOverride}
          >
            {l('datastudio.aiChat.context.reset')}
          </Button>
        )}
      </div>
    </div>
  );

  return (
    <Popover trigger={'click'} placement={'bottomLeft'} content={content} destroyTooltipOnHide>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 4,
          cursor: 'pointer',
          fontSize: 12,
          lineHeight: '20px',
          minWidth: 0
        }}
        title={summary}
      >
        <span style={{ color: 'rgba(0,0,0,0.45)', flexShrink: 0 }}>
          {l('datastudio.aiChat.context.title')}
        </span>
        <span
          style={{
            color: empty ? 'rgba(0,0,0,0.45)' : 'rgba(0,0,0,0.65)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap'
          }}
        >
          {summary}
        </span>
        {conflict ? <WarningOutlined style={{ color: '#faad14', flexShrink: 0 }} /> : null}
        {/* 批次 E：默认知识库 chip。绑定必须**可见且可解除**——它决定了本轮检索范围，
            藏起来就会出现"我明明在问答里只是问了个问题，怎么只搜了那一个库"的困惑。
            点 × 只解绑本会话的默认范围，左侧栏的选中态保持不变（D-K18）。 */}
        {binding.kbCollection ? (
          <Tag
            closable
            color={'purple'}
            style={{ flexShrink: 0, marginInlineEnd: 0, cursor: 'default' }}
            onClose={(event) => {
              // 阻止冒泡：chip 在 Popover 的触发区内，不拦截会顺手把弹层打开
              event.preventDefault();
              event.stopPropagation();
              onClearKbBinding?.();
            }}
          >
            {l('datastudio.aiChat.kbBound', undefined, { collection: binding.kbCollection })}
          </Tag>
        ) : null}
        <DownOutlined style={{ fontSize: 10, color: 'rgba(0,0,0,0.45)', flexShrink: 0 }} />
      </div>
    </Popover>
  );
};

export default ContextBar;
