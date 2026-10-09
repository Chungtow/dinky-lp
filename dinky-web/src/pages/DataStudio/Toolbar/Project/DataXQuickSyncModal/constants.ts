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

import { DataXVisualConfig } from '@/pages/DataStudio/CenterTabContent/SqlTask/DataXVisualEditor/dataxJson';

/** 任务参数（与作业配置「任务参数」完全一致：prop / direct / type / value） */
export interface QuickSyncParam {
  prop: string;
  direct: string;
  type: string;
  value: string;
}

/** 向导配置 = DataX 可视化配置 + 一键同步扩展项 */
export interface QuickSyncConfig extends DataXVisualConfig {
  /** 目标表置空（新建表）时的表名 */
  newTargetTableName?: string;
  /** 新建表时的存储格式（D5，默认 orc） */
  newFileType?: string;
  /** 目标作业方言（D7，由目标数据源 type 推导，默认 Hive） */
  dialect?: string;
}

/** 三个作业的命名前缀（D2，与人工 SOP 任务 27/28/29 保持一致） */
export const QUICK_SYNC_PREFIX = {
  createTable: 'create_table_',
  syncData: 'sync_data_',
  addPartition: 'add_partition_'
};

/** 默认分区字段名（可自定义，D4） */
export const DEFAULT_PARTITION_COLUMN = 'pt';

/** 存储格式（D5：默认 ORC；本集群 fork 版 hdfswriter 支持 PARQUET） */
export const DEFAULT_FILE_TYPE = 'orc';
export const FILE_TYPE_OPTIONS = [
  { label: 'ORC', value: 'orc' },
  { label: 'PARQUET', value: 'parquet' },
  { label: 'TEXT', value: 'text' }
];

export const PARAM_DIRECT_OPTIONS = [
  { label: 'IN', value: 'IN' },
  { label: 'OUT', value: 'OUT' }
];

export const PARAM_TYPE_OPTIONS = ['VARCHAR', 'INTEGER', 'LONG', 'DOUBLE', 'BOOLEAN'].map((v) => ({
  label: v,
  value: v
}));

/** 默认任务参数行：SYNC_TIME IN VARCHAR $[yyyyMMdd-1] */
export const DEFAULT_TASK_PARAM: QuickSyncParam = {
  prop: 'SYNC_TIME',
  direct: 'IN',
  type: 'VARCHAR',
  value: '$[yyyyMMdd-1]'
};

/** DataX 作业固定方言 */
export const DATAX_DIALECT = 'DataX';

/** 源侧一期只支持 MySQL（buildDataXJson 硬编码 mysqlreader） */
export const SUPPORTED_SOURCE_TYPE = 'mysql';

/**
 * 目标作业方言推导（D7）：默认 Hive，按目标数据源 type 推导。
 * 不支持的类型返回 undefined，由调用方禁用提交并提示。
 */
export function dialectFromDataSourceType(type?: string): string | undefined {
  switch ((type ?? '').trim().toLowerCase()) {
    case 'hive':
      return 'Hive';
    case 'spark':
      return 'SparkSql';
    default:
      return undefined;
  }
}

export const DEFAULT_CHANNEL = 3;
export const DEFAULT_WRITE_MODE = 'truncate';
export const DEFAULT_FIELD_DELIMITER = '\t';
