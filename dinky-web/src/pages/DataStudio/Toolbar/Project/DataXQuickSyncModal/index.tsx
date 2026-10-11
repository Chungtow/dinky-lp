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

import FieldMapping from '@/pages/DataStudio/CenterTabContent/SqlTask/DataXVisualEditor/FieldMapping';
import {
  buildDataXJson,
  buildDefaultHdfsPath,
  buildHdfsPath,
  DataXField,
  DataXMapping
} from '@/pages/DataStudio/CenterTabContent/SqlTask/DataXVisualEditor/dataxJson';
import { showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import { queryDataByParams } from '@/services/BusinessCrud';
import { API_CONSTANTS } from '@/services/endpoints';
import { DataSources } from '@/types/RegCenter/data';
import { l } from '@/utils/intl';
import { PlusOutlined } from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  Alert,
  AutoComplete,
  Button,
  Checkbox,
  Col,
  Divider,
  Empty,
  Flex,
  Input,
  message,
  Modal,
  Row,
  Select,
  Space,
  Steps,
  Table,
  Typography
} from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_CHANNEL,
  DEFAULT_FIELD_DELIMITER,
  DEFAULT_FILE_TYPE,
  DEFAULT_PARTITION_COLUMN,
  DEFAULT_PARTITION_EXPRESSION,
  DEFAULT_TASK_PARAM,
  DEFAULT_WRITE_MODE,
  dialectFromDataSourceType,
  FILE_TYPE_OPTIONS,
  PARAM_DIRECT_OPTIONS,
  PARAM_NAME_SAMPLE,
  PARAM_TYPE_OPTIONS,
  QuickSyncConfig,
  QuickSyncParam,
  QUICK_SYNC_PREFIX,
  TIME_EXPR_OPTIONS
} from './constants';
import {
  CatalogueNode,
  fetchCatalogueTree,
  QuickSyncResult,
  runQuickSync,
  StepState,
  TableDetail
} from './orchestrate';
import {
  buildAddPartitionSql,
  buildAnalyzeSql,
  buildCreateTableDdl,
  extractInvalidParamRefs,
  validateParamRefs
} from './statements';
import { isUnsupportedHiveType, mapToHiveType } from './typeMapping';

const { Text, Paragraph } = Typography;

interface SchemaNode {
  name: string;
  tables: { name: string }[];
}

const HIVE_TYPE_OPTIONS = [
  'STRING',
  'BIGINT',
  'INT',
  'SMALLINT',
  'TINYINT',
  'DOUBLE',
  'FLOAT',
  'BOOLEAN',
  'TIMESTAMP',
  'DATE'
].map((v) => ({ label: v, value: v }));

const emptyConfig = (): QuickSyncConfig => ({
  source: { fields: [] },
  target: {
    fields: [],
    partitionColumn: DEFAULT_PARTITION_COLUMN,
    // 预填默认表达式：用户打开就能看到分区值从哪个参数来，而不是留在空白里靠代码兜底
    partitionExpression: DEFAULT_PARTITION_EXPRESSION
  },
  mappings: [],
  newFileType: DEFAULT_FILE_TYPE,
  dialect: 'Hive'
});

/** 单侧「新增字段」小表单（源侧填 MySQL 类型，目标侧选 Hive 类型） */
const FieldAdder = (props: {
  side: 'source' | 'target';
  onAdd: (side: 'source' | 'target', name: string, type: string) => void;
}) => {
  const { side, onAdd } = props;
  const [name, setName] = useState('');
  const [type, setType] = useState(side === 'target' ? 'STRING' : 'string');
  return (
    <Space size={4} style={{ width: '100%' }}>
      <Input
        size='small'
        style={{ flex: '1 1 0' }}
        placeholder='字段名'
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      {side === 'target' ? (
        <Select
          size='small'
          style={{ width: 110 }}
          value={type}
          options={HIVE_TYPE_OPTIONS}
          onChange={setType}
        />
      ) : (
        <Input
          size='small'
          style={{ width: 90 }}
          placeholder='类型'
          value={type}
          onChange={(e) => setType(e.target.value)}
        />
      )}
      <Button
        size='small'
        icon={<PlusOutlined />}
        onClick={() => {
          onAdd(side, name, type);
          setName('');
        }}
      >
        添加
      </Button>
    </Space>
  );
};

/**
 * 默认字段映射：按【同名字段】配对。
 * ⚠️ 不能用下标顺序配对：目标表已存在时两侧列的顺序/集合可能不同，
 * 按下标配对会静默产生错误的列映射（数据写进错误的列）。
 */
const buildDefaultMappings = (sourceFields: DataXField[], targetFields: DataXField[]): DataXMapping[] => {
  const targetNames = new Set(targetFields.map((f) => f.name));
  return sourceFields
    .filter((f) => targetNames.has(f.name))
    .map((f) => ({ sourceField: f.name, targetField: f.name }));
};

const stepStatusOf = (status: StepState['status']): 'wait' | 'process' | 'finish' | 'error' => {
  switch (status) {
    case 'success':
      return 'finish';
    case 'running':
      return 'process';
    case 'error':
      return 'error';
    default:
      return 'wait';
  }
};

