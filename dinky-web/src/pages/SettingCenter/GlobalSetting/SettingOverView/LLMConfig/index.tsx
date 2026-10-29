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

import GeneralConfig from '@/pages/SettingCenter/GlobalSetting/SettingOverView/GeneralConfig';
import { BaseConfigProperties } from '@/types/SettingCenter/data.d';
import { l } from '@/utils/intl';
import { RadioChangeEvent, Tag } from 'antd';
import React from 'react';

interface LLMConfigProps {
  data: BaseConfigProperties[];
  onSave: (data: BaseConfigProperties) => void;
  auth: string;
}

/**
 * LLM（AI 大模型）配置。
 *
 * <p>与 EnvConfig 一样复用 GeneralConfig 渲染；配置项由后端 SystemConfiguration 反射注册，
 * 新增/修改后无需重启即可生效（API Key 后端已做脱敏，前端不会拿到明文）。
 */
export const LLMConfig = ({ data, onSave, auth }: LLMConfigProps) => {
  const [loading, setLoading] = React.useState(false);

  const onSaveHandler = async (dataConfig: BaseConfigProperties) => {
    setLoading(true);
    await onSave(dataConfig);
    setLoading(false);
  };

  const selectChange = async (e: RadioChangeEvent) => {
    const { value, name } = e.target;
    await onSaveHandler({
      name: '',
      example: [],
      frontType: '',
      key: name ?? '',
      note: '',
      value: value.toString().toLocaleUpperCase()
    });
  };

  return (
    <>
      <GeneralConfig
        loading={loading}
        onSave={onSaveHandler}
        auth={auth}
        tag={
          <>
            <Tag color={'processing'}>{l('sys.setting.tag.system')}</Tag>
          </>
        }
        selectChanges={selectChange}
        data={data}
      />
    </>
  );
};
