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
import { Button, Col, Empty, Flex, Input, message, Row, Select, Space, Tabs, Typography } from 'antd';
import { useMemo, useState } from 'react';
import FieldMapping from './FieldMapping';
import {
  buildDataXJson,
  DataXField,
  DataXMapping,
  DataXVisualConfig,
  parseDataXJson
} from './dataxJson';

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
  source: { fields: [] },
  target: { fields: [] },
  mappings: []
});

const buildDefaultMappings = (sourceFields: DataXField[], targetFields: DataXField[]): DataXMapping[] => {
  const length = Math.min(sourceFields.length, targetFields.length);
  const mappings: DataXMapping[] = [];
  for (let i = 0; i < length; i++) {
    mappings.push({ sourceField: sourceFields[i].name, targetField: targetFields[i].name });
  }
  return mappings;
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
      const nextSide =
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

    if (side === 'target') {
      // 目标（Hive）：调 getTableDetail 拿 location/fileType/分区字段/普通字段
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
          target: {
            ...prev.target,
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
        message.warning('目标表为 Parquet 格式，开源 DataX hdfswriter 不支持写入，请改用 orc/text 表');
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

  const handleGenerate = () => {
    if (!config.source.tableName || !config.target.tableName) {
      message.warning('请先选择源表与目标表');
      return;
    }
    if (config.mappings.length === 0) {
      message.warning('请至少配置一条字段映射');
      return;
    }
    onChange(buildDataXJson(config));
    setMode('json');
    message.success('已根据可视化配置生成 DataX JSON，并写回编辑区');
  };

  const renderSideSelect = (side: 'source' | 'target') => {
    const isSource = side === 'source';
    const sources = isSource ? mysqlSources : hiveSources;
    const tables = isSource ? sourceTables : targetTables;
    const schemaList = isSource ? schemas.source : schemas.target;
    const cfg = isSource ? config.source : config.target;

    return (
      <div style={{ flex: 1, minWidth: 240 }}>
        <Text
          strong
          style={{ display: 'block', marginBottom: 8, color: isSource ? '#1677ff' : '#52c41a' }}
        >
          {isSource ? '数据源（MySQL）' : '数据目的地（Spark/Hive）'}
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
          {!isSource && cfg.partitionColumns && cfg.partitionColumns.length > 0 && (
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
                  setConfig((prev) => ({ ...prev, target: { ...prev.target, partitionColumn: v } }))
                }
              />
              <Input
                placeholder='分区值表达式，如 ${SYNC_DATE}-${SYNC_TIME}'
                value={cfg.partitionExpression}
                onChange={(e) =>
                  setConfig((prev) => ({
                    ...prev,
                    target: { ...prev.target, partitionExpression: e.target.value }
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
