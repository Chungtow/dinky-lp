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

import {
  buildErGraphData,
  ErGraphNode
} from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/function';
import { DataSources } from '@/types/RegCenter/data';
import { l } from '@/utils/intl';
import { Graphin } from '@antv/graphin';
import { ExtensionCategory, Graph, register } from '@antv/g6';
import { ReactNode } from '@antv/g6-extension-react';
import { Empty, Spin } from 'antd';
import React, { useEffect, useMemo, useRef } from 'react';

// 注册 React 节点（G6 扩展），用于渲染「表卡片」
register(ExtensionCategory.NODE, 'er-table-node', ReactNode);

const CARD_WIDTH = 220;
const HEADER_HEIGHT = 34;
const ROW_HEIGHT = 22;

const nodeHeight = (columnCount: number) => HEADER_HEIGHT + Math.max(columnCount, 1) * ROW_HEIGHT;

/** 表卡片：表名 + 字段列表（PK / FK 标记） */
const TableCard: React.FC<{ data: ErGraphNode['data'] }> = ({ data }) => {
  if (!data) {
    return null;
  }
  const { name, isCurrent, columns } = data;
  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        boxSizing: 'border-box',
        border: `1px solid ${isCurrent ? '#5B8FF9' : '#DFE5EF'}`,
        borderRadius: 6,
        background: '#FFFFFF',
        overflow: 'hidden'
      }}
    >
      <div
        title={name}
        style={{
          height: HEADER_HEIGHT,
          lineHeight: `${HEADER_HEIGHT}px`,
          padding: '0 10px',
          boxSizing: 'border-box',
          background: isCurrent ? '#EAF1FE' : '#F5F7FA',
          borderBottom: '1px solid #E4E9F2',
          fontWeight: 600,
          fontSize: 13,
          color: '#1F2733',
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis'
        }}
      >
        {name}
      </div>
      <div style={{ padding: '2px 0' }}>
        {columns.map((column) => (
          <div
            key={column.name}
            title={column.name}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              height: ROW_HEIGHT,
              padding: '0 10px',
              boxSizing: 'border-box',
              fontSize: 12,
              color: '#3D4757'
            }}
          >
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {column.name}
            </span>
            {column.mark ? (
              <span
                style={{
                  marginLeft: 8,
                  fontSize: 10,
                  fontWeight: 600,
                  color: column.mark === 'PK' ? '#D48806' : '#1677FF'
                }}
              >
                {column.mark}
              </span>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  );
};

type VisualProps = {
  tableInfo: Partial<DataSources.Table>;
  relations?: DataSources.TableRelations;
  loading: boolean;
};

/**
 * ER 图可视化视图（G6）
 *
 * <p>一张表一个节点（卡片内展示字段与 PK/FK），表之间以有向边表达外键关系：
 * <ul>
 *     <li>拖动表卡片，连接线自动跟随（drag-element）</li>
 *     <li>空白处按住左键拖动整体（drag-canvas）</li>
 *     <li>滚轮缩放 + 右上角工具栏放大 / 缩小 / 适应视图（zoom-canvas）</li>
 *     <li>默认按合适比例展示全貌（autoFit: view）</li>
 * </ul>
 */
const Visual: React.FC<VisualProps> = (props) => {
  const { tableInfo, relations, loading } = props;
  const graphRef = useRef<Graph>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const graphData = useMemo(() => buildErGraphData(tableInfo, relations), [tableInfo, relations]);
  // 数据变化时重建图实例，避免增量更新的状态残留
  const graphKey = useMemo(
    () => `${tableInfo?.name ?? ''}-${graphData.nodes.length}-${graphData.edges.length}`,
    [tableInfo, graphData]
  );

  // 容器尺寸自适应
  useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      if (
        graphRef.current &&
        entries?.length === 1 &&
        entries[0].contentRect.width > 0 &&
        entries[0].contentRect.height > 0
      ) {
        graphRef.current.setSize(entries[0].contentRect.width, entries[0].contentRect.height);
      }
    });
    observer.observe(element);
    return () => observer.unobserve(element);
  }, []);

  if (loading) {
    return <Spin spinning style={{ width: '100%', padding: '48px 0' }} />;
  }

  if (!graphData.nodes.length) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={l('rc.ds.erdiagram.empty')} />;
  }

  return (
    <div ref={containerRef} style={{ width: '100%', height: '62vh' }}>
      <Graphin
        key={graphKey}
        ref={graphRef}
        style={{ width: '100%', height: '100%', overflow: 'hidden' }}
        options={{
          autoResize: true,
          autoFit: 'view',
          padding: 24,
          data: {
            nodes: graphData.nodes as any,
            edges: graphData.edges as any
          },
          node: {
            type: 'er-table-node',
            style: {
              size: (d: any) => [CARD_WIDTH, nodeHeight(d.data?.columns?.length ?? 0)],
              component: (d: any) => <TableCard data={d.data} />
            },
            state: {
              active: {
                lineWidth: 2,
                stroke: '#5B8FF9'
              },
              inactive: {
                opacity: 0.3
              }
            }
          } as any,
          edge: {
            type: 'cubic-horizontal',
            style: {
              stroke: '#A3B1C6',
              lineWidth: 1.5,
              endArrow: true,
              endArrowType: 'vee',
              endArrowSize: 8,
              labelText: (d: any) => d.data?.name ?? '',
              labelFontSize: 10,
              labelFill: '#8A97AB',
              labelBackground: true,
              labelBackgroundFill: '#FFFFFF',
              labelBackgroundOpacity: 0.9,
              labelPadding: [1, 4]
            }
          },
          layout: {
            type: 'dagre',
            rankdir: 'LR',
            nodesep: 32,
            ranksep: 90
          } as any,
          behaviors: [
            'drag-element',
            'drag-canvas',
            'zoom-canvas',
            {
              type: 'hover-activate',
              degree: 1,
              state: 'active',
              inactiveState: 'inactive'
            }
          ],
          plugins: [
            {
              type: 'toolbar',
              position: 'right-top',
              onClick: (item: string) => {
                const graph = graphRef.current;
                switch (item) {
                  case 'zoom-in':
                    graph?.zoomTo(graph?.getZoom() + 0.2);
                    break;
                  case 'zoom-out':
                    graph?.zoomTo(graph?.getZoom() - 0.2);
                    break;
                  case 'auto-fit':
                    graph?.fitView();
                    break;
                }
              },
              getItems: () => {
                return [
                  { id: 'zoom-in', value: 'zoom-in' },
                  { id: 'zoom-out', value: 'zoom-out' },
                  { id: 'auto-fit', value: 'auto-fit' }
                ];
              },
              style: {
                backgroundColor: 'var(--btn-background-color)'
              }
            }
          ]
        }}
      />
    </div>
  );
};

export default Visual;
