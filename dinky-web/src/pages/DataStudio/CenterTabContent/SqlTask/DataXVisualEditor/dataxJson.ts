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

export interface DataXField {
  name: string;
  type: string;
  comment?: string;
}

/**
 * 同步方向。
 * - `mysql2hive`（默认，向后兼容）：reader=`mysqlreader`，writer=`hdfswriter`
 * - `hive2mysql`（反向）：reader=`hdfsreader`，writer=`mysqlwriter`
 */
export type DataXDirection = 'mysql2hive' | 'hive2mysql';

/** 单侧（源/目标）配置 */
export interface DataXSide {
  dataSourceId?: number;
  dataSourceName?: string;
  /** 库名（MySQL 侧为 database，Hive 侧为 database/schema） */
  schemaName?: string;
  /** 表名（不含库名） */
  tableName?: string;
  fields: DataXField[];
  /** 表在 HDFS 上的完整路径（Hive 侧，来自 show create table 的 LOCATION，含 scheme） */
  location?: string;
  /** 分区字段列表（来自表 DDL 的 PARTITIONED BY） */
  partitionColumns?: DataXField[];
  /** 选中的分区字段名 */
  partitionColumn?: string;
  /** 分区值表达式（如 ${SYNC_DATE}-${SYNC_TIME}） */
  partitionExpression?: string;
  /** JDBC 连接串（MySQL 侧；正向=源，反向=目标） */
  jdbcUrl?: string;
  /** 数据库账号（MySQL 侧；正向=源，反向=目标） */
  username?: string;
  /** 数据库密码（MySQL 侧；正向=源，反向=目标） */
  password?: string;
}

/** @deprecated 连接信息已上移到 {@link DataXSide}；保留别名以兼容既有引用 */
export type DataXSourceSide = DataXSide;

/**
 * 字段映射关系。
 * DataX 的字段映射 = reader.column（源字段）与 writer.column（目标字段）的位置对应关系，
 * 本模块用 `mappings` 数组显式表达该对应关系，顺序即位置顺序。
 */
export interface DataXMapping {
  sourceField: string;
  targetField: string;
}

