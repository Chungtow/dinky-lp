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

import { buildErDiagram } from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/ERDiagram/function';
import { DataSources } from '@/types/RegCenter/data';
import { l } from '@/utils/intl';
import { CompressOutlined, ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Space, Spin, Tooltip } from 'antd';
import { renderMermaid } from 'beautiful-mermaid';
import svgPanZoom from 'svg-pan-zoom';
import React, { useEffect, useMemo, useRef, useState } from 'react';

type VisualProps = {
  tableInfo: Partial<DataSources.Table>;
  relations?: DataSources.TableRelations;
  loading: boolean;
};

/**
 * ER 图可视化视图
 *
 * <p>用 {@code beautiful-mermaid} 把 mermaid erDiagram 渲染为 SVG（布局由 ELK 引擎计算，实体不会重叠、
 * 连线自动绘制），再用 {@code svg-pan-zoom} 提供交互：
 * <ul>
 *     <li>按住左键拖动 =====》 平移整图</li>
 *     <li>滚轮 / 工具栏按钮 =====》 放大、缩小</li>
 *     <li>工具栏「适应视图」 =====》 回到合适比例的全貌（首次渲染亦如此）</li>
 * </ul>
 */
const Visual: React.FC<VisualProps> = (props) => {
  const { tableInfo, relations, loading } = props;

  const [svg, setSvg] = useState<string>('');
  const [errorMsg, setErrorMsg] = useState<string>('');
  const containerRef = useRef<HTMLDivElement>(null);
  const panZoomRef = useRef<any>(null);

  const mermaidCode = useMemo(() => buildErDiagram(tableInfo, relations), [tableInfo, relations]);

  // 渲染：mermaid 源码 -> SVG
  useEffect(() => {
    if (!mermaidCode) {
      setSvg('');
      return;
    }
    let alive = true;
    renderMermaid(mermaidCode, { padding: 24, transparent: true })
      .then((result: string) => {
        if (!alive) {
          return;
        }
        setSvg(result);
        setErrorMsg('');
      })
      .catch((error: Error) => {
        if (!alive) {
          return;
        }
        setSvg('');
        setErrorMsg(error?.message ?? String(error));
      });
    return () => {
      alive = false;
    };
  }, [mermaidCode]);

  // 交互：平移 / 缩放 / 适应视图
  useEffect(() => {
    const svgElement = containerRef.current?.querySelector('svg');
    if (!svgElement) {
      return;
    }
    // 去掉渲染产物自带的固定宽高（它就是“无形遮罩”的来源：SVG 视口只有原图大小，
    // 平移/缩放被限制在原图区域内，超出即被裁剪）。改为铺满容器，保留 viewBox 交给 svg-pan-zoom 自适应。
    svgElement.removeAttribute('width');
    svgElement.removeAttribute('height');
    svgElement.style.width = '100%';
    svgElement.style.height = '100%';
    svgElement.style.display = 'block';
    // 让表名/字段名等文字可选中复制（容器为 user-select: none 避免平移误选，此处对文字单独放开，
    // 且不影响平移：平移由 svg-pan-zoom 处理，与原生选中可同时进行）
    const styleElement = document.createElementNS('http://www.w3.org/2000/svg', 'style');
    styleElement.textContent = 'text, tspan { user-select: text; -webkit-user-select: text; cursor: text; }';
    svgElement.appendChild(styleElement);
    const instance = svgPanZoom(svgElement as unknown as SVGSVGElement, {
      zoomEnabled: true,
      panEnabled: true,
      controlIconsEnabled: false,
      // 关键：默认 true 时 svg-pan-zoom 会在 mousedown 上 preventDefault，直接屏蔽原生文本选择
      preventMouseEventsDefault: false,
      fit: true,
      center: true,
      minZoom: 0.1,
      maxZoom: 10,
      zoomScaleSensitivity: 0.25,
      dblClickZoomEnabled: true
    });
    panZoomRef.current = instance;
    return () => {
      instance.destroy();
      panZoomRef.current = null;
    };
  }, [svg]);

  const handleZoomIn = () => panZoomRef.current?.zoomIn();
  const handleZoomOut = () => panZoomRef.current?.zoomOut();
  const handleFit = () => {
    panZoomRef.current?.fit();
    panZoomRef.current?.center();
  };

  // 平移始终可用（不再按拖拽起点禁用），文字能否选中交由 CSS 控制，两者互不排斥

  if (loading) {
    return <Spin spinning style={{ width: '100%', padding: '48px 0' }} />;
  }

  if (!mermaidCode) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={l('rc.ds.erdiagram.empty')} />;
  }

  return (
    <div
      style={{
        position: 'relative',
        width: '100%',
        height: '62vh',
        border: '1px solid var(--border-color)',
        borderRadius: 6,
        overflow: 'hidden',
        background: 'var(--background-color)'
      }}
    >
      <Space style={{ position: 'absolute', top: 8, right: 8, zIndex: 10 }}>
        <Tooltip title={l('rc.ds.erdiagram.zoomIn')}>
          <Button size={'small'} icon={<ZoomInOutlined />} onClick={handleZoomIn} />
        </Tooltip>
        <Tooltip title={l('rc.ds.erdiagram.zoomOut')}>
          <Button size={'small'} icon={<ZoomOutOutlined />} onClick={handleZoomOut} />
        </Tooltip>
        <Tooltip title={l('rc.ds.erdiagram.fitView')}>
          <Button size={'small'} icon={<CompressOutlined />} onClick={handleFit} />
        </Tooltip>
      </Space>

      {errorMsg ? (
        <div style={{ padding: 16 }}>
          <Alert type={'error'} showIcon message={l('rc.ds.erdiagram.renderError')} description={errorMsg} />
        </div>
      ) : (
        <div
          ref={containerRef}
          style={{ width: '100%', height: '100%', userSelect: 'none' }}
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      )}
      <span style={{ position: 'absolute', left: 12, bottom: 8, zIndex: 10, fontSize: 12, opacity: 0.5 }}>
        {l('rc.ds.erdiagram.selectHint')}
      </span>
    </div>
  );
};

export default Visual;
