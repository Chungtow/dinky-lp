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

/**
 * DataX 可视化编辑器 —— 配置模型 与 datax json 生成/解析工具。
 *
 * 字段映射语义：DataX 的字段映射 = reader.column（源字段）与 writer.column（目标字段）
 * 的位置对应关系。本模块用 `mappings` 数组显式表达该对应关系，顺序即位置顺序。
 */

/** 字段定义（源字段 type 为 MySQL 类型，目标字段 type 为 Hive 类型） */
export interface DataXField {
  name: string;
  type: string;
  comment?: string;
}

/** 单侧（源/目标）配置 */
export interface DataXSide {
  dataSourceId?: number;
  dataSourceName?: string;
  /** 库名（MySQL 侧为 database，Hive 侧为 database/schema） */
  schemaName?: string;
  /** 表名（不含库名） */
  tableName?: string;
  fields: DataXField[];
  /** 表在 HDFS 上的完整路径（Hive 目标，来自 show create table 的 LOCATION，含 scheme） */
  location?: string;
  /** 分区字段列表（来自表 DDL 的 PARTITIONED BY） */
  partitionColumns?: DataXField[];
  /** 选中的分区字段名 */
  partitionColumn?: string;
  /** 分区值表达式（如 ${SYNC_DATE}-${SYNC_TIME}） */
  partitionExpression?: string;
}

/** 源侧额外连接信息（生成 json 用） */
export interface DataXSourceSide extends DataXSide {
  jdbcUrl?: string;
  username?: string;
  password?: string;
}

/** 字段映射关系 */
export interface DataXMapping {
  sourceField: string;
  targetField: string;
}

/** 可视化编辑器的完整配置 */
export interface DataXVisualConfig {
  source: DataXSourceSide;
  target: DataXSide;
  mappings: DataXMapping[];
  splitPk?: string;
  fileType?: string;
  writeMode?: string;
  fieldDelimiter?: string;
  defaultFS?: string;
  hdfsPath?: string;
  hadoopConfig?: Record<string, string>;
  channel?: number;
}

/** 默认 HDFS HA 配置（与 jobs 实例保持一致） */
export const DEFAULT_HADOOP_CONFIG: Record<string, string> = {
  'dfs.nameservices': 'mycluster',
  'dfs.ha.namenodes.mycluster': 'nn1,nn2',
  'dfs.namenode.rpc-address.mycluster.nn1': 'xingcheng01:8020',
  'dfs.namenode.rpc-address.mycluster.nn2': 'xingcheng02:8020',
  'dfs.client.failover.proxy.provider.mycluster':
    'org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider'
};

/**
 * 默认 HDFS 目标路径模板。
 * 例：/user/hive/warehouse/lpods.db/ods_gmall_order_info/pt=${SYNC_DATE}
 */
export function buildDefaultHdfsPath(schemaName: string, tableName: string): string {
  return `/user/hive/warehouse/${schemaName}.db/${tableName}/pt=\${SYNC_DATE}`;
}

/**
 * 由表 location + 分区字段 + 分区值表达式组装 hdfswriter 的 path。
 * 例：location=hdfs://mycluster/user/hive/warehouse/lpods.db/ods_gmall_user_info,
 *     partitionColumn=pt, expression=${SYNC_DATE}-${SYNC_TIME}
 *  → /user/hive/warehouse/lpods.db/ods_gmall_user_info/pt=${SYNC_DATE}-${SYNC_TIME}
 */
export function buildHdfsPath(
  location: string,
  defaultFS: string,
  partitionColumn?: string,
  partitionExpression?: string
): string {
  let path = location ?? '';
  if (defaultFS && path.startsWith(defaultFS)) {
    path = path.substring(defaultFS.length);
  } else {
    const scheme = path.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+/);
    if (scheme) {
      path = path.substring(scheme[0].length);
    }
  }
  if (partitionColumn) {
    path = `${path}/${partitionColumn}=${partitionExpression ?? ''}`;
  }
  return path;
}

/**
 * 从数据源 url 生成 datax reader 的 jdbcUrl（补齐常用参数）。
 */
export function buildJdbcUrl(url?: string): string {
  if (!url) {
    return '';
  }
  if (url.includes('?')) {
    return url;
  }
  return `${url}?useSSL=false&serverTimezone=Asia/Shanghai`;
}

