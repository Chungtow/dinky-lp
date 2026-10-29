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

import { DeleteOutlined } from '@ant-design/icons';
import { Graph, Node } from '@antv/x6';
import { register } from '@antv/x6-react-shape';
import { Button, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { DataXField, DataXMapping } from './dataxJson';

const NODE_WIDTH = 200;
const NODE_HEIGHT = 40;
const GAP_X = 220; // 左右列间距
const ROW_HEIGHT = 48; // 行间距
const TOTAL_WIDTH = NODE_WIDTH * 2 + GAP_X + 40; // 画布固定宽度（容纳左右两列 + 连线）

/**
 * 字段节点 React 组件（x6-react-shape 渲染）。
 */
const FieldNode = (props: { node: Node }) => {
  const { node } = props;
  const data = node.getData();
  const field: DataXField = data?.field;
  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 8px',
        boxSizing: 'border-box',
        background: data?.side === 'source' ? '#f0f5ff' : '#f6ffed',
        border: '1px solid #d9d9d9',
        borderRadius: 4,
        overflow: 'hidden'
      }}
    >
      <Tooltip title={field?.comment || field?.name || ''}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, minWidth: 0, flex: 1 }}>
          <span
            style={{
              fontSize: 12,
              fontWeight: 500,
              color: '#262626',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis'
            }}
          >
            {field?.name}
          </span>
          <span
            style={{
              fontSize: 11,
              color: '#8c8c8c',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis'
            }}
          >
            {field?.type}
          </span>
        </div>
      </Tooltip>
      <Button
        size='small'
        type='text'
        danger
        icon={<DeleteOutlined />}
        onMouseDown={(e) => e.stopPropagation()}
        onClick={(e) => {
          e.stopPropagation();
          data?.onDelete?.(field?.name);
        }}
      />
    </div>
  );
};

// 注册字段节点 shape（模块级，仅一次）
register({
  shape: 'datax-field-node',
  width: NODE_WIDTH,
  height: NODE_HEIGHT,
  component: FieldNode,
  ports: {
    groups: {
      out: {
        position: 'right',
        attrs: {
          circle: { r: 5, magnet: true, stroke: '#1890ff', strokeWidth: 1, fill: '#fff' }
        }
      },
      in: {
        position: 'left',
        attrs: {
          circle: { r: 5, magnet: true, stroke: '#52c41a', strokeWidth: 1, fill: '#fff' }
        }
      }
    }
  }
});

export interface FieldMappingProps {
  sourceFields: DataXField[];
  targetFields: DataXField[];
  mappings: DataXMapping[];
  onMappingsChange: (mappings: DataXMapping[]) => void;
  onDeleteSourceField: (fieldName: string) => void;
  onDeleteTargetField: (fieldName: string) => void;
}

/**
 * 字段映射连线组件：左右两列字段节点，通过拖拽连线建立/修改映射。
 */
