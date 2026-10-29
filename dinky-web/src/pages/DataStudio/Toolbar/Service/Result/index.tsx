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

import { handleGetOption, handleGetOptionWithoutMsg } from '@/services/BusinessCrud';
import { API_CONSTANTS } from '@/services/endpoints';
import { transformTableDataToCsv } from '@/utils/function';
import { l } from '@/utils/intl';
import { DownloadOutlined, SearchOutlined, SyncOutlined } from '@ant-design/icons';
import { Highlight } from '@ant-design/pro-layout/es/components/Help/Search';
import { Button, Drawer, Empty, Input, InputRef, Space, Tabs, TabsProps } from 'antd';
import { ColumnType } from 'antd/es/table';
import { FilterConfirmProps } from 'antd/es/table/interface';
import { DataIndex } from 'rc-table/es/interface';
import React, { useCallback, useEffect, useRef, useState, useTransition } from 'react';
import { useAsyncEffect } from 'ahooks';
import { DataStudioActionType } from '@/pages/DataStudio/data.d';
import { isSql } from '@/pages/DataStudio/utils';
import { ProTable } from '@ant-design/pro-components';
import { getInsights } from '@antv/ava';
import { InsightCard } from '@antv/ava-react';
import type { Datum, InsightsResult } from '@antv/ava/lib/insight/types';
import { ProColumns } from '@ant-design/pro-table/es/typing';

type Data = {
  [c: string]: any;
  columns: string[];
  rowData: object[];
};
type DataList = Data[];

type ResultEntry = {
  id: number;
  label: string;
  dataList: DataList;
};

const MAX_RESULTS = 100;

