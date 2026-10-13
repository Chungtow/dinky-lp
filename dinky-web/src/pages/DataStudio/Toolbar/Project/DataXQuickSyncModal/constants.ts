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
  addPartition: 'add_partition_',
  analyzeTable: 'analyze_table_'
};

/** 默认分区字段名（可自定义，D4） */
export const DEFAULT_PARTITION_COLUMN = 'pt';

/**
 * 默认分区值表达式：引用下方默认任务参数 SYNC_TIME。
 * 显式预填（不再靠渲染时兜底），让用户打开弹窗就看到「分区值从哪来」，避免凭空猜。
 */
export const DEFAULT_PARTITION_EXPRESSION = '${SYNC_TIME}';

/**
 * 参数名示例文案。
 * ⚠️ 必须在提示文案里用常量引用：若在模板字符串里直接写 ${SYNC_DATE}，
 * JS 会把它当插值表达式求值 → 运行时 ReferenceError → 整个弹窗组件崩溃。
 */
export const PARAM_NAME_SAMPLE = '${SYNC_DATE}';

/**
 * 「参数值」列的时间表达式建议（DolphinScheduler $[...] 语法）。
 * ⚠️ 只允许出现在任务参数的「值」里，写进分区值表达式会被 validate 拦住。
 */
export const TIME_EXPR_OPTIONS = [
  '$[yyyyMMdd-1]',
  '$[yyyyMMdd]',
  '$[yyyy-MM-dd]',
  '$[HHmmss]',
  '$[yyyyMMddHHmmss]'
].map((v) => ({ label: v, value: v }));

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
 *
 * ⚠️ MySQL 的方言字符串是 `Mysql`（`Dialect.MYSQL("Mysql")`，注意大小写），
 * 反向同步（Hive/Spark → MySQL）时用它给建表作业定型。
 */
export function dialectFromDataSourceType(type?: string): string | undefined {
  switch ((type ?? '').trim().toLowerCase()) {
    case 'hive':
      return 'Hive';
    case 'spark':
      return 'SparkSql';
    case 'mysql':
      return 'Mysql';
    default:
      return undefined;
  }
}

export const DEFAULT_CHANNEL = 3;
export const DEFAULT_WRITE_MODE = 'truncate';
export const DEFAULT_FIELD_DELIMITER = '\t';

/* ------------------------- 反向同步（阶段一）新增 ------------------------- */

/** 同步方向 */
export const DIRECTION_OPTIONS = [
  { label: 'MySQL → Hive/Spark', value: 'mysql2hive' },
  { label: 'Hive/Spark → MySQL', value: 'hive2mysql' }
];

/**
 * 反向写入模式。
 * ⚠️ PoC 实测（本集群 DATAX-OPENSOURCE-3.0）：三种模式都可用，且 `update`
 * 语义为 **upsert**（`INSERT ... ON DUPLICATE KEY UPDATE`，存在则更新、不存在则插入），
 * 需配 `updateKey`（目标表主键/唯一索引列）。
 */
export const DEFAULT_REVERSE_WRITE_MODE = 'update';
export const REVERSE_WRITE_MODE_OPTIONS = [
  { label: 'update（upsert：存在则更新，不存在则插入）', value: 'update' },
  { label: 'replace（REPLACE INTO，DELETE+INSERT）', value: 'replace' },
  { label: 'insert（仅插入，需先清空目标分区）', value: 'insert' }
];

/* ================= 阶段二：目的地画像 + 步骤种类 + 目标表名前缀 ================= */

/** 作业步骤种类（节点命名 = `${kind}_${表名}`，见 QUICK_SYNC_PREFIX） */
export type StepKind =
  | 'create_table'
  | 'ensure_partition'
  | 'sync_data'
  | 'analyze_stats'
  | 'dedup'
  | 'verify';

/**
 * 目的地画像：决定「要生成哪些作业」。
 * 把这些能力抽成数据，未来接入 Doris / Kafka / ES 时只需新增一份画像 + 增量 StepKind，
 * 不必改编排主流程（计划 §4.8）。
 */
