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
import { Button, Empty, Tabs, TabsProps } from 'antd';
import React, { useState } from 'react';
import ResultTable from './ResultTable';
import { useResultEntries } from './useResultEntries';

/**
 * 独立形态的结果面板：自持状态、自带一层「结果-N」标签。
 *
 * <p>使用方：执行历史详情抽屉（`ExecutionHistory/.../PreViewData.tsx`）。
 * 数据由 `historyId` 指定的 jobId 拉取，不参与一级 Tabs（那是 `Service` 里 `TaskServiceTabs` 的职责）。
 */
export default (props: {
  taskId: number;
  historyId?: number | undefined;
  action: any;
  dialect: string;
}) => {
  const { taskId, historyId, action, dialect } = props;
  const { entries, loading, refresh, remove, clearAll, updateColumnsState } = useResultEntries({
    taskId,
    dialect,
    historyId,
    action
  });
  const [activeKey, setActiveKey] = useState<string>('');

  if (entries.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  const items: TabsProps['items'] = entries.map((entry) => ({
    key: String(entry.id),
    label: entry.label,
    children: (
      <ResultTable
        entry={entry}
        loading={loading}
        onRefresh={refresh}
        onColumnsStateChange={updateColumnsState}
      />
    )
  }));

  // 活跃 key 兜底：指向已被关闭的结果时退到最后一个
  const resolvedActiveKey = items.some((item) => item.key === activeKey)
    ? activeKey
    : items[items.length - 1].key;

  return (
    <div style={{ width: '100%' }}>
      <Tabs
        type='editable-card'
        hideAdd
        size={'small'}
        activeKey={resolvedActiveKey}
        onChange={setActiveKey}
        onEdit={(targetKey, action) => {
          if (action === 'remove' && typeof targetKey === 'string') {
            remove(Number(targetKey));
          }
        }}
        tabBarExtraContent={
          <Button size='small' danger onClick={clearAll}>
            {l('pages.datastudio.label.result.clear.all')}
          </Button>
        }
        items={items}
        tabBarStyle={{ marginBottom: '5px' }}
      />
    </div>
  );
};
