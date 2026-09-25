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

import { QueryParams } from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/data';
import Source from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/Source';
import Visual from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/Visual';
import { buildErDiagram } from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/function';
import { getTableRelations } from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/service';
import { DataSources } from '@/types/RegCenter/data';
import { l } from '@/utils/intl';
import { ApartmentOutlined, CodeOutlined } from '@ant-design/icons';
import { Alert, Space, Tabs } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';

type ERDiagramProps = {
  queryParams: QueryParams;
  tableInfo: Partial<DataSources.Table>;
};

/**
 * ER 图（Entity Diagram）
 *
 * <p>左上角两个子 Tab：
 * <ul>
 *     <li>可视化：mermaid 渲染的 ER 图（当前表 + 上下游关联表）</li>
 *     <li>源代码：原始 mermaid 源码，可复制</li>
 * </ul>
 */
const ERDiagram: React.FC<ERDiagramProps> = (props) => {
  const { queryParams, tableInfo } = props;
  const [activeView, setActiveView] = useState('visual');
  const [relations, setRelations] = useState<DataSources.TableRelations>();
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  useEffect(() => {
    let cancelled = false;
    const fetchRelations = async () => {
      if (!queryParams?.tableName) {
        setRelations(undefined);
        return;
      }
      setLoading(true);
      setErrorMsg('');
      try {
        const result = await getTableRelations(queryParams);
        if (!cancelled) {
          setRelations(result);
        }
      } catch (error: any) {
        if (!cancelled) {
          setRelations(undefined);
          setErrorMsg(error?.message ?? String(error));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };
    fetchRelations();
    return () => {
      cancelled = true;
    };
  }, [queryParams]);

  const mermaidCode = useMemo(() => buildErDiagram(tableInfo, relations), [tableInfo, relations]);

  const items = [
    {
      key: 'visual',
      label: (
        <Space>
          <ApartmentOutlined />
          {l('rc.ds.erdiagram.visual')}
        </Space>
      ),
      children: <Visual tableInfo={tableInfo} relations={relations} loading={loading} />
    },
    {
      key: 'source',
      label: (
        <Space>
          <CodeOutlined />
          {l('rc.ds.erdiagram.source')}
        </Space>
      ),
      children: <Source code={mermaidCode} />
    }
  ];

  return (
    <>
      {errorMsg && (
        <Alert
          type='error'
          showIcon
          style={{ marginBottom: 8 }}
          message={l('rc.ds.erdiagram.loadError')}
          description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{errorMsg}</pre>}
        />
      )}
      <Tabs activeKey={activeView} onChange={setActiveView} items={items} />
    </>
  );
};

export default ERDiagram;
