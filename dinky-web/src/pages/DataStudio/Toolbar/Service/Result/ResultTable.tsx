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

import { transformTableDataToCsv } from '@/utils/function';
import { ProTable } from '@ant-design/pro-components';
import { getInsights } from '@antv/ava';
import { InsightCard } from '@antv/ava-react';
import type { Datum, InsightsResult } from '@antv/ava/lib/insight/types';
import { Drawer, Tabs, TabsProps } from 'antd';
import React, { useCallback, useEffect, useMemo, useState, useTransition } from 'react';
import { useResultColumns } from './columns';
import ResultToolRail from './ResultToolRail';
import { ResultColumnsState, ResultData, ResultEntry } from './useResultEntries';

type ResultTableProps = {
  entry: ResultEntry;
  loading?: boolean;
  onRefresh: () => void;
  onColumnsStateChange: (id: number, columnsState: ResultColumnsState) => void;
};

const tableKeyOf = (data: ResultData, index: number) => data.tableName ?? String(index);

/**
 * 单个结果集的渲染：右侧竖条 + 数据表格。
 *
 * <p>mock-sink 场景 `dataList` 会有多项（每项一张表），此时内层按表名分 Tabs，
 * 竖条的导出 / AVA / 列设置作用于**当前选中的表**（与改造前每表各自工具栏的语义一致）。
 */
const ResultTable: React.FC<ResultTableProps> = (props) => {
  const { entry, loading, onRefresh, onColumnsStateChange } = props;
  const { dataList, columnsState } = entry;
  const { getColumns } = useResultColumns();

  const [innerKey, setInnerKey] = useState<string>(() =>
    dataList.length > 0 ? tableKeyOf(dataList[0], 0) : ''
  );
  const [fullscreen, setFullscreen] = useState<boolean>(false);
  const [openAVA, setOpenAVA] = useState<boolean>(false);
  const [avaResult, setAvaResult] = useState<InsightsResult>();
  const [isPending, startTransition] = useTransition();

  // 非 mock-sink 的单表结果：直接渲染，不做内层 Tabs
  const isSingle = dataList.length <= 1 && !dataList[0]?.tableName;

  const activeIndex = useMemo(() => {
    const index = dataList.findIndex((data, idx) => tableKeyOf(data, idx) === innerKey);
    return index >= 0 ? index : 0;
  }, [dataList, innerKey]);
  const activeData = dataList[activeIndex] ?? dataList[0];

  // CSV 只在数据变化时生成一次，避免每次渲染都新建 Blob
  const csvUrl = useMemo(() => {
    if (!activeData?.columns?.length) {
      return undefined;
    }
    const _utf = '\uFEFF';
    const csvDataBlob = new Blob(
      [_utf + transformTableDataToCsv(activeData.columns, activeData.rowData ?? [])],
      { type: 'text/csv' }
    );
    return URL.createObjectURL(csvDataBlob);
  }, [activeData]);

  useEffect(() => {
    return () => {
      if (csvUrl) {
        URL.revokeObjectURL(csvUrl);
      }
    };
  }, [csvUrl]);

  // 全屏下 Esc 退出
  useEffect(() => {
    if (!fullscreen) {
      return;
    }
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setFullscreen(false);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [fullscreen]);

  const handleAVA = useCallback((data: ResultData) => {
    setOpenAVA(true);
    startTransition(() => {
      setAvaResult(getInsights((data?.rowData ?? []) as Datum[], { visualization: true }));
    });
  }, []);

  const handleCloseAva = useCallback(() => setOpenAVA(false), []);

  const renderTable = (data: ResultData) => (
    <ProTable
      className={'datastudio-theme'}
      cardBordered
      columns={getColumns(data?.columns)}
      size='small'
      scroll={{ x: 'max-content' }}
      dataSource={(data?.rowData ?? []).map((item: any, index: number) => ({ ...item, key: index }))}
      options={false}
      toolBarRender={false}
      cardProps={{ bodyStyle: { padding: '6px 8px' } }}
      columnsState={{
        value: columnsState,
        onChange: (map) => onColumnsStateChange(entry.id, map as ResultColumnsState)
      }}
      search={false}
      loading={loading}
      pagination={{ showSizeChanger: true, defaultPageSize: 50, size: 'small', hideOnSinglePage: true }}
    />
  );

  const innerItems: TabsProps['items'] = dataList.map((data, index) => ({
    key: tableKeyOf(data, index),
    label: data.tableName,
    children: renderTable(data)
  }));

  const containerStyle: React.CSSProperties = fullscreen
    ? {
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        padding: 8,
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--main-background-color)'
      }
    : { height: '100%', display: 'flex', flexDirection: 'column' };

  return (
    <div style={containerStyle}>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'row' }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {isSingle ? (
            renderTable(activeData)
          ) : (
            <Tabs
              size={'small'}
              activeKey={tableKeyOf(activeData, activeIndex)}
              items={innerItems}
              onChange={setInnerKey}
              tabBarStyle={{ marginBottom: '5px' }}
            />
          )}
        </div>
        <div style={{ width: 32, flex: 'none' }}>
          <ResultToolRail
            loading={loading}
            csvUrl={csvUrl}
            columns={activeData?.columns ?? []}
            columnsState={columnsState}
            fullscreen={fullscreen}
            onRefresh={onRefresh}
            onAVA={() => handleAVA(activeData)}
            onToggleFullscreen={() => setFullscreen((prev) => !prev)}
            onColumnsStateChange={(state) => onColumnsStateChange(entry.id, state)}
          />
        </div>
      </div>
      <Drawer
        open={openAVA}
        loading={isPending}
        width={'70%'}
        onClose={handleCloseAva}
        destroyOnClose
      >
        <div key='plot' style={{ flex: 5, height: '100%' }}>
          {avaResult?.insights &&
            avaResult.insights.map((insight, index) => {
              return (
                <InsightCard
                  insightInfo={insight}
                  key={index}
                  visualizationOptions={{ lang: 'zh-CN' }}
                />
              );
            })}
        </div>
      </Drawer>
    </div>
  );
};

export default ResultTable;
