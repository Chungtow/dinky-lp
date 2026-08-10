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
import { ProFormList, ProFormSelect, ProFormText } from '@ant-design/pro-components';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { Tooltip } from 'antd';
import React from 'react';

/**
 * Task parameter configuration component.
 * Users define parameters like pt=$[yyyyMMdd-1] that will be
 * resolved by DS at scheduling time and injected into SQL at runtime.
 */
export const ProFormTaskParam: React.FC<{}> = () => {
  return (
    <ProFormList
      name={['configJson', 'taskParams']}
      copyIconProps={false}
      creatorButtonProps={{
        style: { width: '100%' },
        creatorButtonText: l('pages.datastudio.label.taskParam.addParam')
      }}
    >
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', width: '100%' }}>
        <ProFormText
          name={'prop'}
          formItemProps={{ style: { flex: '1 1 0', marginBottom: 0, minWidth: 80 } }}
          fieldProps={{ style: { width: '100%' } }}
          placeholder={l('pages.datastudio.label.taskParam.prop.placeholder')}
          rules={[{ required: true, message: l('pages.datastudio.label.taskParam.prop.required') }]}
        />
        <ProFormSelect
          name={'direct'}
          width={64}
          formItemProps={{ style: { flex: '0 0 64px', marginBottom: 0 } }}
          placeholder={l('pages.datastudio.label.taskParam.direct')}
          options={[
            { label: 'IN', value: 'IN' },
            { label: 'OUT', value: 'OUT' }
          ]}
          initialValue={'IN'}
        />
        <ProFormSelect
          name={'type'}
          width={110}
          formItemProps={{ style: { flex: '0 0 110px', marginBottom: 0 } }}
          placeholder={l('pages.datastudio.label.taskParam.type')}
          options={[
            { label: 'VARCHAR', value: 'VARCHAR' },
            { label: 'INTEGER', value: 'INTEGER' },
            { label: 'LONG', value: 'LONG' },
            { label: 'DOUBLE', value: 'DOUBLE' },
            { label: 'BOOLEAN', value: 'BOOLEAN' }
          ]}
          initialValue={'VARCHAR'}
        />
        <ProFormText
          name={'value'}
          formItemProps={{ style: { flex: '1 1 0', marginBottom: 0, minWidth: 120 } }}
          fieldProps={{
            style: { width: '100%' },
            addonAfter: (
              <Tooltip
                title={
                  <>
                    {l('pages.datastudio.label.taskParam.value.tip')}
                    <a
                      href='https://dolphinscheduler.apache.org/zh-cn/docs/3.1.9/guide/parameter/built-in'
                      target='_blank'
                      rel='noopener noreferrer'
                    >
                      {l('pages.datastudio.label.taskParam.value.tip.link')}
                    </a>
                  </>
                }
              >
                <QuestionCircleOutlined style={{ color: 'rgba(0,0,0,.45)' }} />
              </Tooltip>
            )
          }}
          placeholder={l('pages.datastudio.label.taskParam.value.placeholder')}
          rules={[{ required: true, message: l('pages.datastudio.label.taskParam.value.required') }]}
        />
      </div>
    </ProFormList>
  );
};
