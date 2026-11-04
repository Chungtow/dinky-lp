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
import { Highlight } from '@ant-design/pro-layout/es/components/Help/Search';
import { ProColumns } from '@ant-design/pro-table/es/typing';
import { Button, Input, InputRef, Space } from 'antd';
import { ColumnType } from 'antd/es/table';
import { FilterConfirmProps } from 'antd/es/table/interface';
import { useRef, useState } from 'react';

/**
 * 结果表格列定义：列头带排序 + 搜索。
 * 每个表格实例各持一份搜索状态（原来是 Result 单实例内共享，提升到一级 Tabs 后需按表格隔离）。
 */
export const useResultColumns = () => {
  const [searchText, setSearchText] = useState('');
  const [searchedColumn, setSearchedColumn] = useState('');
  const searchInput = useRef<InputRef>(null);

  const handleReset = (clearFilters: () => void) => {
    clearFilters();
    setSearchText('');
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
      <div style={{ padding: 8 }} onKeyDown={(e) => e.stopPropagation()}>
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
      <SearchOutlined style={{ color: filtered ? '#1677ff' : undefined }} />
    ),
    onFilter: (value, record) =>
      String(record[dataIndex] ?? '')
        .toLowerCase()
        .includes(String(value ?? '').toLowerCase()),
    onFilterDropdownOpenChange: (visible) => {
      if (visible) {
        setTimeout(() => searchInput.current?.select(), 100);
      }
    },
    render: (text) => {
      // 统一字符串化：React 对 JS 原生 boolean/null/undefined 渲染为空，需显式转字符串展示
      const displayText = text == null ? '' : String(text);
      return searchedColumn === dataIndex ? (
        <Highlight label={displayText} words={[searchText]} />
      ) : (
        displayText
      );
    }
  });

  const getColumns = (columns: string[] = []) => {
    return columns?.map((item) => {
      return {
        title: item,
        dataIndex: item,
        sorter: (a: any, b: any) =>
          String(a[item] ?? '').localeCompare(String(b[item] ?? ''), undefined, {
            numeric: true
          }),
        ...getColumnSearchProps(item)
      };
    }) as ProColumns[];
  };

  return { getColumns };
};