/**
 * 根据可视化配置生成 datax json 字符串。
 *
 * 顶层附加 `dinkyMeta` 元数据块（与 job 平级）：DataX 引擎只读取 job.* 路径，
 * 未知顶层字段不影响执行。它保存可视化编辑所需的元信息（dinky 数据源 id、
 * 分区字段列表等），用于 JSON → 可视化回填时还原用户填写的完整配置。
 */
export function buildDataXJson(config: DataXVisualConfig): string {
  const { source, target, mappings } = config;

  const readerColumn = mappings.map((m) => m.sourceField);
  const writerColumn = mappings.map((m) => {
    const targetField = target.fields.find((f) => f.name === m.targetField);
    return { name: m.targetField, type: targetField?.type ?? 'STRING' };
  });

  const json = {
    dinkyMeta: {
      source: {
        dataSourceId: source.dataSourceId,
        dataSourceName: source.dataSourceName,
        schemaName: source.schemaName,
        tableName: source.tableName
      },
      target: {
        dataSourceId: target.dataSourceId,
        dataSourceName: target.dataSourceName,
        schemaName: target.schemaName,
        tableName: target.tableName,
        partitionColumns: target.partitionColumns ?? [],
        partitionColumn: target.partitionColumn,
        partitionExpression: target.partitionExpression
      }
    },
    job: {
      setting: {
        speed: {
          channel: config.channel ?? 3
        }
      },
      content: [
        {
          reader: {
            name: 'mysqlreader',
            parameter: {
              username: source.username ?? '',
              password: source.password ?? '',
              column: readerColumn,
              ...(config.splitPk ? { splitPk: config.splitPk } : {}),
              connection: [
                {
                  table: [source.tableName ?? ''],
                  jdbcUrl: [buildJdbcUrl(source.jdbcUrl)]
                }
              ]
            }
          },
          writer: {
            name: 'hdfswriter',
            parameter: {
              defaultFS: config.defaultFS ?? 'hdfs://mycluster',
              hadoopConfig: config.hadoopConfig ?? DEFAULT_HADOOP_CONFIG,
              fileType: config.fileType ?? 'orc',
              path:
                config.hdfsPath ??
                (target.location
                  ? buildHdfsPath(
                      target.location,
                      config.defaultFS ?? '',
                      target.partitionColumn,
                      target.partitionExpression
                    )
                  : buildDefaultHdfsPath(target.schemaName ?? '', target.tableName ?? '')),
              fileName: source.tableName ?? target.tableName ?? '',
              writeMode: config.writeMode ?? 'truncate',
              fieldDelimiter: config.fieldDelimiter ?? '\t',
              column: writerColumn
            }
          }
        }
      ]
    }
  };

  return JSON.stringify(json, null, 2);
}

/**
 * 从 datax json 字符串还原可视化配置（json → 可视化切换）。
 * 解析失败返回 null。
 */
