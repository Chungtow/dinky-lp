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
import { isSql } from '@/pages/DataStudio/utils';
import { handleGetOption, handleGetOptionWithoutMsg } from '@/services/BusinessCrud';
import { API_CONSTANTS } from '@/services/endpoints';
import { l } from '@/utils/intl';
import { useAsyncEffect } from 'ahooks';
import { useCallback, useEffect, useRef, useState } from 'react';

/** 单张结果表（mock-sink 场景下 dataList 会有多项，每项一个表名） */
export type ResultData = {
  tableName?: string;
  columns: string[];
  rowData: any[];
};

export type ResultDataList = ResultData[];

/** 列显隐状态：列名 → { show } */
export type ResultColumnsState = Record<string, { show?: boolean }>;

/** 一次执行产生的一个结果集（对应界面上一个「结果-N」标签） */
export type ResultEntry = {
  id: number;
  label: string;
  dataList: ResultDataList;
  columnsState?: ResultColumnsState;
};

/** 结果集数量上限：超出后丢弃最旧的一条 */
export const MAX_RESULTS = 100;

/** 把 mock-sink 的结果拆成「一张表一项」 */
export const convertMockResultToList = (data: ResultData): ResultDataList => {
  const rowDataResults: ResultDataList = [];
  // 对于每个 MockResult 的 column，一个元素代表一个表信息
  data.columns.forEach((columnString: string) => {
    // 当前表的 column 信息
    let columnArr: string[] = [];
    // 当前表的 row data 信息
    const rowDataArr: any[] = [];
    // 表名
    let tableName: string = '';
    // 解析当前表单信息
    const columnJsonInfo = JSON.parse(columnString);
    if (columnJsonInfo['dinkySinkResultColumnIdentifier']) {
      columnArr = columnJsonInfo['dinkySinkResultColumnIdentifier'];
    }
    if (columnJsonInfo['dinkySinkResultTableIdentifier']) {
      tableName = columnJsonInfo['dinkySinkResultTableIdentifier'];
    }
    data.rowData.forEach((rowDataElement: any) => {
      if (rowDataElement.dinkySinkResultTableIdentifier == tableName) {
        rowDataArr.push(rowDataElement);
      }
    });
    rowDataResults.push({
      tableName: tableName,
      columns: columnArr,
      rowData: rowDataArr
    });
  });

  return rowDataResults;
};

/** 把 action 携带的原始结果转成 dataList */
export const toDataList = (data: ResultData, isMockSinkResult: boolean = false): ResultDataList => {
  if (isMockSinkResult) {
    return convertMockResultToList(data);
  }
  return [data];
};

export type UseResultEntriesProps = {
  taskId: number;
  dialect?: string;
  /** 历史抽屉预览时指定 jobId，跳过「取最近一次执行」 */
  historyId?: number;
  /**
   * redux action。
   * 历史抽屉里传的是字符串常量，此时 `actionType` / `params` 均为 undefined，
   * 不会触发 append，数据全部由 loadData() 提供 —— 与改造前行为一致。
   */
  action?: any;
};

/**
 * 结果集状态容器：追加 / 关闭 / 清空 / 重新拉取。
 *
 * <p>从 `Result` 组件里提升出来的唯一目的：让 `Service` 也能看到结果数量，
 * 从而把「结果-N」放进一级 Tabs（详见 docs/plan 20261104 服务面板空间优化）。
 */
export const useResultEntries = (props: UseResultEntriesProps) => {
  const { taskId, dialect, historyId, action } = props;

  const [entries, setEntries] = useState<ResultEntry[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const seqRef = useRef<number>(0);

  const actionType = action?.actionType;
  const actionParams = action?.params;

  const append = useCallback((newDataList: ResultDataList) => {
    seqRef.current += 1;
    const newId = seqRef.current;
    const newEntry: ResultEntry = {
      id: newId,
      label: `${l('pages.datastudio.label.result.tab')}-${newId}`,
      dataList: newDataList
    };
    setEntries((prev) => {
      const next = prev.length >= MAX_RESULTS ? prev.slice(1) : [...prev];
      return [...next, newEntry];
    });
  }, []);

  // SQL 方言：结果由 action 推送（编辑器里执行 / AI Chat 执行）
  useEffect(() => {
    if (actionType === DataStudioActionType.TASK_PREVIEW_RESULT && actionParams?.taskId === taskId) {
      append(
        toDataList(
          { columns: actionParams.columns, rowData: actionParams.rowData },
          actionParams.isMockSinkResult
        )
      );
    }
  }, [action]);

  const loadData = useCallback(async () => {
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
        { jobId: historyIdParam }
      );
      const data = tableData?.data;
      if (tableData?.success && data?.success) {
        append(toDataList(data, data.mockSinkResult));
      }
      return;
    }
    setLoading(false);
  }, [historyId, taskId, append]);

  // 非 SQL 方言：挂载时拉最近一次执行数据
  useAsyncEffect(async () => {
    if (!isSql(dialect)) {
      await loadData();
      setLoading(false);
    } else {
      setLoading(false);
    }
  }, []);

  /** 手动「获取最新数据」 */
  const refresh = useCallback(async () => {
    setLoading(true);
    await loadData();
    setLoading(false);
  }, [loadData]);

  const remove = useCallback((id: number) => {
    setEntries((prev) => prev.filter((entry) => entry.id !== id));
  }, []);

  const clearAll = useCallback(() => {
    setEntries([]);
    seqRef.current = 0;
  }, []);

  const updateColumnsState = useCallback((id: number, columnsState: ResultColumnsState) => {
    setEntries((prev) =>
      prev.map((entry) => (entry.id === id ? { ...entry, columnsState } : entry))
    );
  }, []);

  const lastId = entries.length > 0 ? entries[entries.length - 1].id : 0;

  return { entries, lastId, loading, append, remove, clearAll, refresh, updateColumnsState };
};
