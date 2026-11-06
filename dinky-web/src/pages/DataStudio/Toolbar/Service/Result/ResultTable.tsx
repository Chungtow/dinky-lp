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

import { transformTableDataToCsv } from '@/utils/function';
import { l } from '@/utils/intl';
import { ProTable } from '@ant-design/pro-components';
import { getInsights } from '@antv/ava';
import { InsightCard } from '@antv/ava-react';
import type { Datum, InsightsResult } from '@antv/ava/lib/insight/types';
import { Drawer, message, Tabs, TabsProps } from 'antd';
import React, {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useTransition
} from 'react';
import { getCellCopyValue, stringifyCellValue, useResultColumns } from './columns';
import ResultToolRail from './ResultToolRail';
import { ResultColumnsState, ResultData, ResultEntry } from './useResultEntries';

type ResultTableProps = {
  entry: ResultEntry;
  loading?: boolean;
  onRefresh: () => void;
  onColumnsStateChange: (id: number, columnsState: ResultColumnsState) => void;
};

const tableKeyOf = (data: ResultData, index: number) => data.tableName ?? String(index);

/** 分页器与表体的间距（与 `index.less` 中 `.ds-result-pane .ant-table-pagination` 的 margin-top 保持一致） */
const PAGER_GAP = 6;

/**
 * 表体可视高度（`scroll.y`）的实测值。
 *
 * <p>为什么必须实测而不能写死/用 `calc(100vh - Npx)`：结果面板被 dock 布局包着——
 * ① 分隔条可拖拽改变面板高度；② 窗口缩放；③ 每页条数切换会改变分页器高度；
 * ④ 全屏是另一套容器。只有量当前盒子的实际高度，才在四种场景下都成立。
 *
 * <p>为什么量 `.ant-pro-card-body` 而不是 wrap 本身：卡片的 6px 上下 padding 在
 * `clientHeight` 之内，直接减会让表体多出 12px 而挤出分页器。
 *
 * <p>为什么要 scope 到 `.ant-tabs-tabpane-active`：mock-sink 多表时 antd 会把非激活
 * 的 pane 留在 DOM 里（`display:none`，高度 0），直接查 `.ant-table-header` 会量到隐藏表。
 */
const useResultBodyHeight = (
  wrapRef: React.RefObject<HTMLDivElement>,
  isSingle: boolean,
  fullscreen: boolean
) => {
  const [bodyHeight, setBodyHeight] = useState<number>();
  const [paneHeight, setPaneHeight] = useState<number>();

  const measure = useCallback(() => {
    const wrap = wrapRef.current;
    if (!wrap) {
      return;
    }

    /**
     * 结果 pane 自己是 `height:100%`，但父级 `.ant-tabs-tabpane` 高度是 auto（antd 不设高），
     * 100% 对 auto 父级无效 ⇒ 面板会长到内容高度，把外层 `.ant-tabs-content`（`overflow-y:auto`）
     * 顶出一条滚动条。这里取**最外层** `.ant-tabs-content` 的实高把 pane 钉住：
     * 它是 dock 宫格内高度确定的滚动容器，`clientHeight` 不随内容变化（无反馈环）。
     * 全屏时 pane 是 `position:fixed; inset:0`，高度由视口决定，不需要钉。
     */
    if (!fullscreen) {
      let outermost: HTMLElement | undefined;
      let node: HTMLElement | null = wrap.parentElement;
      while (node) {
        if (node.classList?.contains('ant-tabs-content')) {
          outermost = node;
        }
        node = node.parentElement;
      }
      const available = outermost?.clientHeight;
      if (available && available > 0) {
        setPaneHeight((prev) => (prev === available ? prev : available));
      }
    }

    const scope = (
      isSingle
        ? wrap
        : (wrap.querySelector('.ant-tabs-tabpane-active') as HTMLElement | null) || wrap
    ) as HTMLElement;
    const cardBody = (scope.querySelector('.ant-pro-card-body') as HTMLElement | null) || scope;
    const style = window.getComputedStyle(cardBody);
    const innerHeight =
      cardBody.clientHeight -
      (parseFloat(style.paddingTop) || 0) -
      (parseFloat(style.paddingBottom) || 0);
    if (innerHeight <= 0) {
      // 面板被折叠/隐藏（例如切到别的标签页）时不要把它写成 0 或 120
      return;
    }
    // scroll.y 生效后 antd 会渲染独立的 .ant-table-header；首次渲染前退化为 thead
    const header = scope.querySelector('.ant-table-header, .ant-table-thead') as HTMLElement | null;
    const headerHeight = header?.offsetHeight || 39;
    const pager = scope.querySelector('.ant-table-pagination') as HTMLElement | null;
    const pagerHeight = pager ? pager.offsetHeight + PAGER_GAP : 0;
    // 下限 120：面板被拖到极矮时不塌陷成 0；向下取整避免多 1px 挤出外层滚动条
    const next = Math.max(Math.floor(innerHeight - headerHeight - pagerHeight), 120);
    setBodyHeight((prev) => (prev === next ? prev : next));
  }, [fullscreen, isSingle, wrapRef]);

  // 首帧之前就量一次，避免"先整表铺开、再塌成滚动区"的闪动
  useLayoutEffect(() => {
    measure();
  });

  useEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) {
      return;
    }
    // dock 拖拽 / 窗口缩放 / 分页尺寸切换都会改变盒子尺寸，统一靠 ResizeObserver + rAF 收敛
    const ro = new ResizeObserver(() => requestAnimationFrame(measure));
    ro.observe(wrap);
    window.addEventListener('resize', measure);
    // 首屏字体/滚动条占位就绪后再校准一次
    const timer = window.setTimeout(measure, 120);
    return () => {
      ro.disconnect();
      window.removeEventListener('resize', measure);
      window.clearTimeout(timer);
    };
  }, [measure, wrapRef]);

  return { bodyHeight, paneHeight };
};

