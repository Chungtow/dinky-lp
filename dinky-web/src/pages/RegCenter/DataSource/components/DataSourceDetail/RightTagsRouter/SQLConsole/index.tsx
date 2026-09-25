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

import { Height80VHDiv } from '@/components/StyledComponents';
import { QueryParams } from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/data';
import DataList from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/SQLConsole/DataList';
import Editor from '@/pages/RegCenter/DataSource/components/DataSourceDetail/RightTagsRouter/SQLConsole/Editor';
import { postAll } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { l } from '@/utils/intl';
import { PageLoading } from '@ant-design/pro-components';
import { ProColumns } from '@ant-design/pro-table';
import { Alert, message, Result } from 'antd';
import React, { useState } from 'react';

type SQLConsoleProps = {
  queryParams: QueryParams;
};

const SQLConsole: React.FC<SQLConsoleProps> = (props) => {
  const { queryParams } = props;
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState<boolean>(false);
  const [columns, setColumns] = useState<ProColumns[]>([]);
  const [data, setData] = useState<any[]>([]);
  const [errorMsg, setErrorMsg] = useState('');

  const handleInputChange = (value: string) => {
    setInputValue(value);
  };

  const execSql = async () => {
    if (!inputValue?.trim()) {
      message.warning('请输入要执行的 SQL');
      return;
    }
    setLoading(true);
    setErrorMsg('');
    try {
      const result: any = await postAll(API_CONSTANTS.DATASOURCE_EXEC_SQL, {
        id: queryParams.id,
        schemaName: queryParams.schemaName,
        tableName: queryParams.tableName,
        sql: inputValue
      });
      const inner = result?.data;
      const tableColumns: ProColumns[] = (inner?.columns ?? []).map((item: string) => ({
        title: item,
        dataIndex: item,
        key: item,
        ellipsis: true,
        tooltip: item,
        width: '8%'
      }));
      setColumns(tableColumns);
      setData(inner?.rowData ?? []);
    } catch (e: any) {
      // 后端 SQL 执行失败：BizError.info.data 为 JdbcSelectResult（含 error 详情）
      setErrorMsg(e?.info?.data?.error ?? e?.message ?? 'execute failed');
      setColumns([]);
      setData([]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Height80VHDiv>
      <Editor
        inputValue={inputValue}
        loading={loading}
        execCallback={execSql}
        handleInputChange={handleInputChange}
      />
      {errorMsg && (
        <Alert
          style={{ margin: '8px 0' }}
          message='Error'
          description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{errorMsg}</pre>}
          type='error'
          showIcon
        />
      )}
      {loading ? (
        <Result icon={<PageLoading spin={loading} />} title={l('rc.ds.console.running')} />
      ) : (
        <DataList columns={columns} data={data} />
      )}
    </Height80VHDiv>
  );
};

export default SQLConsole;