export interface DestinationProfile {
  kind: 'hive' | 'mysql';
  label: string;
  /** 是否需要生成建表作业 */
  needsCreateTable: boolean;
  /**
   * 是否要求「数据目录预先存在」。
   * Hive 的 DataX HdfsWriter **不会自建目录**，缺失即报 HdfsWriter-02，
   * 因此分区表必须先建出 pt=xxx 目录（= 加分区作业）。MySQL 无此约束。
   */
  requiresPreCreatedPath: boolean;
  /**
   * 写入后是否需要刷新统计。
   * Hive 不刷 ANALYZE 时，HS2 默认 `hive.compute.query.using.stats=true`，
   * 下游 `count(*)` 会直接读统计返回 0（实测踩过）。MySQL 无此约束。
   */
  requiresStatsRefresh: boolean;
  /** 是否支持 upsert（需业务主键 / updateKey） */
  supportsUpsert: boolean;
}

export const HIVE_PROFILE: DestinationProfile = {
  kind: 'hive',
  label: 'Hive/Spark',
  needsCreateTable: true,
  requiresPreCreatedPath: true,
  requiresStatsRefresh: true,
  supportsUpsert: false
};

export const MYSQL_PROFILE: DestinationProfile = {
  kind: 'mysql',
  label: 'MySQL',
  needsCreateTable: true,
  requiresPreCreatedPath: false,
  requiresStatsRefresh: false,
  supportsUpsert: true
};

/**
 * 目的地数据源类型 → 画像。
 * `isPartitioned=false` 时 Hive 不需要预建分区目录（非分区表无 add_partition 步骤）。
 * 不支持的类型返回 undefined，由调用方禁用提交并提示。
 */
export function profileFromDestinationType(
  type?: string,
  isPartitioned = true
): DestinationProfile | undefined {
  switch ((type ?? '').trim().toLowerCase()) {
    case 'hive':
    case 'spark':
      return { ...HIVE_PROFILE, requiresPreCreatedPath: isPartitioned };
    case 'mysql':
      return MYSQL_PROFILE;
    default:
      return undefined;
  }
}

/** 源侧数据源类型是否可作为「反向」的源（Hive/Spark） */
export function isHiveLikeType(type?: string): boolean {
  const t = (type ?? '').trim().toLowerCase();
  return t === 'hive' || t === 'spark';
}

/**
 * 目标表名自动前缀（计划 §3.7，2026-10-12 需求确认）。
 *
 * 规则：取右键所在**目录名** → 转小写 → 移除非 `[a-z0-9_]` 字符 → 取**前 3 位** → 为空则回退 `ods`。
 * 例：`ODS` → `ods`；`ODS_LAYER` → `ods`；`数仓ODS` →（去中文后 `ods`）→ `ods`；`APP` → `app`。
 *
 * ⚠️ 该函数必须**静默**（不弹提示）：必要时由 UI 用「问号图标 + Tooltip」说明来源。
 */
export function buildTableNamePrefix(catalogueName?: string): string {
  const cleaned = (catalogueName ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '');
  const prefix = cleaned.slice(0, 3);
  return prefix || 'ods';
}

/** 目标表名默认值：`<目录名小写前3位>_<源库>_<源表>` */
export function buildDefaultTargetTableName(
  catalogueName: string | undefined,
  sourceSchema?: string,
  sourceTable?: string
): string {
  return `${buildTableNamePrefix(catalogueName)}_${sourceSchema ?? ''}_${sourceTable ?? ''}`;
}

/**
 * 按「目的地画像」生成步骤清单（计划 §4.8）。
 *
 * - Hive/Spark 目的地：与既有 6 项**完全一致**（子目录 → 建表 → 执行建表 → 加分区 → 同步 → 统计）；
 * - MySQL 目的地：无「分区目录预建」「统计刷新」需求，只保留
 *   子目录 → 建表 → 执行建表 → 同步。
 */
export const buildQuickSyncSteps = (
  profile: DestinationProfile
): { key: string; label: string }[] => [
  { key: 'subFolder', label: '创建 / 复用子目录' },
  { key: 'createTableJob', label: '创建建表作业' },
  { key: 'execCreateTable', label: '执行建表' },
  ...(profile.requiresPreCreatedPath
    ? [{ key: 'partitionJob', label: '创建加分区作业（建出分区目录）' }]
    : []),
  { key: 'syncJob', label: '创建同步作业（DataX）' },
  ...(profile.requiresStatsRefresh
    ? [{ key: 'analyzeJob', label: '创建统计作业（ANALYZE）' }]
    : [])
];
