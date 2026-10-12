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

import CodeEdit from '@/components/CustomEditor/CodeEdit';
import { showDataSourceTable } from '@/pages/DataStudio/Toolbar/DataSource/service';
import { queryDataByParams } from '@/services/BusinessCrud';
import { DIALECT } from '@/services/constants';
import { API_CONSTANTS } from '@/services/endpoints';
import { DataSources } from '@/types/RegCenter/data';
import { PlusOutlined } from '@ant-design/icons';
import { Button, Col, Empty, Flex, Input, message, Row, Segmented, Select, Space, Tabs, Typography } from 'antd';
import { useMemo, useState } from 'react';
import FieldMapping from './FieldMapping';
import {
  DEFAULT_REVERSE_WRITE_MODE,
  DIRECTION_OPTIONS,
  REVERSE_WRITE_MODE_OPTIONS
} from '@/pages/DataStudio/Toolbar/Project/DataXQuickSyncModal/constants';
import {
  buildDataXJson,
  DataXField,
  DataXMapping,
  DataXVisualConfig,
  parseDataXJson
} from './dataxJson';
import type { DataXDirection } from './dataxJson';

const { Text } = Typography;

interface DataXVisualEditorProps {
  statement: string;
  onChange: (value: string) => void;
  databaseDataList: DataSources.DataSource[];
  readOnly?: boolean;
}

interface SchemaNode {
  name: string;
  tables: { name: string }[];
}

const emptyConfig = (): DataXVisualConfig => ({
  // 默认方向 MySQL→Hive（保持既有行为）；切换到反向时两侧角色互换
  direction: 'mysql2hive',
  source: { fields: [] },
  target: { fields: [] },
  mappings: []
});

/**
 * 默认字段映射：按【同名字段】配对。
 * ⚠️ 不能用下标顺序配对：两侧列集不同时会静默错配（与一键同步向导保持一致）。
 */
const buildDefaultMappings = (sourceFields: DataXField[], targetFields: DataXField[]): DataXMapping[] => {
  const targetNames = new Set(targetFields.map((f) => f.name));
  return sourceFields
    .filter((f) => targetNames.has(f.name))
    .map((f) => ({ sourceField: f.name, targetField: f.name }));
};