export interface DataXQuickSyncModalProps {
  visible: boolean;
  /** 右键所在目录 id */
  parentId: number;
  parentName?: string;
  onCancel: () => void;
  /** 执行成功后回调（刷新目录树） */
  onSuccess?: () => void;
}

const DataXQuickSyncModal = (props: DataXQuickSyncModalProps) => {
  const { visible, parentId, parentName, onCancel, onSuccess } = props;
  const { initialState } = useModel('@@initialState');
  const currentUserId = initialState?.currentUser?.user?.id;

  const [config, setConfig] = useState<QuickSyncConfig>(emptyConfig());
  const [schemas, setSchemas] = useState<{ source: SchemaNode[]; target: SchemaNode[] }>({
    source: [],
    target: []
  });
  const [dataSourceList, setDataSourceList] = useState<DataSources.DataSource[]>([]);
  const [hadoopConf, setHadoopConf] = useState<{ defaultFS?: string; hadoopConfig?: any }>({});
  const [taskParams, setTaskParams] = useState<QuickSyncParam[]>([{ ...DEFAULT_TASK_PARAM }]);
  const [execCreateTable, setExecCreateTable] = useState<boolean>(true);
  const [targetTableExists, setTargetTableExists] = useState<boolean>(false);
  const [running, setRunning] = useState<boolean>(false);
  const [steps, setSteps] = useState<StepState[]>([]);
  const [result, setResult] = useState<QuickSyncResult | undefined>();
  const [previewOpen, setPreviewOpen] = useState<boolean>(false);
  const [conflict, setConflict] = useState<{ names: string[] } | undefined>();

  // 供编排回调读取最新配置（避免闭包拿到旧状态）
  const configRef = useRef(config);
  /** 上一次自动生成的「新建表名」，用于区分“用户手填”与“系统默认填充” */
  const autoTargetNameRef = useRef('');
  useEffect(() => {
    configRef.current = config;
  }, [config]);

  const mysqlSources = useMemo(
    () => dataSourceList.filter((d) => (d.type ?? '').toLowerCase() === 'mysql'),
    [dataSourceList]
  );
  const targetSources = useMemo(
    () => dataSourceList.filter((d) => !!dialectFromDataSourceType(d.type)),
    [dataSourceList]
  );

  const sourceTables = schemas.source.find((s) => s.name === config.source.schemaName)?.tables ?? [];
  const targetTables = schemas.target.find((s) => s.name === config.target.schemaName)?.tables ?? [];

  const targetTableName = config.target.tableName || config.newTargetTableName || '';
  const effectiveFileType = (config.target.tableName
    ? config.fileType
    : config.newFileType || DEFAULT_FILE_TYPE) as string;
  const partitionColumn = config.target.partitionColumn || DEFAULT_PARTITION_COLUMN;
  /** 实际生效的分区值表达式（留空按默认值处理，与预览/执行保持一致） */
  const partitionExpression = config.target.partitionExpression || DEFAULT_PARTITION_EXPRESSION;
  /** 表达式引用了、但任务参数表里没有定义的参数名（行内提示 + 一键补齐用） */
  const missingParams = useMemo(
    () => validateParamRefs([partitionExpression], taskParams),
    [partitionExpression, taskParams]
  );
  /** 表达式里形如 ${X} 但 X 不是合法参数名（含 - 等字符）的引用 */
  const invalidParamRefs = useMemo(() => extractInvalidParamRefs(partitionExpression), [partitionExpression]);

  /**
   * 关闭弹窗即重置全部状态。
   * 历史问题：Modal 只是 visible 切换、组件不卸载，上次填的内容（表达式/参数）会残留到下次打开，
   * 用户以为自己下过线或刷新过，其实还在。
   */
  useEffect(() => {
    if (!visible) {
      setConfig(emptyConfig());
      setTaskParams([{ ...DEFAULT_TASK_PARAM }]);
      setResult(undefined);
      setSteps([]);
      setConflict(undefined);
      setPreviewOpen(false);
      setExecCreateTable(true);
      setTargetTableExists(false);
    }
  }, [visible]);

  useEffect(() => {
    if (!visible) {
      return;
    }
    const init = async () => {
      const list = (await queryDataByParams(API_CONSTANTS.DATASOURCE_LIST_ENABLE_ALL)) as
        | DataSources.DataSource[]
        | undefined;
      setDataSourceList(list ?? []);
      const conf = (await queryDataByParams(API_CONSTANTS.DATASOURCE_HADOOP_CONFIG)) as any;
      setHadoopConf({ defaultFS: conf?.defaultFS, hadoopConfig: conf?.hadoopConfig });
      setConfig((prev) => ({
        ...prev,
        defaultFS: conf?.defaultFS ?? prev.defaultFS,
        hadoopConfig: conf?.hadoopConfig ?? prev.hadoopConfig
      }));
    };
    init().then();
  }, [visible]);

  const loadSchemas = async (side: 'source' | 'target', dataSourceId: number) => {
    const res = await showDataSourceTable(dataSourceId);
    const nodes: SchemaNode[] = (res ?? []).map((s: any) => ({
      name: s.name,
      tables: (s.tables ?? []).map((t: any) => ({ name: t.name }))
    }));
    setSchemas((prev) => ({ ...prev, [side]: nodes }));
    return nodes;
  };

  const handleDataSourceChange = (side: 'source' | 'target', dataSourceId: number) => {
    const ds = dataSourceList.find((d) => d.id === dataSourceId);
    setConfig((prev) => {
      const nextSide: any =
        side === 'source'
          ? {
              ...prev.source,
              dataSourceId,
              dataSourceName: ds?.name,
              jdbcUrl: ds?.connectConfig?.url,
              username: ds?.connectConfig?.username,
              password: ds?.connectConfig?.password
            }
          : { ...prev.target, dataSourceId, dataSourceName: ds?.name };
      const next: QuickSyncConfig = { ...prev, [side]: nextSide, mappings: [] };
      if (side === 'target') {
        next.dialect = dialectFromDataSourceType(ds?.type) ?? prev.dialect;
      }
      // 切换数据源后两侧字段若仍在（典型顺序：先选源表、再选目标数据源），
      // 必须按同名字段重建映射；否则 mappings 恒为空，预览/执行会被
      // 「请至少配置一条字段映射」卡死，用户只能手工拉几十条连线。
      next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
      return next;
    });
    setSchemas((prev) => ({ ...prev, [side]: [] }));
    loadSchemas(side, dataSourceId).then();
  };

  const handleSourceTableChange = async (tableName: string) => {
    const { dataSourceId, schemaName } = config.source;
    setConfig((prev) => ({ ...prev, source: { ...prev.source, tableName } }));
    if (!tableName) {
      return;
    }
    const columns = (await queryDataByParams(API_CONSTANTS.DATASOURCE_GET_COLUMNS_BY_TABLE, {
      id: dataSourceId,
      schemaName,
      tableName
    })) as DataSources.Column[];
    const fields: DataXField[] = (columns ?? []).map((c) => ({
      name: c.name,
      type: c.type,
      comment: c.comment
    }));
    setConfig((prev) => {
      const next = { ...prev, source: { ...prev.source, tableName, fields } };
      // 新建表模式：目标字段由源字段经类型映射派生
      if (!prev.target.tableName) {
        next.target = {
          ...prev.target,
          fields: fields.map((f) => ({ name: f.name, type: mapToHiveType(f.type), comment: f.comment }))
        };
        // 默认表名：ods_<源库>_<源表>（仅当用户没填、或填的仍是上一次自动值时覆盖）
        const autoName = `ods_${schemaName}_${tableName}`;
        if (!prev.newTargetTableName || prev.newTargetTableName === autoTargetNameRef.current) {
          next.newTargetTableName = autoName;
          autoTargetNameRef.current = autoName;
        }
      }
      next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
      return next;
    });
  };

  const handleTargetTableChange = async (tableName: string) => {
    const { dataSourceId, schemaName } = config.target;
    setConfig((prev) => ({ ...prev, target: { ...prev.target, tableName } }));
    if (!tableName) {
      setTargetTableExists(false);
      // 置空 = 新建表：目标字段回退为源字段派生
      setConfig((prev) => {
        const next = {
          ...prev,
          target: {
            ...prev.target,
            tableName: undefined,
            location: undefined,
            partitionColumns: [],
            fields: prev.source.fields.map((f) => ({
              name: f.name,
              type: mapToHiveType(f.type),
              comment: f.comment
            }))
          }
        };
        next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
        return next;
      });
      return;
    }
    const detail = (await queryDataByParams(API_CONSTANTS.DATASOURCE_GET_TABLE_DETAIL, {
      id: dataSourceId,
      schemaName,
      tableName
    })) as TableDetail | undefined;
    if (!detail) {
      setTargetTableExists(false);
      return;
    }
    setTargetTableExists(true);
    const fields: DataXField[] = (detail.columns ?? []).map((c) => ({
      name: c.name,
      type: c.type,
      comment: c.comment
    }));
    const partitionColumns: DataXField[] = (detail.partitionColumns ?? []).map((c) => ({
      name: c.name,
      type: c.type
    }));
    setConfig((prev) => {
      const next: QuickSyncConfig = {
        ...prev,
        fileType: detail.fileType ?? prev.fileType,
        target: {
          ...prev.target,
          tableName,
          fields,
          location: detail.location,
          partitionColumns,
          partitionColumn: partitionColumns.length ? partitionColumns[0].name : prev.target.partitionColumn
        }
      };
      next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
      return next;
    });
  };

  const handleAddField = (side: 'source' | 'target', name: string, type: string) => {
    if (!name) {
      message.warning('请输入字段名');
      return;
    }
    setConfig((prev) => {
      const fields = [...prev[side].fields, { name, type: type || 'STRING', comment: '' }];
      const next = { ...prev, [side]: { ...prev[side], fields } };
      next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
      return next;
    });
  };

  const handleDeleteField = (side: 'source' | 'target', fieldName: string) => {
    setConfig((prev) => {
      const fields = prev[side].fields.filter((f) => f.name !== fieldName);
      const mappings = prev.mappings.filter(
        (m) => m.sourceField !== fieldName && m.targetField !== fieldName
      );
      return { ...prev, [side]: { ...prev[side], fields }, mappings };
    });
  };

  /** 供编排回调：用最新配置（含重取到的 location）生成 DataX json */
  const buildSyncStatement = (detail?: TableDetail): string => {
    const cur = configRef.current;
    const target = {
      ...cur.target,
      tableName: cur.target.tableName || cur.newTargetTableName,
      location: detail?.location ?? cur.target.location,
      partitionColumns: detail?.partitionColumns?.length
        ? (detail.partitionColumns as DataXField[])
        : cur.target.partitionColumns,
      partitionColumn: cur.target.partitionColumn || DEFAULT_PARTITION_COLUMN
    };
    const nextConfig: QuickSyncConfig = {
      ...cur,
      target,
      fileType: detail?.fileType ?? effectiveFileType,
      defaultFS: hadoopConf.defaultFS ?? cur.defaultFS,
      hadoopConfig: hadoopConf.hadoopConfig ?? cur.hadoopConfig,
      channel: cur.channel ?? DEFAULT_CHANNEL,
      writeMode: cur.writeMode ?? DEFAULT_WRITE_MODE,
      fieldDelimiter: cur.fieldDelimiter ?? DEFAULT_FIELD_DELIMITER
    };
    return buildDataXJson(nextConfig);
  };

  const buildDdl = (): string =>
    buildCreateTableDdl({
      schemaName: config.target.schemaName,
      tableName: targetTableName,
      fields: config.target.fields,
      partitionColumn,
      fileType: effectiveFileType,
      comment: `DataX 同步自 ${config.source.schemaName}.${config.source.tableName}`
    });

  const buildPartitionSql = (): string =>
    buildAddPartitionSql(config.target.schemaName, targetTableName, partitionColumn, partitionExpression);

  const buildAnalyzeSqlFn = (): string =>
    buildAnalyzeSql(config.target.schemaName, targetTableName, partitionColumn, partitionExpression);

  /** 预览用的 writer path（未建表时用默认 warehouse 路径占位） */
  const previewPath = (): string => {
    const cur = config;
    return cur.target.location
      ? buildHdfsPath(
          cur.target.location,
          hadoopConf.defaultFS ?? cur.defaultFS ?? '',
          partitionColumn,
          partitionExpression
        )
      : buildDefaultHdfsPath(cur.target.schemaName ?? '', targetTableName) +
          `/${partitionColumn}=${partitionExpression}`;
  };

  const validate = (): string | undefined => {
    if (!config.source.dataSourceId || !config.source.schemaName || !config.source.tableName) {
      return '请选择源数据源、库与表';
    }
    if (!config.target.dataSourceId || !config.target.schemaName) {
      return '请选择目的数据源与库';
    }
    if (!targetTableName) {
      return '请选择目标表，或填写新建表名';
    }
    if (!config.dialect) {
      return '当前目标数据源类型暂不支持自动建表/加分区（仅支持 Hive / Spark）';
    }
    if (config.mappings.length === 0) {
      return '请至少配置一条字段映射';
    }
    const bad = config.target.fields.filter((f) => isUnsupportedHiveType(f.type));
    if (bad.length > 0) {
      return `目标字段类型 ${bad.map((f) => `${f.name}:${f.type}`).join('、')} 不被 DataX hdfswriter 支持，请改为 DOUBLE / STRING`;
    }
    if (config.target.partitionExpression && config.target.partitionExpression.includes('$[')) {
      return '分区值表达式要填任务参数引用（如 ${SYNC_TIME}）；$[yyyyMMdd-1] 这类时间表达式请填在下方任务参数的「值」里';
    }
    if (invalidParamRefs.length > 0) {
      return `分区值表达式里的参数名不合法：${invalidParamRefs
        .map((n) => '${' + n + '}')
        .join('、')}（参数名只能用字母、数字、下划线，如 ${PARAM_NAME_SAMPLE}）`;
    }
    const missing = validateParamRefs([buildPartitionSql(), previewPath()], taskParams);
    if (missing.length > 0) {
      return `分区值引用了未定义的任务参数：${missing
        .map((m) => '${' + m + '}')
        .join('、')}，请先在「任务参数」里补一行同名参数`;
    }
    return undefined;
  };

  /** 一键补齐表达式里缺失的任务参数（默认值对齐默认参数行 $[yyyyMMdd-1]） */
  const addMissingParams = () => {
    setTaskParams([
      ...taskParams,
      ...missingParams.map((prop) => ({ prop, direct: 'IN', type: 'VARCHAR', value: '$[yyyyMMdd-1]' }))
    ]);
  };

  const computeNames = (suffix = '') => ({
    createTable: `${QUICK_SYNC_PREFIX.createTable}${targetTableName}${suffix}`,
    syncData: `${QUICK_SYNC_PREFIX.syncData}${targetTableName}${suffix}`,
    addPartition: `${QUICK_SYNC_PREFIX.addPartition}${targetTableName}${suffix}`,
    analyzeTable: `${QUICK_SYNC_PREFIX.analyzeTable}${targetTableName}${suffix}`
  });

  const doRun = async (names: ReturnType<typeof computeNames>, overwriteIds?: any) => {
    setRunning(true);
    const res = await runQuickSync({
      parentId,
      subDirName: targetTableName,
      dialect: config.dialect ?? 'Hive',
      targetTableName,
      targetDataSourceId: config.target.dataSourceId,
      targetSchemaName: config.target.schemaName ?? '',
      names,
      overwriteIds,
      taskParams,
      firstLevelOwner: currentUserId,
      createTableDdl: buildDdl(),
      targetTableExists,
      execCreateTable,
      hasPartition: config.target.tableName
        ? (config.target.partitionColumns?.length ?? 0) > 0
        : !!partitionColumn,
      buildAddPartitionSql: buildPartitionSql,
      buildAnalyzeSql: buildAnalyzeSqlFn,
      buildSyncStatement,
      onStep: (s) => setSteps(s)
    });
    setResult(res);
    setRunning(false);
    if (res.created.length > 0) {
      onSuccess?.();
    }
  };

  const handleSubmit = async () => {
    const error = validate();
    if (error) {
      message.error(error);
      return;
    }
    // 重名预检（dinky_task name 全局唯一）
    const tree = await fetchCatalogueTree();
    const nameToId = new Map<string, number>();
    const walk = (nodes: CatalogueNode[]) => {
      (nodes ?? []).forEach((n) => {
        if (n?.name) {
          nameToId.set(n.name, n.id);
        }
        walk(n.children ?? []);
      });
    };
    walk(tree);
    const names = computeNames();
    const conflicts = Object.values(names).filter((n) => nameToId.has(n));
    if (conflicts.length > 0) {
      setConflict({ names: conflicts });
      return;
    }
    await doRun(names);
  };

  const resolveConflict = async (mode: 'overwrite' | 'suffix') => {
    const tree = await fetchCatalogueTree();
    const nameToId = new Map<string, number>();
    const walk = (nodes: CatalogueNode[]) => {
      (nodes ?? []).forEach((n) => {
        if (n?.name) {
          nameToId.set(n.name, n.id);
        }
        walk(n.children ?? []);
      });
    };
    walk(tree);
    setConflict(undefined);
    if (mode === 'overwrite') {
      const base = computeNames();
      await doRun(base, {
        createTable: nameToId.get(base.createTable),
        syncData: nameToId.get(base.syncData),
        addPartition: nameToId.get(base.addPartition),
        analyzeTable: nameToId.get(base.analyzeTable)
      });
      return;
    }
    let suffixNo = 2;
    let candidate = computeNames(`_${suffixNo}`);
    while (Object.values(candidate).some((n) => nameToId.has(n))) {
      suffixNo += 1;
      candidate = computeNames(`_${suffixNo}`);
    }
    await doRun(candidate);
  };

  const renderFieldAdder = (side: 'source' | 'target') => (
    <FieldAdder side={side} onAdd={handleAddField} />
  );

  const renderSourcePanel = () => (
    <div style={{ flex: 1, minWidth: 240 }}>
      <Text strong style={{ display: 'block', marginBottom: 8, color: '#1677ff' }}>
        {l('datastudio.datax.quickSync.source')}
      </Text>
      <Space direction='vertical' size={8} style={{ width: '100%' }}>
        <Select
          style={{ width: '100%' }}
          placeholder={l('datastudio.datax.quickSync.dataSource')}
          value={config.source.dataSourceId}
          options={mysqlSources.map((d) => ({ label: d.name, value: d.id }))}
          onChange={(v) => handleDataSourceChange('source', v as number)}
        />
        <Select
          style={{ width: '100%' }}
          placeholder={l('datastudio.datax.quickSync.schema')}
          value={config.source.schemaName || undefined}
          options={schemas.source.map((s) => ({ label: s.name, value: s.name }))}
          onChange={(v) => setConfig((prev) => ({ ...prev, source: { ...prev.source, schemaName: v } }))}
        />
        <Select
          style={{ width: '100%' }}
          placeholder={l('datastudio.datax.quickSync.table')}
          showSearch
          optionFilterProp='label'
          value={config.source.tableName || undefined}
          options={sourceTables.map((t) => ({ label: t.name, value: t.name }))}
          onChange={handleSourceTableChange}
        />
        {renderFieldAdder('source')}
      </Space>
    </div>
  );

  const renderTargetPanel = () => {
    const isNewTable = !config.target.tableName;
    return (
      <div style={{ flex: 1, minWidth: 240 }}>
        <Text strong style={{ display: 'block', marginBottom: 8, color: '#52c41a' }}>
          {l('datastudio.datax.quickSync.target')}
        </Text>
        <Space direction='vertical' size={8} style={{ width: '100%' }}>
          <Select
            style={{ width: '100%' }}
            placeholder={l('datastudio.datax.quickSync.dataSource')}
            value={config.target.dataSourceId}
            options={targetSources.map((d) => ({ label: `${d.name}（${d.type}）`, value: d.id }))}
            onChange={(v) => handleDataSourceChange('target', v as number)}
          />
          <Select
            style={{ width: '100%' }}
            placeholder={l('datastudio.datax.quickSync.schema')}
            value={config.target.schemaName || undefined}
            options={schemas.target.map((s) => ({ label: s.name, value: s.name }))}
            onChange={(v) => setConfig((prev) => ({ ...prev, target: { ...prev.target, schemaName: v } }))}
          />
          <Select
            style={{ width: '100%' }}
            placeholder={l('datastudio.datax.quickSync.tablePlaceholder')}
            showSearch
            allowClear
            optionFilterProp='label'
            value={config.target.tableName || undefined}
            options={targetTables.map((t) => ({ label: t.name, value: t.name }))}
            onChange={(v) => handleTargetTableChange(v ?? '')}
          />
          {isNewTable ? (
            <>
              <Input
                placeholder={l('datastudio.datax.quickSync.newTableName')}
                value={config.newTargetTableName}
                onChange={(e) =>
                  setConfig((prev) => ({ ...prev, newTargetTableName: e.target.value }))
                }
              />
              <Input
                placeholder={l('datastudio.datax.quickSync.partitionColumn')}
                value={config.target.partitionColumn}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    target: { ...prev.target, partitionColumn: e.target.value }
                  }))
                }
              />
              <Select
                style={{ width: '100%' }}
                placeholder={l('datastudio.datax.quickSync.fileType')}
                value={config.newFileType ?? DEFAULT_FILE_TYPE}
                options={FILE_TYPE_OPTIONS}
                onChange={(v) => setConfig((prev) => ({ ...prev, newFileType: v }))}
              />
            </>
          ) : (
            <>
              <Select
                style={{ width: '100%' }}
                placeholder={l('datastudio.datax.quickSync.partitionColumn')}
                value={config.target.partitionColumn}
                options={(config.target.partitionColumns ?? []).map((p) => ({
                  label: `${p.name}（${p.type}）`,
                  value: p.name
                }))}
                onChange={(v) =>
                  setConfig((prev) => ({ ...prev, target: { ...prev.target, partitionColumn: v } }))
                }
              />
              <Alert
                type={targetTableExists ? 'success' : 'warning'}
                showIcon
                message={
                  targetTableExists
                    ? `目标表已存在${config.fileType ? `（${config.fileType}）` : ''}，将只创建同步与加分区作业`
                    : '未获取到目标表详情'
                }
              />
            </>
          )}
          {renderFieldAdder('target')}
        </Space>
      </div>
    );
  };

  const paramColumns = [
    {
      title: '参数名',
      dataIndex: 'prop',
      render: (v: string, _r: QuickSyncParam, i: number) => (
        <Input
          size='small'
          value={v}
          placeholder='字母/数字/下划线，如 SYNC_TIME'
          onChange={(e) => {
            const next = [...taskParams];
            next[i] = { ...next[i], prop: e.target.value };
            setTaskParams(next);
          }}
        />
      )
    },
    {
      title: '方向',
      dataIndex: 'direct',
      width: 90,
      render: (v: string, _r: QuickSyncParam, i: number) => (
        <Select
          size='small'
          style={{ width: 80 }}
          value={v}
          options={PARAM_DIRECT_OPTIONS}
          onChange={(val) => {
            const next = [...taskParams];
            next[i] = { ...next[i], direct: val };
            setTaskParams(next);
          }}
        />
      )
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 120,
      render: (v: string, _r: QuickSyncParam, i: number) => (
        <Select
          size='small'
          style={{ width: 110 }}
          value={v}
          options={PARAM_TYPE_OPTIONS}
          onChange={(val) => {
            const next = [...taskParams];
            next[i] = { ...next[i], type: val };
            setTaskParams(next);
          }}
        />
      )
    },
    {
      title: '值',
      dataIndex: 'value',
      render: (v: string, _r: QuickSyncParam, i: number) => (
        <AutoComplete
          size='small'
          style={{ width: '100%' }}
          value={v}
          options={TIME_EXPR_OPTIONS}
          placeholder='如 $[yyyyMMdd-1]'
          onChange={(val) => {
            const next = [...taskParams];
            next[i] = { ...next[i], value: val };
            setTaskParams(next);
          }}
        />
      )
    },
    {
      title: '操作',
      width: 70,
      render: (_v: any, _r: QuickSyncParam, i: number) => (
        <Button
          size='small'
          type='link'
          danger
          onClick={() => setTaskParams(taskParams.filter((_, idx) => idx !== i))}
        >
          删除
        </Button>
      )
    }
  ];

  return (
    <>
      <Modal
        title={l('datastudio.datax.quickSync.title')}
        open={visible}
        width={1100}
        destroyOnClose
        maskClosable={false}
        onCancel={onCancel}
        okText='确定并执行'
        confirmLoading={running}
        onOk={handleSubmit}
        footer={[
          <Button key='cancel' onClick={onCancel} disabled={running}>
            取消
          </Button>,
          <Button
            key='preview'
            onClick={() => {
              if (validate()) {
                message.error(validate() as string);
                return;
              }
              setPreviewOpen(true);
            }}
          >
            {l('datastudio.datax.quickSync.preview')}
          </Button>,
          <Button key='ok' type='primary' loading={running} onClick={handleSubmit}>
            确定并执行
          </Button>
        ]}
      >
        {/* 用普通块级容器而非 Flex：Flex 子项默认可收缩，会把下方「任务参数」表格压成 0 高度 */}
        <div style={{ maxHeight: 'calc(100vh - 200px)', overflow: 'auto', paddingRight: 4 }}>
          <Space size={16} wrap>
            <Text type='secondary'>{l('datastudio.datax.quickSync.parentPath')}：{parentName ?? parentId}</Text>
            <Text type='secondary'>
              {l('datastudio.datax.quickSync.subDir')}：{targetTableName || '<待填目标表名>'}
            </Text>
            <Text type='secondary'>
              {l('datastudio.datax.quickSync.jobNames')}：{QUICK_SYNC_PREFIX.createTable}
              {targetTableName || '<表名>'} / {QUICK_SYNC_PREFIX.addPartition}
              {targetTableName || '<表名>'} / {QUICK_SYNC_PREFIX.syncData}
              {targetTableName || '<表名>'} / {QUICK_SYNC_PREFIX.analyzeTable}
              {targetTableName || '<表名>'}
            </Text>
          </Space>

          <Divider style={{ margin: '12px 0 8px' }} />
          <Text strong>{l('datastudio.datax.quickSync.partitionExpression')}</Text>
          <Row gutter={8} align='middle' style={{ marginTop: 6 }}>
            <Col flex='none'>
              <Input
                style={{ width: 280 }}
                addonBefore={`${partitionColumn}=`}
                placeholder={DEFAULT_PARTITION_EXPRESSION}
                value={config.target.partitionExpression}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    target: { ...prev.target, partitionExpression: e.target.value }
                  }))
                }
              />
            </Col>
            <Col flex='auto'>
              <Text type='secondary' style={{ fontSize: 12 }}>
                用 {'${参数名}'} 引用下方任务参数；时间算法写进参数的「值」里，如 {'$[yyyyMMdd-1]'}
              </Text>
            </Col>
          </Row>
          {invalidParamRefs.length > 0 && (
            <Alert
              style={{ marginTop: 6 }}
              type='error'
              showIcon
              message={`参数名不合法：${invalidParamRefs
                .map((n) => '${' + n + '}')
                .join('、')}，只能用字母、数字、下划线（如 ${PARAM_NAME_SAMPLE}）`}
            />
          )}
          {invalidParamRefs.length === 0 && missingParams.length > 0 && (
            <Alert
              style={{ marginTop: 6 }}
              type='warning'
              showIcon
              message={`表达式引用了未定义的任务参数：${missingParams
                .map((m) => '${' + m + '}')
                .join('、')}`}
              action={
                <Button size='small' onClick={addMissingParams}>
                  一键添加
                </Button>
              }
            />
          )}
          {invalidParamRefs.length === 0 &&
            missingParams.length === 0 &&
            targetTableName &&
            config.target.schemaName && (
              <Text type='secondary' style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
                写入分区路径：{previewPath()}
              </Text>
            )}

          <Divider style={{ margin: '8px 0' }} dashed />
          <Text strong>{l('datastudio.datax.quickSync.taskParams')}</Text>
          {/* 表格 footer 的默认背景在本项目主题变量下会渲染成黑条，这里覆盖为透明 */}
          <style>{`.quick-sync-param-table .ant-table-footer{background:transparent;padding:8px 0 0;}`}</style>
          <Table
            className='quick-sync-param-table'
            size='small'
            style={{ marginTop: 6 }}
            rowKey={(_r, i) => String(i)}
            pagination={false}
            dataSource={taskParams}
            columns={paramColumns}
            footer={() => (
              <Button
                size='small'
                icon={<PlusOutlined />}
                onClick={() =>
                  setTaskParams([...taskParams, { prop: '', direct: 'IN', type: 'VARCHAR', value: '' }])
                }
              >
                添加参数
              </Button>
            )}
          />

          <Row gutter={16} style={{ marginTop: 8 }}>
            <Col span={12}>{renderSourcePanel()}</Col>
            <Col span={12}>{renderTargetPanel()}</Col>
          </Row>

          <Divider style={{ margin: '12px 0 8px' }} />
          <Text strong>{l('datastudio.datax.quickSync.mapping')}</Text>
          {config.source.fields.length > 0 || config.target.fields.length > 0 ? (
            <div
              style={{
                height: 280,
                border: '1px solid #f0f0f0',
                borderRadius: 6,
                marginTop: 6
              }}
            >
              <FieldMapping
                sourceFields={config.source.fields}
                targetFields={config.target.fields}
                mappings={config.mappings}
                onMappingsChange={(mappings) => setConfig((prev) => ({ ...prev, mappings }))}
                onDeleteSourceField={(name) => handleDeleteField('source', name)}
                onDeleteTargetField={(name) => handleDeleteField('target', name)}
              />
            </div>
          ) : (
            <div style={{ height: 160, border: '1px dashed #d9d9d9', borderRadius: 6, marginTop: 6 }}>
              <Empty description='选择源表（或填写新建目标表）后，在此展示字段并支持连线映射' />
            </div>
          )}

          <Checkbox
            checked={execCreateTable}
            onChange={(e) => setExecCreateTable(e.target.checked)}
            style={{ marginTop: 4 }}
          >
            {l('datastudio.datax.quickSync.execCreateTable')}
          </Checkbox>

          {result && (
            <>
              <Divider style={{ margin: '4px 0' }} />
              <Text strong>{l('datastudio.datax.quickSync.summary')}</Text>
              <Steps
                size='small'
                direction='vertical'
                items={result.steps.map((s) => ({
                  title: s.label,
                  status: stepStatusOf(s.status),
                  description: s.detail
                }))}
              />
              {result.residual.length > 0 && (
                <Alert
                  type='error'
                  showIcon
                  message={`以下对象补偿删除失败，请手工处理：${result.residual
                    .map((r) => r.label)
                    .join('、')}`}
                />
              )}
              {result.executedCreateTable && (
                <Alert type='info' showIcon message='目标表已在 Hive 侧创建（不可回滚）' />
              )}
            </>
          )}
        </div>
      </Modal>

      <Modal
        title={l('datastudio.datax.quickSync.preview')}
        open={previewOpen}
        width={900}
        /*
         * 固定更高的层级 + 关闭即销毁。
         * 主弹窗带 destroyOnClose：关闭后它的 wrap 会被移除，第二次打开时重新追加到 body 末尾；
         * 而预览弹窗若残留旧 DOM 节点会排在主弹窗之前，两者 z-index 又同为 1000，
         * 于是预览弹窗被主弹窗盖住——表现为「第二次使用时点预览生成物弹不出来，强刷才恢复」。
         */
        zIndex={1100}
        destroyOnClose
        onCancel={() => setPreviewOpen(false)}
        footer={[
          <Button key='close' onClick={() => setPreviewOpen(false)}>
            关闭
          </Button>
        ]}
      >
        <Flex vertical gap={8} style={{ maxHeight: '60vh', overflow: 'auto' }}>
          <Text type='secondary'>
            执行顺序：建表 → 加分区（建出分区目录）→ 同步数据 → 刷新统计（顺序不能变，否则写数据会报 path
            不存在、或统计为空）
          </Text>
          <Text strong>① 建表 DDL（作业 {QUICK_SYNC_PREFIX.createTable}{targetTableName}）</Text>
          <Paragraph code copyable style={{ whiteSpace: 'pre-wrap' }}>
            {buildDdl()}
          </Paragraph>
          <Text strong>② 加分区 SQL（作业 {QUICK_SYNC_PREFIX.addPartition}{targetTableName}）</Text>
          <Paragraph code copyable style={{ whiteSpace: 'pre-wrap' }}>
            {buildPartitionSql()}
          </Paragraph>
          <Text strong>③ DataX JSON（作业 {QUICK_SYNC_PREFIX.syncData}{targetTableName}）</Text>
          <Paragraph code copyable style={{ whiteSpace: 'pre-wrap' }}>
            {buildSyncStatement()}
          </Paragraph>
          <Text type='secondary'>writer path：{previewPath()}</Text>
          <Text strong>④ 统计 SQL（作业 {QUICK_SYNC_PREFIX.analyzeTable}{targetTableName}）</Text>
          <Paragraph code copyable style={{ whiteSpace: 'pre-wrap' }}>
            {buildAnalyzeSqlFn()}
          </Paragraph>
        </Flex>
      </Modal>

      <Modal
        title='作业名已存在'
        open={!!conflict}
        /* 同预览弹窗：必须高于主弹窗，否则二次使用时会被主弹窗盖住导致无法选择 */
        zIndex={1150}
        destroyOnClose
        onCancel={() => setConflict(undefined)}
        footer={[
          <Button key='cancel' onClick={() => setConflict(undefined)}>
            取消
          </Button>,
          <Button key='suffix' onClick={() => resolveConflict('suffix')}>
            自动加后缀
          </Button>,
          <Button key='overwrite' type='primary' onClick={() => resolveConflict('overwrite')}>
            覆盖更新
          </Button>
        ]}
      >
        <Paragraph>
          以下作业名已在目录树中存在：{conflict?.names.join('、')}
        </Paragraph>
        <Paragraph type='secondary'>
          选择「覆盖更新」将更新同名作业的语句与参数；选择「自动加后缀」将创建 <Text code>_2</Text>{' '}
          结尾的新作业。
        </Paragraph>
      </Modal>
    </>
  );
};

export default DataXQuickSyncModal;