/** 可视化编辑器的完整配置 */
export interface DataXVisualConfig {
  /** 同步方向；缺省视为 `mysql2hive`（向后兼容既有 JSON） */
  direction?: DataXDirection;
  source: DataXSide;
  target: DataXSide;
  mappings: DataXMapping[];
  splitPk?: string;
  fileType?: string;
  writeMode?: string;
  /** 反向 upsert（`writeMode=update`）的业务主键列；须为目标表主键或唯一索引 */
  updateKey?: string[];
  /** 反向：`mysqlwriter` 的 session 语句（可选，如 sql_mode） */
  session?: string[];
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
 * 默认 HDFS 目标路径模板（不含分区段，分区段由调用方按分区字段+表达式拼接）。
 * 例：/user/hive/warehouse/lpods.db/ods_gmall_order_info
 */
export function buildDefaultHdfsPath(schemaName: string, tableName: string): string {
  return `/user/hive/warehouse/${schemaName}.db/${tableName}`;
}

/**
 * location 是否是**有效的表路径**：排除空串，以及只有 scheme 的残缺值（如 `hdfs://mycluster`）。
 * 后者常见于「回填旧 JSON」——旧 path 退化时解析出的 location 只剩 scheme，若当成有效值
 * 参与拼路径（会被剥掉 scheme），会再次生成丢表目录的 path（如 /pt=xxx）。
 */
export function isValidTableLocation(location?: string): boolean {
  const loc = (location ?? '').trim();
  if (!loc) {
    return false;
  }
  const path = loc.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+/, '');
  return path.length > 1;
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
/** parquet 基础类型映射（对齐本集群 fork 版 hdfswriter 的列类型映射规则） */
const PARQUET_BASE_TYPE: Record<string, string> = {
  TINYINT: 'int32',
  SMALLINT: 'int32',
  INT: 'int32',
  INTEGER: 'int32',
  BIGINT: 'int64',
  LONG: 'int64',
  FLOAT: 'float',
  DOUBLE: 'double',
  BOOLEAN: 'boolean',
  STRING: 'binary',
  VARCHAR: 'binary',
  CHAR: 'binary',
  BINARY: 'binary',
  DATE: 'int32',
  TIMESTAMP: 'int96'
};

/** parquet 逻辑类型（把 binary 还原成 STRING、int32 还原成 DATE 语义） */
const PARQUET_LOGICAL_TYPE: Record<string, string> = {
  STRING: 'UTF8',
  VARCHAR: 'UTF8',
  CHAR: 'UTF8',
  DATE: 'DATE'
};

/**
 * 生成 hdfswriter 的 `parquetSchema`（parquet MessageType 语法）。
 *
 * ⚠️ parquet 模式下**必须显式提供**：fork 版 hdfswriter 的
 * `HdfsHelper.parquetFileStartWrite` 直接 `config.getString("parquetSchema")` 再交给
 * `MessageTypeParser.parseMessageType`，没有任何回退——插件里那个"从 column 自动生成
 * schema"的 `unitizeParquetConfig` 是死代码（全插件无调用点）。缺省会直接报
 * `Code:[HdfsWriter-10] Parse parquet schema error - NullPointerException`。
 *
 * 实测（本集群 datax 插件）：TIMESTAMP→`int96` 时 Hive 能正确读成 timestamp，
 * STRING→`binary (UTF8)` 能正确读成 string。
 */
export function buildParquetSchema(columns: { name: string; type?: string }[]): string {
  const lines = ['message m {'];
  columns.forEach((c) => {
    const key = (c.type ?? '').trim().toUpperCase();
    const base = PARQUET_BASE_TYPE[key] ?? 'binary';
    const logical = PARQUET_LOGICAL_TYPE[key];
    lines.push(`  optional ${base} ${c.name}${logical ? ` (${logical})` : ''};`);
  });
  lines.push('}');
  return lines.join('\n');
}

/**
 * Hive 类型 → DataX `hdfsreader.column[].type`。
 *
 * ⚠️ PoC 实测：反向读 HDFS 时 `column` **必须是 `{index, type}` 对象数组**——
 * 纯字符串数组报 `HdfsReader-05`（[type] 必填），只给 `type` 不给 `index` 报
 * `HdfsReader-06`（至少需要 index 或 value）。这与 `mysqlreader` 的
 * `["col1","col2"]` 完全不同。
 */
export function mapToDataxType(hiveType?: string): string {
  const t = (hiveType ?? '').trim().toLowerCase().replace(/\(.*\)/, '');
  switch (t) {
    case 'tinyint':
    case 'smallint':
    case 'int':
    case 'integer':
    case 'bigint':
      return 'LONG';
    case 'float':
    case 'double':
    case 'decimal':
    case 'numeric':
      return 'DOUBLE';
    case 'boolean':
      return 'BOOLEAN';
    case 'date':
    case 'timestamp':
      return 'DATE';
    case 'string':
    case 'varchar':
    case 'char':
      return 'STRING';
    default:
      // 复杂类型（array/map/struct）与未知类型：按 STRING 声明，
      // 需在上游（去重 SQL）CAST 成字符串，否则读取结果不可控。
      return 'STRING';
  }
}

/**
 * 反向 reader 的 column：`[{index, type}]`。
 * `index` 取**源表真实列位置**（hdfsreader 按文件列位置读取），
 * 因此映射了部分列时，index 仍指向正确的列。
 */
export function buildHdfsReaderColumn(
  sourceFields: DataXField[],
  mappedFieldNames: string[]
): { index: number; type: string }[] {
  return mappedFieldNames.map((name) => {
    const idx = sourceFields.findIndex((f) => f.name === name);
    const field = idx >= 0 ? sourceFields[idx] : undefined;
    return { index: idx >= 0 ? idx : 0, type: mapToDataxType(field?.type) };
  });
}

export function buildDataXJson(config: DataXVisualConfig): string {
  const { source, target, mappings } = config;
  /** 同步方向：缺省 mysql2hive（向后兼容既有 JSON） */
  const direction: DataXDirection = config.direction ?? 'mysql2hive';
  const isReverse = direction === 'hive2mysql';

  const readerColumn = mappings.map((m) => m.sourceField);
  const writerColumn = mappings.map((m) => {
    const targetField = target.fields.find((f) => f.name === m.targetField);
    return { name: m.targetField, type: targetField?.type ?? 'STRING' };
  });
  const fileType = config.fileType ?? 'orc';
  /** 反向写入模式：默认 upsert（update），见计划 §4.3 */
  const reverseWriteMode = config.writeMode ?? 'update';
  /** 反向 reader.path：与正向 writer.path 对称，指向源（或去重）表的分区目录 */
  const reverseReaderPath =
    config.hdfsPath ||
    (source.location
      ? buildHdfsPath(
          source.location,
          config.defaultFS ?? '',
          source.partitionColumn,
          source.partitionExpression
        )
      : buildHdfsPath(
          buildDefaultHdfsPath(source.schemaName ?? '', source.tableName ?? ''),
          '',
          source.partitionColumn,
          source.partitionExpression
        ));

  const json = {
    dinkyMeta: {
      /**
       * ⚠️ 方向与两侧字段必须写入 dinkyMeta：
       * 反向的 `hdfsreader.column` 只有 `{index,type}`、**不含列名**，
       * JSON → 可视化回填时只能靠这里还原列名与类型。
       */
      direction,
      source: {
        dataSourceId: source.dataSourceId,
        dataSourceName: source.dataSourceName,
        schemaName: source.schemaName,
        tableName: source.tableName,
        fields: source.fields ?? [],
        location: source.location,
        partitionColumns: source.partitionColumns ?? [],
        partitionColumn: source.partitionColumn,
        partitionExpression: source.partitionExpression
      },
      target: {
        dataSourceId: target.dataSourceId,
        dataSourceName: target.dataSourceName,
        schemaName: target.schemaName,
        tableName: target.tableName,
        fields: target.fields ?? [],
        partitionColumns: target.partitionColumns ?? [],
        partitionColumn: target.partitionColumn,
        partitionExpression: target.partitionExpression,
        updateKey: config.updateKey ?? [],
        writeMode: reverseWriteMode
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
          reader: isReverse
            ? {
                name: 'hdfsreader',
                parameter: {
                  defaultFS: config.defaultFS ?? 'hdfs://mycluster',
                  hadoopConfig: config.hadoopConfig ?? DEFAULT_HADOOP_CONFIG,
                  path: reverseReaderPath,
                  fileType,
                  encoding: 'UTF-8',
                  // 必须是 {index,type}：hdfsreader 不接受字符串数组（PoC 实测）
                  column: buildHdfsReaderColumn(source.fields, readerColumn)
                }
              }
            : {
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
          writer: isReverse
            ? {
                name: 'mysqlwriter',
                parameter: {
                  // PoC 实测 insert/replace/update 均可用；默认 update（upsert：存在则更新、不存在则插入）
                  writeMode: reverseWriteMode,
                  ...(reverseWriteMode === 'update' ? { updateKey: config.updateKey ?? [] } : {}),
                  ...(config.session?.length ? { session: config.session } : {}),
                  column: writerColumn.map((c) => c.name),
                  connection: [
                    {
                      jdbcUrl: buildJdbcUrl(target.jdbcUrl),
                      table: [target.tableName ?? '']
                    }
                  ],
                  username: target.username ?? '',
                  password: target.password ?? ''
                }
              }
            : {
            name: 'hdfswriter',
            parameter: {
              defaultFS: config.defaultFS ?? 'hdfs://mycluster',
              hadoopConfig: config.hadoopConfig ?? DEFAULT_HADOOP_CONFIG,
              fileType,
              // parquet 必须带 parquetSchema，否则写端直接 NPE（详见 buildParquetSchema 注释）
              ...(fileType.trim().toLowerCase() === 'parquet'
                ? { parquetSchema: buildParquetSchema(writerColumn) }
                : {}),
              path:
                config.hdfsPath ||
                (isValidTableLocation(target.location)
                  ? buildHdfsPath(
                      target.location as string,
                      config.defaultFS ?? '',
                      target.partitionColumn,
                      target.partitionExpression
                    )
                  : buildHdfsPath(
                      buildDefaultHdfsPath(target.schemaName ?? '', target.tableName ?? ''),
                      '',
                      target.partitionColumn,
                      target.partitionExpression
                    )),
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
 * 反向（hive2mysql）JSON → 可视化配置。
 *
 * reader=`hdfsreader`（Hive 源）、writer=`mysqlwriter`（MySQL 目标）。
 * ⚠️ `hdfsreader.column` 只有 `{index,type}`、**不含列名**，所以列名/类型
 * 只能从 `dinkyMeta.{source,target}.fields` 还原；手写 JSON（无 dinkyMeta）
 * 时退化为按 index 生成占位列名，用户需在界面上重新选择源表。
 */
function parseReverseDataXJson(obj: any, readerParam: any, writerParam: any): DataXVisualConfig {
  const metaSource = obj?.dinkyMeta?.source;
  const metaTarget = obj?.dinkyMeta?.target;

  const readerCols: any[] = readerParam.column ?? [];
  const writerCols: any[] = writerParam.column ?? [];
  const writerNames: string[] = writerCols.map((c) => (typeof c === 'string' ? c : (c?.name ?? '')));

  const metaSourceFields: DataXField[] = metaSource?.fields ?? [];
  const metaTargetFields: DataXField[] = metaTarget?.fields ?? [];

  const sourceFields: DataXField[] = metaSourceFields.length
    ? metaSourceFields
    : readerCols.map((c, i) => ({ name: `col_${c?.index ?? i}`, type: '' }));

  const targetFields: DataXField[] = writerNames.map((n) => {
    const f = metaTargetFields.find((x) => x.name === n);
    return { name: n, type: f?.type ?? 'STRING' };
  });

  // 不按旧 JSON 的 writer 列数截断源字段：源表加列后（如新增 stat_date），
  // 截断会让新增列在「重新生成」时丢失。
  const mappings: DataXMapping[] = sourceFields.map((f, i) => ({
    sourceField: f.name,
    targetField: writerNames[i] ?? f.name
  }));

  const hdfsPath: string = readerParam.path ?? '';
  const defaultFS: string = readerParam.defaultFS ?? '';
  const pathInfo = parseHdfsPathInfo(hdfsPath, defaultFS);
  const connection = writerParam.connection?.[0] ?? {};

  return {
    direction: 'hive2mysql',
    source: {
      dataSourceId: metaSource?.dataSourceId,
      dataSourceName: metaSource?.dataSourceName,
      schemaName: metaSource?.schemaName || extractSchemaFromHdfsPath(hdfsPath),
      tableName: metaSource?.tableName || extractTableFromHdfsPath(hdfsPath),
      fields: sourceFields,
      location: metaSource?.location || pathInfo.location,
      partitionColumns: metaSource?.partitionColumns ?? [],
      partitionColumn: pathInfo.partitionColumn || metaSource?.partitionColumn,
      partitionExpression: pathInfo.partitionExpression || metaSource?.partitionExpression
    },
    target: {
      dataSourceId: metaTarget?.dataSourceId,
      dataSourceName: metaTarget?.dataSourceName,
      schemaName: metaTarget?.schemaName || extractSchemaFromJdbcUrl(connection.jdbcUrl ?? ''),
      tableName: metaTarget?.tableName || connection.table?.[0] || '',
      fields: targetFields,
      jdbcUrl: connection.jdbcUrl ?? '',
      username: writerParam.username ?? '',
      password: writerParam.password ?? ''
    },
    mappings,
    fileType: readerParam.fileType ?? 'orc',
    writeMode: writerParam.writeMode ?? 'update',
    updateKey: writerParam.updateKey ?? metaTarget?.updateKey ?? [],
    session: writerParam.session,
    defaultFS,
    // ⚠️ 不要把回填的 path 写回 config.hdfsPath：生成时它的优先级最高，会把旧 path
    // 原样带出（旧 path 一旦退化就会一直退化下去），location / 分区字段 / 分区表达式
    // 已从 path 解析出来，重新生成时可正确重建路径。
    hdfsPath: undefined,
    hadoopConfig: readerParam.hadoopConfig,
    channel: obj?.job?.setting?.speed?.channel
  };
}

/**
 * 从 datax json 字符串还原可视化配置（json → 可视化切换）。
 * 解析失败返回 null。正/反向按 reader/writer 名或 dinkyMeta.direction 自动判定。
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

    // 方向判定：优先 dinkyMeta（可视化生成时写入），否则按 reader/writer 名推断
    const readerName: string = content?.reader?.name ?? '';
    const writerName: string = content?.writer?.name ?? '';
    const direction: DataXDirection =
      obj?.dinkyMeta?.direction ??
      (readerName === 'hdfsreader' || writerName === 'mysqlwriter' ? 'hive2mysql' : 'mysql2hive');
    if (direction === 'hive2mysql') {
      return parseReverseDataXJson(obj, readerParam, writerParam);
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
      direction,
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
      // 同上：不把回填的旧 path 当成「用户自定义路径」，避免生成时短路路径推导
      hdfsPath: undefined,
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