const DataXVisualEditor = (props: DataXVisualEditorProps) => {
  const { statement, onChange, databaseDataList, readOnly } = props;

  const [mode, setMode] = useState<'json' | 'visual'>('json');
  const [config, setConfig] = useState<DataXVisualConfig>(emptyConfig());
  const [schemas, setSchemas] = useState<{ source: SchemaNode[]; target: SchemaNode[] }>({
    source: [],
    target: []
  });

  const mysqlSources = useMemo(
    () => databaseDataList.filter((d) => d.type?.toLowerCase() === 'mysql'),
    [databaseDataList]
  );
  const hiveSources = useMemo(
    () => databaseDataList.filter((d) => d.type?.toLowerCase() === 'hive'),
    [databaseDataList]
  );

  /** 当前同步方向（缺省 mysql2hive） */
  const isReverse = (config.direction ?? 'mysql2hive') === 'hive2mysql';
  /** 该侧是否为 MySQL 侧：正向=源、反向=目标 */
  const isMysqlSide = (side: 'source' | 'target') => (isReverse ? side === 'target' : side === 'source');
  /** 该侧是否为 Hive/Spark 侧：决定「分区字段/表达式」展示与 getTableDetail 归属 */
  const isHiveSide = (side: 'source' | 'target') => !isMysqlSide(side);

  const sourceTables = schemas.source.find((s) => s.name === config.source.schemaName)?.tables ?? [];
  const targetTables = schemas.target.find((s) => s.name === config.target.schemaName)?.tables ?? [];

  const loadSchemas = async (side: 'source' | 'target', dataSourceId: number) => {
    const res = await showDataSourceTable(dataSourceId);
    const nodes: SchemaNode[] = (res ?? []).map((s: any) => ({
      name: s.name,
      tables: (s.tables ?? []).map((t: any) => ({ name: t.name }))
    }));
    setSchemas((prev) => ({ ...prev, [side]: nodes }));
  };

  const loadHadoopConfig = async () => {
    const conf = (await queryDataByParams(API_CONSTANTS.DATASOURCE_HADOOP_CONFIG)) as {
      defaultFS?: string;
      hadoopConfig?: Record<string, string>;
    };
    if (conf?.defaultFS || conf?.hadoopConfig) {
      setConfig((prev) => ({
        ...prev,
        defaultFS: conf.defaultFS ?? prev.defaultFS,
        hadoopConfig: conf.hadoopConfig ?? prev.hadoopConfig
      }));
    }
  };

  const handleModeChange = (key: string) => {
    if (key === 'json') {
      setMode('json');
      return;
    }
    // JSON 与可视化联动：JSON 合法则解析展示；为空或不符合规范则置空，允许从零编辑
    const parsed = parseDataXJson(statement);
    if (!parsed) {
      setConfig(emptyConfig());
      setSchemas({ source: [], target: [] });
      setMode('visual');
      loadHadoopConfig();
      if (statement && statement.trim()) {
        message.info('当前 JSON 内容无法解析，可视化已重置为空白配置，可直接开始配置');
      }
      return;
    }
    setConfig(parsed);
    setMode('visual');
    loadHadoopConfig();
    if (parsed.source.dataSourceId) {
      loadSchemas('source', parsed.source.dataSourceId);
    }
    if (parsed.target.dataSourceId) {
      loadSchemas('target', parsed.target.dataSourceId);
    }
  };

  const handleDataSourceChange = (side: 'source' | 'target', dataSourceId: number) => {
    const ds = databaseDataList.find((d) => d.id === dataSourceId);
    setConfig((prev) => {
      // 连接信息只对 MySQL 侧有意义（正向=源、反向=目标），由方向决定
      const nextSide = isMysqlSide(side)
        ? {
            ...prev[side],
            dataSourceId,
            dataSourceName: ds?.name,
            jdbcUrl: ds?.connectConfig?.url,
            username: ds?.connectConfig?.username,
            password: ds?.connectConfig?.password
          }
        : { ...prev[side], dataSourceId, dataSourceName: ds?.name };
      return { ...prev, [side]: nextSide };
    });
    setSchemas((prev) => ({ ...prev, [side]: [] }));
    loadSchemas(side, dataSourceId);
  };

  const handleTableChange = async (
    side: 'source' | 'target',
    dataSourceId: number,
    schemaName: string,
    tableName: string
  ) => {
    setConfig((prev) => ({ ...prev, [side]: { ...prev[side], schemaName, tableName } }));
    if (!tableName) {
      return;
    }

    if (isHiveSide(side)) {
      // Hive/Spark 侧（正向=目标、反向=源）：调 getTableDetail 拿 location/fileType/分区字段/普通字段
      const detail = (await queryDataByParams(API_CONSTANTS.DATASOURCE_GET_TABLE_DETAIL, {
        id: dataSourceId,
        schemaName,
        tableName
      })) as {
        location?: string;
        fileType?: string;
        partitionColumns?: DataSources.Column[];
        columns?: DataSources.Column[];
      };
      if (!detail) {
        return;
      }
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
        const next: DataXVisualConfig = {
          ...prev,
          fileType: detail.fileType ?? prev.fileType,
          [side]: {
            ...prev[side],
            schemaName,
            tableName,
            fields,
            location: detail.location,
            partitionColumns,
            partitionColumn: detail.partitionColumns?.length
              ? detail.partitionColumns[0].name
              : undefined
          }
        };
        next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
        return next;
      });
      if (detail.fileType === 'parquet') {
        if (isReverse) {
          // 反向：DataX 的 hdfsreader 只支持 ORC/SEQ/RC/CSV/TEXT，**不支持 Parquet**
          // （已核实插件 HdfsFileType 枚举），生成 JSON 后运行期必报 HdfsReader-12。
          message.warning(
            '源表为 Parquet 格式：DataX 的 hdfsreader 不支持 Parquet（仅支持 ORC/TEXT 等），无法反向同步。' +
              '请将 ADS 层表建成 ORC 存储；如需日期字段，请在表设计时显式添加一个普通列，不要用分区字段当日期。',
            6
          );
        } else {
          // 正向：fork 版 hdfswriter 支持 Parquet（须显式传 parquetSchema），此处仅提示、不阻断
          message.info(
            '目标表为 Parquet 格式：生成 JSON 时会自动附带 parquetSchema（fork 版 hdfswriter 必须显式传入，已实测通过）'
          );
        }
      }
      return;
    }

    // 源（MySQL）：listColumns
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
      const next = { ...prev, [side]: { ...prev[side], schemaName, tableName, fields } };
      next.mappings = buildDefaultMappings(next.source.fields, next.target.fields);
      return next;
    });
  };

  const handleAddField = (side: 'source' | 'target') => {
    setConfig((prev) => {
      const fields = [...prev[side].fields];
      fields.push({ name: `new_field_${fields.length + 1}`, type: '', comment: '' });
      return { ...prev, [side]: { ...prev[side], fields } };
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

  const handleGenerate = async () => {
    if (!config.source.tableName || !config.target.tableName) {
      message.warning('请先选择源表与目标表');
      return;
    }
    // 反向：生成前用最新元数据刷新源表的 fileType / location，避免沿用回填的旧值
    // （如 fileType=text、location 只剩 scheme 导致 reader.path 丢表目录）。
    let buildConfig = config;
    if (isReverse && config.source.dataSourceId && config.source.schemaName) {
      try {
        const detail = (await queryDataByParams(API_CONSTANTS.DATASOURCE_GET_TABLE_DETAIL, {
          id: config.source.dataSourceId,
          schemaName: config.source.schemaName,
          tableName: config.source.tableName
        })) as { fileType?: string; location?: string } | null;
        if (detail && (detail.fileType || detail.location)) {
          buildConfig = {
            ...config,
            fileType: detail.fileType ?? config.fileType,
            source: { ...config.source, location: detail.location ?? config.source.location }
          };
          setConfig(buildConfig);
        }
      } catch (e) {
        // 元数据刷新失败时沿用当前配置，不阻断生成
      }
    }
    // 反向：hdfsreader 只支持 ORC/SEQ/RC/CSV/TEXT，Parquet 源表在运行期必报 HdfsReader-12，直接阻断生成
    if (isReverse && (buildConfig.fileType ?? '').toLowerCase() === 'parquet') {
      message.warning(
        '源表为 Parquet 格式，DataX 的 hdfsreader 不支持，无法反向同步；请改用 ORC 存储的源表' +
          '（如需日期字段，请在表设计时显式添加一个普通列，不要用分区字段当日期）。',
        6
      );
      return;
    }
    if (config.mappings.length === 0) {
      message.warning('请至少配置一条字段映射');
      return;
    }
    // 反向：update（upsert）模式必须选业务主键，否则幂等失效（会退化为普通插入）
    if (isReverse) {
      const writeMode = config.writeMode ?? DEFAULT_REVERSE_WRITE_MODE;
      if (writeMode === 'update' && (config.updateKey ?? []).length === 0) {
        message.warning(
          '反向同步在 update（upsert）模式下必须选择业务主键（updateKey），且该列须为目标表主键或唯一索引'
        );
        return;
      }
    }
    onChange(buildDataXJson(buildConfig));
    setMode('json');
    message.success('已根据可视化配置生成 DataX JSON，并写回编辑区');
  };

  const renderSideSelect = (side: 'source' | 'target') => {
    const isSource = side === 'source';
    /** MySQL 侧：正向=源、反向=目标 */
    const mysqlSide = isMysqlSide(side);
    /** 数据源候选按方向决定：MySQL 侧始终选 MySQL 数据源，Hive 侧选 Hive/Spark */
    const sources = mysqlSide ? mysqlSources : hiveSources;
    const tables = isSource ? sourceTables : targetTables;
    const schemaList = isSource ? schemas.source : schemas.target;
    const cfg = isSource ? config.source : config.target;
    const sideLabel = mysqlSide
      ? isReverse
        ? '数据目的地（MySQL）'
        : '数据源（MySQL）'
      : isReverse
        ? '数据源（Hive/Spark）'
        : '数据目的地（Hive/Spark）';

    return (
      <div style={{ flex: 1, minWidth: 240 }}>
        <Text
          strong
          style={{ display: 'block', marginBottom: 8, color: mysqlSide ? '#1677ff' : '#52c41a' }}
        >
          {sideLabel}
        </Text>
        <Space direction='vertical' size={8} style={{ width: '100%' }}>
          <Select
            style={{ width: '100%' }}
            placeholder='请选择数据源'
            value={cfg.dataSourceId}
            options={sources.map((d) => ({ label: d.name, value: d.id }))}
            onChange={(v) => handleDataSourceChange(side, v as number)}
          />
          <Select
            style={{ width: '100%' }}
            placeholder='请选择库'
            value={cfg.schemaName || undefined}
            options={schemaList.map((s) => ({ label: s.name, value: s.name }))}
            onChange={(v) =>
              setConfig((prev) => ({
                ...prev,
                [side]: { ...prev[side], schemaName: v, tableName: '' }
              }))
            }
          />
          <Select
            style={{ width: '100%' }}
            placeholder='请选择表'
            showSearch
            value={cfg.tableName || undefined}
            optionFilterProp='label'
            options={tables.map((t) => ({ label: t.name, value: t.name }))}
            onChange={(v) => handleTableChange(side, cfg.dataSourceId!, cfg.schemaName!, v)}
          />
          {isHiveSide(side) && cfg.partitionColumns && cfg.partitionColumns.length > 0 && (
            <>
              <Select
                style={{ width: '100%' }}
                placeholder='请选择分区字段'
                value={cfg.partitionColumn}
                options={cfg.partitionColumns.map((p) => ({
                  label: `${p.name} (${p.type})`,
                  value: p.name
                }))}
                onChange={(v) =>
                  setConfig((prev) => ({ ...prev, [side]: { ...prev[side], partitionColumn: v } }))
                }
              />
              <Input
                placeholder='分区值表达式，用 ${参数名} 引用任务参数，如 ${SYNC_TIME}'
                value={cfg.partitionExpression}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    [side]: { ...prev[side], partitionExpression: e.target.value }
                  }))
                }
              />
            </>
          )}
          <Button block size='small' icon={<PlusOutlined />} onClick={() => handleAddField(side)}>
            添加字段
          </Button>
        </Space>
      </div>
    );
  };

  const visualContent = (
    <Flex vertical gap={12} style={{ height: '100%', padding: 8 }}>
      <Flex align='center' gap={8} wrap='wrap'>
        <Text strong>同步方向：</Text>
        <Segmented
          size='small'
          value={config.direction ?? 'mysql2hive'}
          options={DIRECTION_OPTIONS}
          disabled={readOnly}
          onChange={(v) =>
            // 方向切换会让两侧角色互换，故清空已选表/映射/连接信息，避免残留错配
            setConfig((prev) => ({
              ...emptyConfig(),
              direction: v as DataXDirection,
              defaultFS: prev.defaultFS,
              hadoopConfig: prev.hadoopConfig
            }))
          }
        />
        {isReverse && (
          <Text type='secondary' style={{ fontSize: 12 }}>
            源＝Hive/Spark 表（读 HDFS 分区目录），目标＝MySQL 表（默认 upsert）
          </Text>
        )}
      </Flex>
      <Row gutter={16}>
        <Col span={12}>{renderSideSelect('source')}</Col>
        <Col span={12}>{renderSideSelect('target')}</Col>
      </Row>
      {config.source.fields.length > 0 || config.target.fields.length > 0 ? (
        <div style={{ flex: 1, minHeight: 320, border: '1px solid #f0f0f0', borderRadius: 6 }}>
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
        <div style={{ flex: 1, minHeight: 320, border: '1px dashed #d9d9d9', borderRadius: 6 }}>
          <Empty
            description='选择数据源、库、表后，将在此展示字段并支持连线映射'
            style={{ marginTop: 120 }}
          />
        </div>
      )}
      {isReverse && (
        <Flex gap={16} wrap='wrap' align='center' style={{ paddingTop: 4 }}>
          <Flex align='center' gap={6}>
            <Text strong>写入模式：</Text>
            <Select
              size='small'
              style={{ width: 320 }}
              value={config.writeMode ?? DEFAULT_REVERSE_WRITE_MODE}
              options={REVERSE_WRITE_MODE_OPTIONS}
              disabled={readOnly}
              onChange={(v) => setConfig((prev) => ({ ...prev, writeMode: v }))}
            />
          </Flex>
          {(config.writeMode ?? DEFAULT_REVERSE_WRITE_MODE) === 'update' && (
            <Flex align='center' gap={6}>
              <Text strong>业务主键（updateKey）：</Text>
              <Select
                mode='multiple'
                size='small'
                style={{ width: 280 }}
                placeholder='须为目标表主键/唯一索引列'
                value={config.updateKey ?? []}
                options={(config.target.fields ?? []).map((f) => ({ label: f.name, value: f.name }))}
                disabled={readOnly}
                onChange={(v) => setConfig((prev) => ({ ...prev, updateKey: v }))}
              />
            </Flex>
          )}
          <Text type='secondary' style={{ fontSize: 12 }}>
            读取格式：{config.fileType ?? 'orc'}（取自源表详情；错配会报 HdfsReader-12）
          </Text>
        </Flex>
      )}
    </Flex>
  );

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Flex
        justify='space-between'
        align='center'
        style={{ padding: '4px 8px', borderBottom: '1px solid #f0f0f0' }}
      >
        <Tabs
          activeKey={mode}
          onChange={handleModeChange}
          size='small'
          items={[
            { key: 'json', label: 'JSON' },
            { key: 'visual', label: '可视化' }
          ]}
          style={{ marginBottom: -1 }}
        />
        {mode === 'visual' && (
          <Button type='primary' size='small' onClick={handleGenerate} disabled={readOnly}>
            生成 JSON
          </Button>
        )}
      </Flex>
      <div style={{ flex: 1, height: 0 }}>
        {mode === 'json' ? (
          <CodeEdit
            code={statement}
            language={DIALECT.JSON}
            onChange={(value) => onChange(value ?? '')}
            options={{
              readOnly,
              scrollBeyondLastLine: false,
              wordWrap: 'on'
            }}
          />
        ) : (
          visualContent
        )}
      </div>
    </div>
  );
};

export default DataXVisualEditor;