const FieldMapping = (props: FieldMappingProps) => {
  const { sourceFields, targetFields, mappings, onMappingsChange, onDeleteSourceField, onDeleteTargetField } =
    props;

  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const [graphHeight, setGraphHeight] = useState(400);

  // 用 ref 保存最新值，供事件回调（闭包）访问
  const mappingsRef = useRef(mappings);
  const onMappingsChangeRef = useRef(onMappingsChange);
  const onDeleteSourceRef = useRef(onDeleteSourceField);
  const onDeleteTargetRef = useRef(onDeleteTargetField);
  mappingsRef.current = mappings;
  onMappingsChangeRef.current = onMappingsChange;
  onDeleteSourceRef.current = onDeleteSourceField;
  onDeleteTargetRef.current = onDeleteTargetField;

  // 初始化 graph（仅一次）
  useEffect(() => {
    if (!containerRef.current) {
      return;
    }
    const graph = new Graph({
      container: containerRef.current,
      width: TOTAL_WIDTH,
      height: 400,
      grid: false,
      panning: false,
      mousewheel: false,
      background: { color: '#fafafa' },
      interacting: {
        nodeMovable: false
      },
      connecting: {
        snap: true,
        allowBlank: false,
        allowLoop: false,
        allowNode: false,
        allowEdge: false,
        highlight: true,
        connectionPoint: 'boundary',
        sourceAnchor: 'right',
        targetAnchor: 'left',
        validateMagnet({ magnet }) {
          return (
            magnet.getAttribute('port-group') === 'out' ||
            magnet.getAttribute('port-group') === 'in'
          );
        },
        validateConnection({ sourceCell, targetCell }) {
          if (!sourceCell || !targetCell || sourceCell.id === targetCell.id) {
            return false;
          }
          return (
            sourceCell.getData()?.side === 'source' && targetCell.getData()?.side === 'target'
          );
        }
      }
    });

    // 建立连线 → 更新映射（一个目标字段只保留一条映射）
    graph.on('edge:connected', ({ edge }) => {
      const sourceCell = edge.getSourceCell();
      const targetCell = edge.getTargetCell();
      if (!sourceCell || !targetCell) {
        return;
      }
      const sourceField = sourceCell.getData()?.field?.name;
      const targetField = targetCell.getData()?.field?.name;
      if (!sourceField || !targetField) {
        return;
      }
      const next = mappingsRef.current.filter((m) => m.targetField !== targetField);
      next.push({ sourceField, targetField });
      onMappingsChangeRef.current(next);
    });

    // 点击边 → 删除映射
    graph.on('edge:click', ({ edge }) => {
      const sourceCell = edge.getSourceCell();
      const targetCell = edge.getTargetCell();
      if (!sourceCell || !targetCell) {
        return;
      }
      const sourceField = sourceCell.getData()?.field?.name;
      const targetField = targetCell.getData()?.field?.name;
      const next = mappingsRef.current.filter(
        (m) => !(m.sourceField === sourceField && m.targetField === targetField)
      );
      onMappingsChangeRef.current(next);
    });

    graphRef.current = graph;
    return () => {
      graph.dispose();
      graphRef.current = null;
    };
  }, []);

  // 字段/映射变化时重建节点与边
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) {
      return;
    }

    graph.clearCells();

    const sourceX = 10;
    const targetX = sourceX + NODE_WIDTH + GAP_X;

    sourceFields.forEach((field, index) => {
      graph.addNode({
        id: `src:${field.name}`,
        shape: 'datax-field-node',
        x: sourceX,
        y: 10 + index * ROW_HEIGHT,
        data: {
          side: 'source',
          field,
          onDelete: (name: string) => onDeleteSourceRef.current(name)
        },
        ports: { items: [{ id: 'out', group: 'out' }] }
      });
    });

    targetFields.forEach((field, index) => {
      graph.addNode({
        id: `tgt:${field.name}`,
        shape: 'datax-field-node',
        x: targetX,
        y: 10 + index * ROW_HEIGHT,
        data: {
          side: 'target',
          field,
          onDelete: (name: string) => onDeleteTargetRef.current(name)
        },
        ports: { items: [{ id: 'in', group: 'in' }] }
      });
    });

    mappings.forEach((m) => {
      const sourceNode = graph.getCellById(`src:${m.sourceField}`);
      const targetNode = graph.getCellById(`tgt:${m.targetField}`);
      if (sourceNode && targetNode) {
        graph.addEdge({
          source: { cell: `src:${m.sourceField}`, port: 'out' },
          target: { cell: `tgt:${m.targetField}`, port: 'in' },
          attrs: {
            line: {
              stroke: '#1890ff',
              strokeWidth: 1.5,
              targetMarker: { name: 'block', width: 8, height: 6 }
            }
          }
        });
      }
    });

    // 动态调整画布高度
    const rows = Math.max(sourceFields.length, targetFields.length, 1);
    const height = 10 + rows * ROW_HEIGHT + 10;
    graph.resize(TOTAL_WIDTH, height);
    setGraphHeight(height);
  }, [sourceFields, targetFields, mappings]);

  return (
    <div style={{ width: '100%', height: '100%', overflow: 'auto' }}>
      <div ref={containerRef} style={{ width: TOTAL_WIDTH, height: graphHeight }} />
    </div>
  );
};

export default FieldMapping;
