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

import { l } from '@/utils/intl';
import { SearchOutlined } from '@ant-design/icons';
import { ProColumns } from '@ant-design/pro-table/es/typing';
import { Button, Input, InputRef, message, Popover, Space } from 'antd';
import { ColumnType } from 'antd/es/table';
import { FilterConfirmProps } from 'antd/es/table/interface';
import React, { useRef, useState } from 'react';

/** 列宽上下限（对齐 pgconsole 的 80~400 夹取，这里上限收紧到 360 以免宽表横向滚动过远） */
const COLUMN_MIN_WIDTH = 80;
const COLUMN_MAX_WIDTH = 360;
/** 估算宽度时预留：左右 padding + 排序/筛选图标 */
const COLUMN_PADDING = 44;
/** 类型推断与宽度估算的采样行数 */
const SAMPLE_SIZE = 200;

/** 看起来像"标识符"的列名：虽然值是数字，但右对齐反而不利于扫读（手机号/订单号…） */
const IDENTIFIER_COLUMN_RE =
  /(^|_)(id|code|no)$|phone|mobile|tel|zip|postcode|编号|电话|邮编|身份证|卡号/i;
/** 纯数字字符串（不允许前导 0，避免把 "007" / "13800138000" 当成数值） */
const NUMERIC_TEXT_RE = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;
/** 粗略判定 CJK 字符（宽度按 12px 估，ASCII 按 7px 估） */
const CJK_RE = /[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]/;

/**
 * 估算一段文本的像素宽度。
 * 结果集没有 JDBC 类型信息，只能用字符宽度启发式估算（12px 字号下 CJK≈12px、ASCII≈7px）。
 */
export const estimateTextWidth = (text: string): number => {
  let width = 0;
  for (const char of text) {
    width += CJK_RE.test(char) ? 12 : 7;
  }
  return width;
};

/** 采样估算列宽，夹取 [80, 360] */
export const estimateColumnWidth = (header: string, rows: any[], dataIndex: string): number => {
  let maxWidth = estimateTextWidth(String(header ?? ''));
  const sample = (rows ?? []).slice(0, SAMPLE_SIZE);
  for (const row of sample) {
    const value = row?.[dataIndex];
    if (value === null || value === undefined || value === '') {
      continue;
    }
    const width = estimateTextWidth(String(value));
    if (width > maxWidth) {
      maxWidth = width;
    }
    if (maxWidth >= COLUMN_MAX_WIDTH) {
      break;
    }
  }
  return Math.min(Math.max(maxWidth + COLUMN_PADDING, COLUMN_MIN_WIDTH), COLUMN_MAX_WIDTH);
};

/**
 * 判定数值列：采样行里所有非空值都是有限数字（或纯数字字符串），且列名不像标识符。
 */
export const isNumericColumn = (header: string, rows: any[], dataIndex: string): boolean => {
  if (IDENTIFIER_COLUMN_RE.test(header ?? '')) {
    return false;
  }
  let seen = 0;
  const sample = (rows ?? []).slice(0, SAMPLE_SIZE);
  for (const row of sample) {
    const value = row?.[dataIndex];
    if (value === null || value === undefined || value === '') {
      continue;
    }
    if (typeof value === 'number') {
      if (!Number.isFinite(value)) {
        return false;
      }
    } else if (typeof value === 'string') {
      if (!NUMERIC_TEXT_RE.test(value.trim())) {
        return false;
      }
    } else {
      return false;
    }
    seen += 1;
  }
  return seen > 0;
};

/**
 * 取单元格的"可复制值"。
 *
 * <p>空值占位（`null` / `(empty)`）会污染 `textContent`，因此占位元素上带 `data-copy=""`，
 * 这里优先读它 —— 保证「复制当前页」与「单元格复制」拿到的都是**原始值**。
 */
export const getCellCopyValue = (cell: Element): string => {
  const rawNode = cell.querySelector('[data-copy]');
  if (rawNode) {
    return rawNode.getAttribute('data-copy') ?? '';
  }
  return (cell.textContent ?? '').replace(/\s+/g, ' ').trim();
};

/** 写剪贴板：优先 Clipboard API，内网 HTTP 下降级 textarea + execCommand */
export const copyTextToClipboard = async (text: string): Promise<void> => {
  try {
    if (!navigator?.clipboard) {
      throw new Error('clipboard api unavailable');
    }
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
  }
};

/**
 * 安全的关键词高亮。
 *
 * <p>**不再使用** `@ant-design/pro-layout` 内部的 `Highlight`：它的实现是
 * `while (matchText.length) { matchText = matchText.slice(matchLength) }`，
 * 空关键词时 `matchLength === 0` 会死循环（列头搜索点「重置」导致页面崩溃的根因）。
 * 这里空关键词直接返回原文，并用 `String.split(捕获组)` 实现，不存在零长度循环。
 */
export const HighlightText: React.FC<{ text: string; keyword?: string }> = ({ text, keyword }) => {
  const keywordTrimmed = (keyword ?? '').trim();
  if (!keywordTrimmed) {
    return <>{text}</>;
  }
  const escaped = keywordTrimmed.replace(/[-[\]/{}()*+?.\\^$|]/g, '\\$&');
  const parts = text.split(new RegExp(`(${escaped})`, 'gi'));
  return (
    <>
      {parts.map((part, index) =>
        // split 带捕获组时，奇数下标即命中片段
        index % 2 === 1 ? (
          <span key={index} className={'ds-cell-hl'}>
            {part}
          </span>
        ) : (
          part
        )
      )}
    </>
  );
};

