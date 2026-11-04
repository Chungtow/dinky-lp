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

import { CircleBtn } from '@/components/CallBackButton/CircleBtn';
import { l } from '@/utils/intl';
import {
  BulbOutlined,
  CopyOutlined,
  DownloadOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  SettingOutlined,
  SyncOutlined
} from '@ant-design/icons';
import { Button, Checkbox, Popover, Space } from 'antd';
import React from 'react';
import { ResultColumnsState } from './useResultEntries';

type ResultToolRailProps = {
  loading?: boolean;
  /** 当前结果表的 CSV 下载地址（无数据时为空） */
  csvUrl?: string;
  columns: string[];
  columnsState?: ResultColumnsState;
  fullscreen: boolean;
  onRefresh: () => void;
  onAVA: () => void;
  onCopyPage: () => void;
  onToggleFullscreen: () => void;
  onColumnsStateChange: (columnsState: ResultColumnsState) => void;
};

/**
 * 结果表格的右侧竖向工具条。
 * 与「输出」页签右侧的 EditorFloatBtn 一致：把原本横铺一行的按钮竖起来，省下一整行高度。
 */
const ResultToolRail: React.FC<ResultToolRailProps> = (props) => {
  const {
    loading,
    csvUrl,
    columns,
    columnsState,
    fullscreen,
    onRefresh,
    onAVA,
    onCopyPage,
    onToggleFullscreen,
    onColumnsStateChange
  } = props;

  const visibleColumns = columns.filter((column) => columnsState?.[column]?.show !== false);
  const allChecked = columns.length > 0 && visibleColumns.length === columns.length;

  const handleSelectAll = (show: boolean) => {
    const next: ResultColumnsState = {};
    columns.forEach((column) => {
      next[column] = { ...(columnsState?.[column] ?? {}), show };
    });
    onColumnsStateChange(next);
  };

  const columnSetting = (
    <div style={{ maxHeight: 320, overflowY: 'auto', minWidth: 160 }}>
      <div
        style={{
          borderBottom: '1px solid var(--border-color)',
          paddingBottom: 4,
          marginBottom: 4
        }}
      >
        <Checkbox
          checked={allChecked}
          indeterminate={visibleColumns.length > 0 && !allChecked}
          onChange={(e) => handleSelectAll(e.target.checked)}
        >
          {l('button.selectAll')}
        </Checkbox>
      </div>
      <Checkbox.Group
        value={visibleColumns}
        onChange={(values) => {
          const next: ResultColumnsState = {};
          columns.forEach((column) => {
            next[column] = {
              ...(columnsState?.[column] ?? {}),
              show: (values as string[]).includes(column)
            };
          });
          onColumnsStateChange(next);
        }}
        style={{ display: 'flex', flexDirection: 'column', gap: 4 }}
      >
        {columns.map((column) => (
          <Checkbox key={column} value={column}>
            {column}
          </Checkbox>
        ))}
      </Checkbox.Group>
    </div>
  );

  return (
    <Space direction={'vertical'} align={'center'} size={2} style={{ paddingTop: 4 }}>
      <CircleBtn
        icon={<SyncOutlined spin={loading} />}
        onClick={onRefresh}
        title={l('pages.datastudio.label.result.query.latest.data')}
      />
      {csvUrl && (
        <CircleBtn icon={<DownloadOutlined />} href={csvUrl} title={'Export CSV'} />
      )}
      <CircleBtn
        icon={<CopyOutlined />}
        onClick={onCopyPage}
        title={l('pages.datastudio.label.result.copy')}
      />
      <CircleBtn icon={<BulbOutlined />} onClick={onAVA} title={l('button.ava')} />
      <Popover trigger={'click'} placement={'left'} content={columnSetting}>
        <Button
          type={'text'}
          shape={'circle'}
          block
          icon={<SettingOutlined />}
          title={l('button.setting.columns')}
        />
      </Popover>
      <CircleBtn
        icon={fullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
        onClick={onToggleFullscreen}
        title={l('button.fullscreen')}
      />
    </Space>
  );
};

export default ResultToolRail;
