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

import { DataStudioActionType } from '@/pages/DataStudio/data.d';
import Output from '@/pages/DataStudio/Toolbar/Service/Output';
import ResultTable from '@/pages/DataStudio/Toolbar/Service/Result/ResultTable';
import ResultTabLabel from '@/pages/DataStudio/Toolbar/Service/Result/ResultTabLabel';
import { useResultEntries } from '@/pages/DataStudio/Toolbar/Service/Result/useResultEntries';
import { l } from '@/utils/intl';
import { ClearOutlined, CodeOutlined, MonitorOutlined } from '@ant-design/icons';
import { Button, Empty, Popconfirm, Tabs, TabsProps } from 'antd';
import React, { useCallback, useMemo } from 'react';

type TaskServiceTabsProps = {
  taskId: number;
  dialect?: string;
  action: any;
  activeKey?: string;
  onActiveKeyChange: (key: string) => void;
  /** 历史 / 表数据 / 任务图等由外部构建的标签 */
  extraItems?: TabsProps['items'];
};

const RESULT_KEY_PREFIX = 'result-';

/**
 * 单个任务的「服务」面板内容：**单层** Tabs。
 *
 * <p>改造前：`输出 | 结果`（一级）+ `结果-1 | 结果-2`（二级）两层标签，白白多占一行。
 * 现在结果标签与「输出」同级、按实际执行结果按需出现，右侧竖条承接原本横铺一行的表格按钮。
 */
const TaskServiceTabs: React.FC<TaskServiceTabsProps> = (props) => {
  const { taskId, dialect, action, activeKey, onActiveKeyChange, extraItems } = props;
  const { entries, lastId, loading, remove, clearAll, refresh, updateColumnsState } =
    useResultEntries({ taskId, dialect, action });

  const handleClose = useCallback(
    (id: number) => {
      const index = entries.findIndex((entry) => entry.id === id);
      const neighbor = entries[index + 1] ?? entries[index - 1];
      remove(id);
      if (activeKey === `${RESULT_KEY_PREFIX}${id}`) {
        onActiveKeyChange(
          neighbor ? `${RESULT_KEY_PREFIX}${neighbor.id}` : DataStudioActionType.TASK_RUN_SUBMIT
        );
      }
    },
    [entries, activeKey, remove, onActiveKeyChange]
  );

  const items = useMemo<TabsProps['items']>(() => {
    const list: TabsProps['items'] = [
      {
        key: DataStudioActionType.TASK_RUN_SUBMIT,
        label: l('button.output'),
        icon: <CodeOutlined />,
        children: <Output taskId={taskId} />
      },
      ...entries.map((entry) => ({
        key: `${RESULT_KEY_PREFIX}${entry.id}`,
        label: <ResultTabLabel label={entry.label} onClose={() => handleClose(entry.id)} />,
        children: (
          <ResultTable
            entry={entry}
            loading={loading}
            onRefresh={refresh}
            onColumnsStateChange={updateColumnsState}
          />
        )
      }))
    ];
    // 没有任何结果时，保留一个「结果」占位标签，保持"点结果看数据"的既有路径
    if (entries.length === 0) {
      list.push({
        key: DataStudioActionType.TASK_PREVIEW_RESULT,
        label: l('menu.datastudio.result'),
        icon: <MonitorOutlined />,
        children: (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={l('pages.datastudio.label.result.empty')}
          />
        )
      });
    }
    return [...list, ...(extraItems ?? [])];
  }, [entries, loading, extraItems, taskId, handleClose, refresh, updateColumnsState]);

  const itemKeys = useMemo(() => new Set((items ?? []).map((item) => item.key)), [items]);
  const fallbackKey =
    entries.length > 0 ? `${RESULT_KEY_PREFIX}${lastId}` : DataStudioActionType.TASK_RUN_SUBMIT;

  // 活跃 key 兜底：预览动作把 key 指向「结果」占位键 → 落到最新结果；key 已消失 → 落到兜底键
  let resolvedActiveKey = activeKey ?? fallbackKey;
  if (
    resolvedActiveKey === DataStudioActionType.TASK_PREVIEW_RESULT &&
    entries.length > 0
  ) {
    resolvedActiveKey = `${RESULT_KEY_PREFIX}${lastId}`;
  }
  if (!itemKeys.has(resolvedActiveKey)) {
    resolvedActiveKey = fallbackKey;
  }

  return (
    <Tabs
      size={'small'}
      activeKey={resolvedActiveKey}
      items={items}
      onChange={onActiveKeyChange}
      style={{ height: '100%' }}
      tabBarStyle={{ marginBottom: '5px' }}
      tabBarExtraContent={
        entries.length > 0 ? (
          <Popconfirm
            title={l('pages.datastudio.label.result.clear.all.confirm')}
            onConfirm={() => {
              clearAll();
              // 清空后回落到「结果」占位标签，而不是跳到「输出」
              onActiveKeyChange(DataStudioActionType.TASK_PREVIEW_RESULT);
            }}
          >
            <Button
              size={'small'}
              type={'text'}
              danger
              icon={<ClearOutlined />}
              title={l('pages.datastudio.label.result.clear.all')}
            />
          </Popconfirm>
        ) : undefined
      }
    />
  );
};

export default TaskServiceTabs;