/**
 * 单个结果集的渲染：右侧竖条 + 数据表格。
 *
 * <p>mock-sink 场景 `dataList` 会有多项（每项一张表），此时内层按表名分 Tabs，
 * 竖条的导出 / AVA / 列设置作用于**当前选中的表**（与改造前每表各自工具栏的语义一致）。
 */
const ResultTable: React.FC<ResultTableProps> = (props) => {
  const { entry, loading, onRefresh, onColumnsStateChange } = props;
  const { dataList, columnsState } = entry;
  const { getColumns } = useResultColumns();
  const tableWrapRef = useRef<HTMLDivElement>(null);

  const [innerKey, setInnerKey] = useState<string>(() =>
    dataList.length > 0 ? tableKeyOf(dataList[0], 0) : ''
  );
  const [fullscreen, setFullscreen] = useState<boolean>(false);
  const [openAVA, setOpenAVA] = useState<boolean>(false);
  const [avaResult, setAvaResult] = useState<InsightsResult>();
  const [isPending, startTransition] = useTransition();

  // 非 mock-sink 的单表结果：直接渲染，不做内层 Tabs
  const isSingle = dataList.length <= 1 && !dataList[0]?.tableName;

  // 表体可视高度：`scroll.y` 的唯一用途是"表头固定 + 表体滚"共处一个滚动容器。
  // 分页器出现/消失、每页条数切换都伴随一次渲染，hook 内部每次渲染后都会重新校准
  const { bodyHeight, paneHeight } = useResultBodyHeight(tableWrapRef, isSingle, fullscreen);

  const activeIndex = useMemo(() => {
    const index = dataList.findIndex((data, idx) => tableKeyOf(data, idx) === innerKey);
    return index >= 0 ? index : 0;
  }, [dataList, innerKey]);
  const activeData = dataList[activeIndex] ?? dataList[0];

  // CSV 只在数据变化时生成一次，避免每次渲染都新建 Blob
  const csvUrl = useMemo(() => {
    if (!activeData?.columns?.length) {
      return undefined;
    }
    const _utf = '\uFEFF';
    // 导出与单元格展示保持同一套取值口径：结构化值序列化成 JSON，
    // 否则 CSV 里同样是 `[object Object]`（导出的是原文，不带显示层占位符）
    const csvRows = (activeData.rowData ?? []).map((row: any) => {
      const formatted: Record<string, string> = {};
      activeData.columns.forEach((column: string) => {
        formatted[column] = stringifyCellValue(row?.[column]);
      });
      return formatted;
    });
    const csvDataBlob = new Blob([_utf + transformTableDataToCsv(activeData.columns, csvRows)], {
      type: 'text/csv'
    });
    return URL.createObjectURL(csvDataBlob);
  }, [activeData]);

  useEffect(() => {
    return () => {
      if (csvUrl) {
        URL.revokeObjectURL(csvUrl);
      }
    };
  }, [csvUrl]);

  // 全屏下 Esc 退出
  useEffect(() => {
    if (!fullscreen) {
      return;
    }
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setFullscreen(false);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [fullscreen]);

  const handleAVA = useCallback((data: ResultData) => {
    setOpenAVA(true);
    startTransition(() => {
      setAvaResult(getInsights((data?.rowData ?? []) as Datum[], { visualization: true }));
    });
  }, []);

  const handleCloseAva = useCallback(() => setOpenAVA(false), []);

  /**
   * 滚轮转发：结果面板只剩表体一个纵向滚动容器（外层 `overflow:hidden`），
   * 鼠标停在右侧功能竖条 / 分页器 / 表头空白处时浏览器不会再滚表体，
   * 这里把 `deltaY` 转给 `.ant-table-body`，做到"在哪儿滚都在滚表格"。
   *
   * <p>指针已经在表体内（含横向滚动条区域）时不接管，交给浏览器原生滚动，
   * 否则会出现"滚动被抢、滚动手感变卡"。
   */
  const handleWheel = useCallback(
    (e: React.WheelEvent<HTMLDivElement>) => {
      const wrap = tableWrapRef.current;
      if (!wrap) {
        return;
      }
      const scope = (
        isSingle
          ? wrap
          : (wrap.querySelector('.ant-tabs-tabpane-active') as HTMLElement | null) || wrap
      ) as HTMLElement;
      const body = scope.querySelector('.ant-table-body') as HTMLElement | null;
      if (!body || body.contains(e.target as Node)) {
        return;
      }
      if (body.scrollHeight <= body.clientHeight) {
        return;
      }
      body.scrollTop += e.deltaY;
    },
    [isSingle]
  );

  /**
   * 复制当前页表格内容（TSV，可直接粘进 Excel）。
   *
   * <p>直接读 DOM 而不是读 dataSource：DOM 里只有当前页渲染的行，且天然跟随当前排序、
   * 列筛选与列显隐（隐藏列不在 DOM 里），与用户"看到什么就复制什么"的预期一致。
   */
  const handleCopyPage = useCallback(async () => {
    const root = tableWrapRef.current;
    if (!root) {
      return;
    }
    const scope = isSingle ? root : (root.querySelector('.ant-tabs-tabpane-active') ?? root);
    if (!scope.querySelector('.ant-table-tbody')) {
      return;
    }
    // 启用 scroll.y 后 antd 把表头和表体拆成两张 table（`.ant-table-header` / `.ant-table-body`），
    // 表体那张没有 thead ⇒ 表头必须单独取；未启用时退化为 `.ant-table-thead` 里的 th。
    // 排除 `.ant-table-cell-scrollbar`：它是为纵向滚动条预留的占位格，不是数据列。
    const headers = Array.from(
      scope.querySelectorAll('.ant-table-header thead th, .ant-table-thead th')
    )
      .filter((th) => !th.classList.contains('ant-table-cell-scrollbar'))
      .map(getCellCopyValue);
    const rows = Array.from(scope.querySelectorAll('tbody tr.ant-table-row')).map((tr) =>
      Array.from(tr.querySelectorAll('td'))
        .filter((td) => !td.classList.contains('ant-table-cell-scrollbar'))
        .map(getCellCopyValue)
    );
    if (headers.length === 0 && rows.length === 0) {
      return;
    }
    const tsv = [headers.join('\t'), ...rows.map((row) => row.join('\t'))].join('\n');
    try {
      if (!navigator?.clipboard) {
        throw new Error('clipboard api unavailable');
      }
      await navigator.clipboard.writeText(tsv);
    } catch (e) {
      const textarea = document.createElement('textarea');
      textarea.value = tsv;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
    message.success(l('pages.datastudio.label.result.copy.success', '', { rowCount: rows.length }));
  }, [isSingle]);

  const renderTable = (data: ResultData) => {
    const columnsDef = getColumns(data?.columns, data?.rowData);
    // 横向滚动基准 = **可见列**宽度之和（列设置里被隐藏的列不再计入，
    // 否则隐藏几列后仍会多出一段无效的横向滚动空白）
    const visibleWidth = columnsDef.reduce((sum: number, column: any) => {
      if (columnsState?.[column.dataIndex as string]?.show === false) {
        return sum;
      }
      return sum + (column.width ?? 120);
    }, 0);
    return (
      <ProTable
        className={'datastudio-theme'}
        cardBordered
        columns={columnsDef}
        size='small'
        /**
         * 单一滚动容器（D12）：`y` 由 ResizeObserver 实测。
         * - 表体 `.ant-table-body` 自己滚 ⇒ 表头不在滚动容器内，天然固定；
         * - 水平滚动条贴在表体内底边 ⇒ 永远停在可视区底部，不需要滚到表格末尾；
         * - 分页器在表体之外 ⇒ 高度扣掉后常驻底部（行数少时 antd 不渲染，自然不占位）。
         *
         * 不能再配 `sticky`：与 `scroll.y` 语义重复，且它会把表头克隆一份到
         * `.ant-table-sticky-holder`（v2 的"复制当前页"曾为绕开它而取 tbody）。
         */
        scroll={{ x: Math.max(visibleWidth, 80), y: bodyHeight }}
        dataSource={(data?.rowData ?? []).map((item: any, index: number) => ({
          ...item,
          key: index
        }))}
        options={false}
        toolBarRender={false}
        cardProps={{ bodyStyle: { padding: '6px 8px' } }}
        columnsState={{
          value: columnsState,
          onChange: (map) => onColumnsStateChange(entry.id, map as ResultColumnsState)
        }}
        search={false}
        loading={loading}
        pagination={{
          showSizeChanger: true,
          defaultPageSize: 50,
          size: 'small',
          hideOnSinglePage: true
        }}
      />
    );
  };

  const innerItems: TabsProps['items'] = dataList.map((data, index) => ({
    key: tableKeyOf(data, index),
    label: data.tableName,
    children: renderTable(data)
  }));

  const containerStyle: React.CSSProperties = fullscreen
    ? {
        // 全屏：fixed inset 给到确定高度，与面板同一套高度链，无需另行钉高
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        padding: 8,
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--main-background-color)'
      }
    : {
        // 非全屏：由 hook 实测外层滚动容器高度后钉住，避免 `height:100%` 落到 auto 父级上失效
        height: paneHeight ?? '100%',
        minHeight: 0,
        display: 'flex',
        flexDirection: 'column'
      };

  return (
    // ds-result-pane：结果 pane 自己不再滚（overflow:hidden），纵向滚动只发生在表体，
    // 从而消除"右侧竖条右边那条滚动整个结果页"的滚动条；滚轮由 handleWheel 转发给表体
    <div className={'ds-result-pane'} style={containerStyle} onWheel={handleWheel}>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'row' }}>
        <div style={{ flex: 1, minWidth: 0 }} ref={tableWrapRef}>
          {isSingle ? (
            renderTable(activeData)
          ) : (
            <Tabs
              size={'small'}
              activeKey={tableKeyOf(activeData, activeIndex)}
              items={innerItems}
              onChange={setInnerKey}
              tabBarStyle={{ marginBottom: '5px' }}
            />
          )}
        </div>
        <div style={{ width: 32, flex: 'none' }}>
          <ResultToolRail
            loading={loading}
            csvUrl={csvUrl}
            columns={activeData?.columns ?? []}
            columnsState={columnsState}
            fullscreen={fullscreen}
            onRefresh={onRefresh}
            onAVA={() => handleAVA(activeData)}
            onCopyPage={handleCopyPage}
            onToggleFullscreen={() => setFullscreen((prev) => !prev)}
            onColumnsStateChange={(state) => onColumnsStateChange(entry.id, state)}
          />
        </div>
      </div>
      <Drawer
        open={openAVA}
        loading={isPending}
        width={'70%'}
        onClose={handleCloseAva}
        destroyOnClose
      >
        <div key='plot' style={{ flex: 5, height: '100%' }}>
          {avaResult?.insights &&
            avaResult.insights.map((insight, index) => {
              return (
                <InsightCard
                  insightInfo={insight}
                  key={index}
                  visualizationOptions={{ lang: 'zh-CN' }}
                />
              );
            })}
        </div>
      </Drawer>
    </div>
  );
};

export default ResultTable;