export function parseDataXJson(jsonStr: string): DataXVisualConfig | null {
  if (!jsonStr) {
    return null;
  }
  try {
    const obj = JSON.parse(jsonStr);
    const content = obj?.job?.content?.[0];
    const readerParam = content?.reader?.parameter;
    const writerParam = content?.writer?.parameter;
    if (!readerParam || !writerParam) {
      return null;
    }

    const sourceColumns: string[] = readerParam.column ?? [];
    const targetColumns: { name: string; type: string }[] = writerParam.column ?? [];

    const mappings: DataXMapping[] = sourceColumns.map((sourceField, index) => ({
      sourceField,
      targetField: targetColumns[index]?.name ?? sourceField
    }));

    const tableName = readerParam.connection?.[0]?.table?.[0] ?? '';
    const jdbcUrl = readerParam.connection?.[0]?.jdbcUrl?.[0] ?? '';
    const fileName = writerParam.fileName ?? '';
    const hdfsPath = writerParam.path ?? '';
    const pathInfo = parseHdfsPathInfo(hdfsPath, writerParam.defaultFS ?? '');

    // dinkyMeta：由可视化「生成 JSON」时写入的元数据块，回填时优先使用；
    // 手写的 json 没有该块，走推导兜底。
    const metaSource = obj?.dinkyMeta?.source;
    const metaTarget = obj?.dinkyMeta?.target;
    const pathPartitionColumn: string = pathInfo.partitionColumn ?? '';

    return {
      source: {
        dataSourceId: metaSource?.dataSourceId,
        dataSourceName: metaSource?.dataSourceName,
        schemaName: metaSource?.schemaName || extractSchemaFromJdbcUrl(jdbcUrl),
        tableName: metaSource?.tableName || tableName,
        fields: sourceColumns.map((name) => ({ name, type: '' })),
        jdbcUrl,
        username: readerParam.username ?? '',
        password: readerParam.password ?? ''
      },
      target: {
        dataSourceId: metaTarget?.dataSourceId,
        dataSourceName: metaTarget?.dataSourceName,
        schemaName: metaTarget?.schemaName || extractSchemaFromHdfsPath(hdfsPath),
        // 目标表名：优先 dinkyMeta；兜底从 hdfs path 的 warehouse/{db}.db/{table} 提取。
        // 不能用 fileName（它是文件名前缀，通常等于源表名，与目标表无关）。
        tableName: metaTarget?.tableName || extractTableFromHdfsPath(hdfsPath) || fileName,
        fields: targetColumns.map((c) => ({ name: c.name, type: c.type })),
        location: pathInfo.location,
        // 分区字段列表只存在于 dinkyMeta（它来自表 DDL）；path 只能反推单个分区字段
        partitionColumns: metaTarget?.partitionColumns?.length
          ? metaTarget.partitionColumns
          : pathPartitionColumn
            ? [{ name: pathPartitionColumn, type: 'string' }]
            : [],
        // 分区字段/表达式优先从 path 反推（path 是 DataX 实际执行的真相，手动改过 path 也能同步）
        partitionColumn: pathPartitionColumn || metaTarget?.partitionColumn,
        partitionExpression: pathInfo.partitionExpression || metaTarget?.partitionExpression
      },
      mappings,
      splitPk: readerParam.splitPk,
      fileType: writerParam.fileType,
      writeMode: writerParam.writeMode,
      fieldDelimiter: writerParam.fieldDelimiter,
      defaultFS: writerParam.defaultFS,
      hdfsPath,
      hadoopConfig: writerParam.hadoopConfig,
      channel: obj?.job?.setting?.speed?.channel
    };
  } catch (e) {
    return null;
  }
}

/** 从 jdbc url 提取库名（jdbc:mysql://host:port/gmall?x=y → gmall） */
export function extractSchemaFromJdbcUrl(jdbcUrl: string): string {
  if (!jdbcUrl) {
    return '';
  }
  const match = jdbcUrl.match(/\/([^/?]+)(\?|$)/);
  return match?.[1] ?? '';
}

/** 从 hdfs path 提取库名（/warehouse/lpods.db/xxx → lpods） */
export function extractSchemaFromHdfsPath(hdfsPath: string): string {
  if (!hdfsPath) {
    return '';
  }
  const match = hdfsPath.match(/warehouse\/([^/]+)\.db/);
  return match?.[1] ?? '';
}

/** 从 hdfs path 提取 Hive 表名（/user/hive/warehouse/lpods.db/ods_x/pt=1 → ods_x） */
export function extractTableFromHdfsPath(hdfsPath: string): string {
  if (!hdfsPath) {
    return '';
  }
  // 先去掉末尾的分区段（/pt=xxx）
  const withoutPartition = hdfsPath.replace(/\/[^/=]+=[^/]+$/, '');
  const match = withoutPartition.match(/\.db\/([^/]+)/);
  return match?.[1] ?? '';
}

/** 从 hdfs path 反推 location + 分区字段 + 分区值表达式 */
export function parseHdfsPathInfo(
  hdfsPath: string,
  defaultFS: string
): { location: string; partitionColumn: string; partitionExpression: string } {
  let location = hdfsPath;
  let partitionColumn = '';
  let partitionExpression = '';
  const match = hdfsPath.match(/^(.*)\/([^/=]+)=([^/]+)$/);
  if (match) {
    location = match[1];
    partitionColumn = match[2];
    partitionExpression = match[3];
  }
  const fullLocation = defaultFS ? `${defaultFS}${location}` : location;
  return { location: fullLocation, partitionColumn, partitionExpression };
}