export default (props: {
  taskId: number;
  historyId?: number | undefined;
  action: any;
  dialect: string;
}) => {
  const {
    taskId,
    historyId,
    action: { actionType, params },
    dialect
  } = props;

  const [results, setResults] = useState<ResultEntry[]>([]);
  const [activeResultKey, setActiveResultKey] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [openAVA, setOpenAVA] = useState<boolean>(false);
  const [avaResult, setAvaResult] = useState<InsightsResult>();
  const [isPending, startTransition] = useTransition();
  const [searchText, setSearchText] = useState('');
  const [searchedColumn, setSearchedColumn] = useState('');
  const searchInput = useRef<InputRef>(null);
  const seqRef = useRef<number>(0);

  const appendResult = useCallback((newDataList: DataList) => {
    seqRef.current += 1;
    const newId = seqRef.current;
    const newEntry: ResultEntry = {
      id: newId,
      label: `${l('pages.datastudio.label.result.tab')}-${newId}`,
      dataList: newDataList
    };
    setResults((prev) => {
      const next = prev.length >= MAX_RESULTS ? prev.slice(1) : [...prev];
      return [...next, newEntry];
    });
    setActiveResultKey(String(newId));
  }, []);

  useEffect(() => {
    if (actionType === DataStudioActionType.TASK_PREVIEW_RESULT && taskId === params.taskId) {
      appendResult(
        covertDataList(
          { columns: params.columns, rowData: params.rowData },
          params.isMockSinkResult
        )
      );
    }
  }, [props.action]);

  const handleReset = (clearFilters: () => void) => {
    clearFilters();
    setSearchText('');
  };
  const handleSearch = (
    selectedKeys: string[],
    confirm: (param?: FilterConfirmProps) => void,
    dataIndex: DataIndex
  ) => {
    confirm();
    if (selectedKeys.length > 0) {
      setSearchText(selectedKeys[0]);
      setSearchedColumn(dataIndex.toString());
    } else {
      setSearchText('');
      setSearchedColumn('');
    }
  };
  const covertDataList = (data: Data, isMockSinkResult: boolean = false) => {
    if (isMockSinkResult) {
      return convertMockResultToList(data);
    }
    return [data];
  };
  const convertMockResultToList = (data: Data): Data[] => {
    const rowDataResults: any[] = [];
    // 对于每个MockResult的Column，一个元素代表一个表信息
    data.columns.forEach((columnString: string) => {
      // 当前表的column信息
      let columnArr: string[] = [];
      // 当前表的row data信息
      const rowDataArr: string[] = [];
      // 表名
      let tableName: string = '';
      //解析当前表单信息
      const columnJsonInfo = JSON.parse(columnString);
      // 提取column信息
      if (columnJsonInfo['dinkySinkResultColumnIdentifier']) {
        columnArr = columnJsonInfo['dinkySinkResultColumnIdentifier'];
      }
      // 提取表名
      if (columnJsonInfo['dinkySinkResultTableIdentifier']) {
        tableName = columnJsonInfo['dinkySinkResultTableIdentifier'];
      }
      // 遍历column信息
      data.rowData.forEach((rowDataElement: any) => {
        if (rowDataElement.dinkySinkResultTableIdentifier == tableName) {
          rowDataArr.push(rowDataElement);
        }
      });
      // 构建constant对象
      const rowDataResult = {
        tableName: tableName,
        columns: columnArr,
        rowData: rowDataArr
      };
      rowDataResults.push(rowDataResult);
    });

    return rowDataResults;
  };
  const getColumnSearchProps = (dataIndex: string): ColumnType<Data> => ({
    filterDropdown: ({ setSelectedKeys, selectedKeys, confirm, clearFilters }) => (
      <div style={{ padding: 8 }} onKeyDown={(e) => e.stopPropagation()}>
        <Input
          ref={searchInput}
          placeholder={`Search ${dataIndex}`}
          value={selectedKeys[0]}
          onChange={(e) => setSelectedKeys(e.target.value ? [e.target.value] : [])}
          onPressEnter={() => handleSearch(selectedKeys as string[], confirm, dataIndex)}
          style={{ marginBottom: 8, display: 'block' }}
        />
        <Space>
          <Button
            type='primary'
            onClick={() => handleSearch(selectedKeys as string[], confirm, dataIndex)}
            icon={<SearchOutlined />}
            size='small'
            style={{ width: 90 }}
          >
            {l('button.search')}
          </Button>
          <Button
            onClick={() => clearFilters && handleReset(clearFilters)}
            size='small'
            style={{ width: 90 }}
          >
            {l('button.reset')}
          </Button>
        </Space>
      </div>
    ),
    filterIcon: (filtered: boolean) => (
      <SearchOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
    ),
    onFilter: (value, record) =>
      String(record[dataIndex] ?? '')
        .toLowerCase()
        .includes(String(value ?? '').toLowerCase()),
    onFilterDropdownOpenChange: (visible) => {
      if (visible) {
        setTimeout(() => searchInput.current?.select(), 100);
      }
    },
    render: (text) => {
      // 统一字符串化：React 对 JS 原生 boolean/null/undefined 渲染为空，需显式转字符串展示
      const displayText = text == null ? '' : String(text);
      return searchedColumn === dataIndex ? (
        <Highlight label={displayText} words={[searchText]} />
      ) : (
        displayText
      );
    }
  });

  const loadData = async () => {
    let historyIdParam = historyId;
    if (!historyIdParam) {
      const res = await handleGetOptionWithoutMsg(API_CONSTANTS.GET_LATEST_HISTORY_BY_ID, {
        id: taskId
      });
      historyIdParam = res?.data?.id;
    }
    if (historyIdParam) {
      const tableData = await handleGetOption(
        API_CONSTANTS.GET_JOB_DATA,
        l('global.getdata.tips'),
        {
          jobId: historyIdParam
        }
      );
      const data = tableData.data;
      if (tableData.success && data?.success) {
        appendResult(covertDataList(data, data.mockSinkResult));
      } else {
        setLoading(false);
        return;
      }
    }

    setLoading(false);
  };

  useAsyncEffect(async () => {
    if (!isSql(dialect)) {
      await loadData();
    } else {
      setLoading(false);
    }
  }, []);

  const getColumns = (columns: string[] = []) => {
    return columns?.map((item) => {
      return {
        title: item,
        dataIndex: item,
        sorter: (a, b) =>
          String(a[item] ?? '').localeCompare(String(b[item] ?? ''), undefined, {
            numeric: true
          }),
        ...getColumnSearchProps(item)
      };
    }) as ProColumns[];
  };

  const showDetail = async () => {
    setLoading(true);
    await loadData();
    setLoading(false);
  };

  const renderFlinkSQLContent = () => {
    return (
      <>
        {!isSql(dialect) ? (
          <Button loading={loading} type='primary' onClick={showDetail} icon={<SyncOutlined />}>
            {l('pages.datastudio.label.result.query.latest.data')}
          </Button>
        ) : undefined}
      </>
    );
  };
  const renderDownloadButton = (data: Data) => {
    if (data.columns) {
      const _utf = '\uFEFF';
      const csvDataBlob = new Blob([_utf + transformTableDataToCsv(data.columns!, data.rowData!)], {
        type: 'text/csv'
      });
      const url = URL.createObjectURL(csvDataBlob);
      return <Button type='link' href={url} icon={<DownloadOutlined />} title={'Export Csv'} />;
    }
    return undefined;
  };
  const renderAVA = (data: Data) => {
    return (
      <Button
        type='link'
        title={l('button.ava')}
        onClick={() => {
          setOpenAVA(true);
          startTransition(() => {
            setAvaResult(getInsights(data.rowData as Datum[], { visualization: true }));
          });
        }}
      >
        AVA
      </Button>
    );
  };

  // const renderTips = () => {
  //   return (
  //     <>
  //       {data.truncationFlag ? (
  //         <Tooltip
  //           placement='top'
  //           title={l('pages.datastudio.label.result.query.latest.data.truncate')}
  //         >
  //           <QuestionCircleOutlined />
  //         </Tooltip>
  //       ) : undefined}
  //     </>
  //   );
  // };
  const handleCloseAva = useCallback(() => setOpenAVA(false), []);

  const handleClearAll = useCallback(() => {
    setResults([]);
    seqRef.current = 0;
    setActiveResultKey('');
  }, []);

  const handleTabEdit = useCallback(
    (targetKey: React.MouseEvent | React.KeyboardEvent | string, action: 'add' | 'remove') => {
      if (action === 'remove' && typeof targetKey === 'string') {
        const targetId = Number(targetKey);
        setResults((prev) => prev.filter((r) => r.id !== targetId));
      }
    },
    []
  );

  const renderResultContent = (entry: ResultEntry) => {
    const { dataList } = entry;

    // 非 mock-sink 单表结果：直接渲染 ProTable
    if (dataList.length === 1 && !dataList[0].tableName) {
      const data = dataList[0];
      return (
        <ProTable
          className={'datastudio-theme'}
          cardBordered
          columns={getColumns(data.columns)}
          size='small'
          scroll={{ x: 'max-content' }}
          dataSource={data.rowData?.map((item: any, index: number) => {
            return { ...item, key: index };
          })}
          options={{ fullScreen: true, density: false }}
          search={false}
          loading={loading}
          toolBarRender={() => [renderDownloadButton(data), renderAVA(data)]}
          pagination={{
            showSizeChanger: true
          }}
        />
      );
    }

    // mock-sink 多表结果：内部 Tabs
    const innerItems: TabsProps['items'] = dataList.map((data, index) => {
      return {
        key: data.tableName ?? String(index),
        label: data.tableName,
        children: (
          <ProTable
            className={'datastudio-theme'}
            cardBordered
            columns={getColumns(data.columns)}
            size='small'
            scroll={{ x: 'max-content' }}
            dataSource={data.rowData?.map((item: any, idx: number) => {
              return { ...item, key: idx };
            })}
            options={{ fullScreen: true, density: false }}
            search={false}
            loading={loading}
            toolBarRender={() => [renderDownloadButton(data), renderAVA(data)]}
            pagination={{
              showSizeChanger: true
            }}
          />
        )
      };
    });
    return (
      <Tabs defaultActiveKey={innerItems[0]?.key} items={innerItems} tabBarStyle={{ marginBottom: '5px' }} />
    );
  };

  const outerTabItems: TabsProps['items'] = results.map((entry) => ({
    key: String(entry.id),
    label: entry.label,
    children: renderResultContent(entry)
  }));

  return (
    <div style={{ width: '100%' }}>
      {results.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <Tabs
          type='editable-card'
          hideAdd
          activeKey={activeResultKey}
          onChange={setActiveResultKey}
          onEdit={handleTabEdit}
          tabBarExtraContent={
            <Space>
              {renderFlinkSQLContent()}
              <Button size='small' onClick={handleClearAll} danger>
                {l('pages.datastudio.label.result.clear.all')}
              </Button>
            </Space>
          }
          items={outerTabItems}
          tabBarStyle={{ marginBottom: '5px' }}
        />
      )}
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