/** 单元格复制：双击触发，toast 提示 */
const handleCellCopy = async (text: string) => {
  await copyTextToClipboard(text);
  message.success(l('pages.datastudio.label.result.copy.cell.success'));
};

/**
 * 结果表格列定义：列头带排序 + 搜索；单元格带空值弱化、超长省略 + hover 全文面板、双击复制。
 * 每个表格实例各持一份搜索状态（提升到一级 Tabs 后需按表格隔离）。
 */
export const useResultColumns = () => {
  const [searchText, setSearchText] = useState('');
  const [searchedColumn, setSearchedColumn] = useState('');
  const searchInput = useRef<InputRef>(null);

  const handleReset = (clearFilters: () => void) => {
    clearFilters();
    setSearchText('');
    // 必须一起清：否则 render 仍走高亮分支且关键词为空串（旧版「重置」崩溃的根因）
    setSearchedColumn('');
  };

  const handleSearch = (
    selectedKeys: string[],
    confirm: (param?: FilterConfirmProps) => void,
    dataIndex: string
  ) => {
    confirm();
    if (selectedKeys.length > 0) {
      setSearchText(selectedKeys[0]);
      setSearchedColumn(dataIndex.toString());
    } else {
      setSearchText('');
      setSearchedColumn('');
    }
  };

  const getColumnSearchProps = (dataIndex: string): ColumnType<any> => ({
    filterDropdown: ({ setSelectedKeys, selectedKeys, confirm, clearFilters }) => (
      <div
        className={'ds-column-search-dropdown'}
        style={{ padding: 8 }}
        onKeyDown={(e) => e.stopPropagation()}
      >
        <Input
          ref={searchInput}
          placeholder={`Search ${dataIndex}`}
          value={selectedKeys[0]}
          onChange={(e) => setSelectedKeys(e.target.value ? [e.target.value] : [])}
          onPressEnter={() => handleSearch(selectedKeys as string[], confirm, dataIndex)}
          style={{ marginBottom: 8, display: 'block' }}
        />
        <Space>
          <Button
            type='primary'
            onClick={() => handleSearch(selectedKeys as string[], confirm, dataIndex)}
            icon={<SearchOutlined />}
            size='small'
            style={{ width: 90 }}
          >
            {l('button.search')}
          </Button>
          <Button
            onClick={() => clearFilters && handleReset(clearFilters)}
            size='small'
            style={{ width: 90 }}
          >
            {l('button.reset')}
          </Button>
        </Space>
      </div>
    ),
    filterIcon: (filtered: boolean) => (
      <SearchOutlined style={{ color: filtered ? 'var(--primary-color)' : undefined }} />
    ),
    onFilter: (value, record) =>
      String(record[dataIndex] ?? '')
        .toLowerCase()
        .includes(String(value ?? '').toLowerCase()),
    onFilterDropdownOpenChange: (visible) => {
      if (visible) {
        setTimeout(() => searchInput.current?.select(), 100);
      }
    }
  });

  /** 单元格内容：空值弱化 / 关键词高亮 / 超宽时挂 hover 全文面板 / 双击复制 */
  const renderCell = (value: any, columnWidth: number, keyword: string, matched: boolean) => {
    if (value === null || value === undefined) {
      return (
        <span
          className={'ds-cell-placeholder ds-cell-null'}
          data-copy={''}
          onDoubleClick={() => handleCellCopy('')}
        >
          {'null'}
        </span>
      );
    }
    if (value === '') {
      return (
        <span
          className={'ds-cell-placeholder ds-cell-empty'}
          data-copy={''}
          onDoubleClick={() => handleCellCopy('')}
        >
          {'(empty)'}
        </span>
      );
    }

    const text = String(value);
    const needFullTextPanel = estimateTextWidth(text) > columnWidth - COLUMN_PADDING;
    const content = matched ? <HighlightText text={text} keyword={keyword} /> : text;

    const cell = (
      <span
        className={'ds-cell-text'}
        onDoubleClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          handleCellCopy(text);
        }}
      >
        {content}
      </span>
    );

    if (!needFullTextPanel) {
      return cell;
    }
    return (
      <Popover
        trigger={'hover'}
        placement={'topLeft'}
        mouseEnterDelay={0.3}
        overlayClassName={'ds-cell-popover'}
        content={<div className={'ds-cell-fulltext'}>{text}</div>}
      >
        {cell}
      </Popover>
    );
  };

  const getColumns = (columns: string[] = [], rows: any[] = []) => {
    return columns?.map((item) => {
      const width = estimateColumnWidth(item, rows, item);
      const numeric = isNumericColumn(item, rows, item);
      return {
        title: item,
        dataIndex: item,
        width,
        align: numeric ? 'right' : 'left',
        ellipsis: { showTitle: false },
        sorter: (a: any, b: any) =>
          String(a[item] ?? '').localeCompare(String(b[item] ?? ''), undefined, {
            numeric: true
          }),
        ...getColumnSearchProps(item),
        render: (text: any) =>
          renderCell(text, width, searchText, searchedColumn === item && !!searchText)
      };
    }) as ProColumns[];
  };

  return { getColumns };
};
