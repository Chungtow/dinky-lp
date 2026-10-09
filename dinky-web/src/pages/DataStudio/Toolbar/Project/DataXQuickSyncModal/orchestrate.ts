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

import { showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import { addOrUpdateData, getData, postAll, removeById } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { DataSources } from '@/types/RegCenter/data';
import { QUICK_SYNC_PREFIX, QuickSyncParam } from './constants';

export type StepStatus = 'pending' | 'running' | 'success' | 'error' | 'skipped';

export interface StepState {
  key: string;
  label: string;
  status: StepStatus;
  detail?: string;
}

export const QUICK_SYNC_STEPS: { key: string; label: string }[] = [
  { key: 'subFolder', label: '创建 / 复用子目录' },
  { key: 'createTableJob', label: '创建建表作业' },
  { key: 'execCreateTable', label: '执行建表' },
  { key: 'syncJob', label: '创建同步作业（DataX）' },
  { key: 'partitionJob', label: '创建加分区作业' }
];

export interface CatalogueNode {
  id: number;
  name?: string;
  isLeaf?: boolean;
  taskId?: number;
  children?: CatalogueNode[];
}

export interface TableDetail {
  location?: string;
  fileType?: string;
  partitionColumns?: DataSources.Column[];
  columns?: DataSources.Column[];
}

export interface QuickSyncCreatedItem {
  key: string;
  label: string;
  name: string;
  catalogueId?: number;
  taskId?: number;
}

export interface QuickSyncResult {
  subFolder?: { id: number; name: string; reused: boolean };
  created: QuickSyncCreatedItem[];
  executedCreateTable: boolean;
  skipped: string[];
  residual: { label: string; id?: number }[];
  steps: StepState[];
}

export interface QuickSyncRunInput {
  /** 右键所在目录 id */
  parentId: number;
  /** 子目录名（默认目标表名，D1） */
  subDirName: string;
  /** 目标作业方言（D7） */
  dialect: string;
  /** 目标表名（用于建表/加分区，以及取表详情） */
  targetTableName: string;
  /** 三个作业最终名称（UI 已处理重名后缀） */
  names: { createTable: string; syncData: string; addPartition: string };
  /** 重名作业选择「覆盖更新」时，传入既有 catalogue id 走更新路径 */
  overwriteIds?: { createTable?: number; syncData?: number; addPartition?: number };
  /** 目标数据源 id（Hive 类作业必填，否则执行时报 please assign data source） */
  targetDataSourceId?: number;
  targetSchemaName: string;
  taskParams: QuickSyncParam[];
  firstLevelOwner?: number;
  createTableDdl: string;
  /** 目标表已存在时是否仍需创建建表作业（是，保留 IF NOT EXISTS 语义） */
  targetTableExists: boolean;
  /** 是否执行建表（D3，且仅在目标表不存在时有意义） */
  execCreateTable: boolean;
  /** 是否有分区（目标表存在但无分区时不建加分区作业） */
  hasPartition: boolean;
  /** 生成加分区 SQL（需在拿到分区字段后调用） */
  buildAddPartitionSql: () => string;
  /** 生成 DataX json（建表后重取的目标表详情会回传，闭包持有最新配置） */
  buildSyncStatement: (detail?: TableDetail) => string;
  onStep?: (steps: StepState[]) => void;
}

/** 后端失败响应经 umi errorThrower 抛出，错误信息可能在 info.data.error（JobResult）或 info.msg */
const errorText = (error: any): string =>
  error?.info?.data?.error || error?.info?.msg || error?.message || '未知错误';

const findNode = (nodes: CatalogueNode[] = [], id: number): CatalogueNode | undefined => {
  for (const node of nodes) {
    if (node?.id === id) {
      return node;
    }
    const hit = findNode(node?.children ?? [], id);
    if (hit) {
      return hit;
    }
  }
  return undefined;
};

export const fetchCatalogueTree = async (): Promise<CatalogueNode[]> => {
  const res = await postAll(API_CONSTANTS.CATALOGUE_GET_CATALOGUE_TREE_DATA, {});
  return (res?.data ?? []) as CatalogueNode[];
};

/**
 * 子目录：已存在则复用 id，未命中才创建（D1）。
 * 不依赖后端 NAME_IS_EXIST 报错兜底——先查树再建，天然支持重跑。
 */
export const ensureSubFolder = async (
  parentId: number,
  name: string
): Promise<{ id: number; reused: boolean }> => {
  const tree = await fetchCatalogueTree();
  const parent = findNode(tree, parentId);
  const exist = parent?.children?.find((c) => c.name === name && !c.isLeaf);
  if (exist?.id) {
    return { id: exist.id, reused: true };
  }
  const res = await addOrUpdateData(API_CONSTANTS.SAVE_OR_UPDATE_CATALOGUE_URL, {
    name,
    parentId,
    isLeaf: false
  });
  const id = res?.data?.id;
  if (!id) {
    throw new Error('创建子目录失败：后端未返回目录 id');
  }
  return { id, reused: false };
};

export interface CreateTaskInput {
  /** 传入既有 catalogue id 时为更新（重名覆盖） */
  id?: number;
  name: string;
  dialect: string;
  parentId: number;
  statement: string;
  taskParams: QuickSyncParam[];
  /** Hive 类作业必填，否则执行时报 please assign data source */
  databaseId?: number;
  firstLevelOwner?: number;
  note?: string;
}

/** 创建作业（目录 + 任务联动），返回目录 id 与任务 id */
export const createTaskUnderFolder = async (
  input: CreateTaskInput
): Promise<{ catalogueId?: number; taskId?: number }> => {
  const res = await addOrUpdateData(API_CONSTANTS.SAVE_OR_UPDATE_TASK_URL, {
    ...(input.id ? { id: input.id } : {}),
    name: input.name,
    type: input.dialect,
    parentId: input.parentId,
    configJson: { taskParams: input.taskParams },
    ...(input.firstLevelOwner ? { firstLevelOwner: input.firstLevelOwner } : {}),
    ...(input.note ? { note: input.note } : {}),
    task: {
      statement: input.statement,
      ...(input.databaseId ? { databaseId: input.databaseId } : {}),
      enabled: true
    }
  });
  const catalogueId = res?.data?.id;
  const taskId = res?.data?.taskId;
  if (!catalogueId) {
    throw new Error('创建作业失败：后端未返回作业 id');
  }
  return { catalogueId, taskId };
};

/** 执行一次作业（同步）：成功返回，失败抛错并带出 JobResult.error */
export const executeTaskOnce = async (taskId: number, statement?: string): Promise<void> => {
  const res = await getData(API_CONSTANTS.SUBMIT_TASK, { id: taskId, statement });
  const jobResult = res?.data;
  if (!jobResult || jobResult.success !== true) {
    throw new Error(jobResult?.error || res?.msg || '执行失败');
  }
};

/** 删除目录（同时删除关联作业），失败返回 false */
export const deleteCatalogueSafe = async (id?: number): Promise<boolean> => {
  if (!id) {
    return false;
  }
  try {
    await removeById(API_CONSTANTS.DELETE_CATALOGUE_BY_ID_URL, { id });
    return true;
  } catch (e) {
    return false;
  }
};

/** 目标表是否存在：用库表树的表列表判断（不要用 getTableDetail 探测） */
export const tableExists = async (
  dataSourceId: number,
  schemaName: string,
  tableName: string
): Promise<boolean> => {
  if (!dataSourceId || !schemaName || !tableName) {
    return false;
  }
  const schemas = (await showDataSourceTable(dataSourceId)) ?? [];
  const schema = schemas.find((s: any) => s.name === schemaName);
  return (schema?.tables ?? []).some((t: any) => t.name === tableName);
};

export const fetchTableDetail = async (
  dataSourceId: number,
  schemaName: string,
  tableName: string
): Promise<TableDetail | undefined> => {
  if (!dataSourceId || !schemaName || !tableName) {
    return undefined;
  }
  const res = await getData(API_CONSTANTS.DATASOURCE_GET_TABLE_DETAIL, {
    id: dataSourceId,
    schemaName,
    tableName
  });
  return res?.data as TableDetail | undefined;
};

export interface HadoopConfigResp {
  defaultFS?: string;
  hadoopConfig?: Record<string, string>;
}

export const fetchHadoopConfig = async (): Promise<HadoopConfigResp | undefined> => {
  const res = await getData(API_CONSTANTS.DATASOURCE_HADOOP_CONFIG);
  return res?.data as HadoopConfigResp | undefined;
};

/**
 * 一键同步主流程（Step 0 之前的前置校验由 UI 完成）：
 * 子目录 → 建表作业 →（条件）执行建表 → DataX 作业 → 加分区作业。
 * 任一步失败：已创建对象逆序补偿删除；补偿失败的对象进入 residual 由用户手工处理。
 */
export const runQuickSync = async (input: QuickSyncRunInput): Promise<QuickSyncResult> => {
  const steps: StepState[] = QUICK_SYNC_STEPS.map((s) => ({ ...s, status: 'pending' as StepStatus }));
  const created: QuickSyncCreatedItem[] = [];
  const residual: { label: string; id?: number }[] = [];
  const skipped: string[] = [];
  let executedCreateTable = false;

  const patch = (key: string, status: StepStatus, detail?: string) => {
    const idx = steps.findIndex((s) => s.key === key);
    if (idx >= 0) {
      steps[idx] = { ...steps[idx], status, detail };
      input.onStep?.([...steps]);
    }
  };

  let subFolder: { id: number; reused: boolean } | undefined;

  const compensate = async () => {
    for (let i = created.length - 1; i >= 0; i--) {
      const item = created[i];
      const ok = await deleteCatalogueSafe(item.catalogueId);
      if (!ok) {
        residual.push({ label: `${item.label}（${item.name}）`, id: item.catalogueId });
      }
    }
    created.length = 0;
    if (subFolder && !subFolder.reused) {
      const ok = await deleteCatalogueSafe(subFolder.id);
      if (!ok) {
        residual.push({ label: `子目录（${input.subDirName}）`, id: subFolder.id });
      }
    }
  };

  try {
    // Step 1: 子目录
    patch('subFolder', 'running');
    subFolder = await ensureSubFolder(input.parentId, input.subDirName);
    patch('subFolder', 'success', subFolder.reused ? `复用已有目录 #${subFolder.id}` : `#${subFolder.id}`);

    // Step 3: 建表作业
    patch('createTableJob', 'running');
    const createTable = await createTaskUnderFolder({
      id: input.overwriteIds?.createTable,
      name: input.names.createTable,
      dialect: input.dialect,
      parentId: subFolder.id,
      statement: input.createTableDdl,
      taskParams: input.taskParams,
      databaseId: input.targetDataSourceId,
      firstLevelOwner: input.firstLevelOwner,
      note: '由「一键 DataX 同步」生成'
    });
    created.push({
      key: 'createTableJob',
      label: '建表作业',
      name: input.names.createTable,
      catalogueId: createTable.catalogueId,
      taskId: createTable.taskId
    });
    patch('createTableJob', 'success', `作业 id ${createTable.taskId}`);

    // Step 4: 条件执行建表（仅当目标表不存在 && D3 勾选）
    if (!input.targetTableExists && input.execCreateTable) {
      patch('execCreateTable', 'running');
      // 不传 statement：作业里已保存同一份 DDL，避免超长 SQL 走 GET query 被截断
      await executeTaskOnce(createTable.taskId!);
      executedCreateTable = true;
      patch('execCreateTable', 'success', '目标表已创建');
    } else {
      patch('execCreateTable', 'skipped', input.targetTableExists ? '目标表已存在' : '未勾选立即执行建表');
      skipped.push('执行建表');
    }

    // Step 5 前置：建表后重取目标表详情，拿到真实 location / fileType / 分区列
    const detail = await fetchTableDetail(
      input.targetDataSourceId!,
      input.targetSchemaName,
      input.targetTableName
    );

    // Step 5: DataX 同步作业
    patch('syncJob', 'running');
    const syncStatement = input.buildSyncStatement(detail);
    const syncJob = await createTaskUnderFolder({
      id: input.overwriteIds?.syncData,
      name: input.names.syncData,
      dialect: 'DataX',
      parentId: subFolder.id,
      statement: syncStatement,
      taskParams: input.taskParams,
      firstLevelOwner: input.firstLevelOwner,
      note: '由「一键 DataX 同步」生成'
    });
    created.push({
      key: 'syncJob',
      label: '同步作业',
      name: input.names.syncData,
      catalogueId: syncJob.catalogueId,
      taskId: syncJob.taskId
    });
    patch('syncJob', 'success', `作业 id ${syncJob.taskId}`);

    // Step 6: 加分区作业（目标表无分区列时不创建）
    const hasPartition = (detail?.partitionColumns?.length ?? 0) > 0 || input.hasPartition;
    if (hasPartition) {
      patch('partitionJob', 'running');
      const partitionJob = await createTaskUnderFolder({
        id: input.overwriteIds?.addPartition,
        name: input.names.addPartition,
        dialect: input.dialect,
        parentId: subFolder.id,
        statement: input.buildAddPartitionSql(),
        taskParams: input.taskParams,
        databaseId: input.targetDataSourceId,
        firstLevelOwner: input.firstLevelOwner,
        note: '由「一键 DataX 同步」生成'
      });
      created.push({
        key: 'partitionJob',
        label: '加分区作业',
        name: input.names.addPartition,
        catalogueId: partitionJob.catalogueId,
        taskId: partitionJob.taskId
      });
      patch('partitionJob', 'success', `作业 id ${partitionJob.taskId}`);
    } else {
      patch('partitionJob', 'skipped', '目标表无分区字段');
      skipped.push('加分区作业（目标表无分区字段）');
    }

    return {
      subFolder: { id: subFolder.id, name: input.subDirName, reused: subFolder.reused },
      created: [...created],
      executedCreateTable,
      skipped,
      residual,
      steps: [...steps]
    };
  } catch (error: any) {
    const msg = errorText(error);
    const failedKey = steps.find((s) => s.status === 'running')?.key;
    if (failedKey) {
      patch(failedKey, 'error', msg);
    }
    steps
      .filter((s) => s.status === 'pending')
      .forEach((s) => patch(s.key, 'skipped', '前序步骤失败，未执行'));
    await compensate();
    return {
      subFolder: subFolder ? { id: subFolder.id, name: input.subDirName, reused: subFolder.reused } : undefined,
      created: [],
      executedCreateTable,
      skipped,
      residual,
      steps: [...steps]
    };
  }
};
