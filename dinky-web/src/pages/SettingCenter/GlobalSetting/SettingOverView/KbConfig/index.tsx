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
import { BaseConfigProperties } from '@/types/SettingCenter/data';
import { l } from '@/utils/intl';
import { Tag } from 'antd';
import React from 'react';

interface KbConfigProps {
  data: BaseConfigProperties[];
  onSave: (data: BaseConfigProperties) => void;
  auth: string;
}

/**
 * 知识库（RAG）配置（{@code sys.kb.settings.*}）。
 *
 * <p><b>为什么要独立分组</b>：这四项描述的是"Dinky 连哪个 kb-service、用哪个密钥"，
 * 与 LLM 提供商的 baseUrl/apiKey 是两个外部系统。早先寄生在 {@code sys.llm.settings.*} 下，
 * 配置中心只能在「LLM」页签的几十项里翻找，且 key 名（kbBaseUrl）与实际语义不符。
 *
 * <p>四项：服务地址、访问密钥（后端脱敏，前端只见掩码）、检索开关、注入预算。
 * 保存后由 {@code /api/sysConfig/modifyConfig} 同时写库与刷新内存，无需重启。
 */
export const KbConfig = ({ data, onSave, auth }: KbConfigProps) => {
  const [loading, setLoading] = React.useState(false);

  const onSaveHandler = async (dataConfig: BaseConfigProperties) => {
    setLoading(true);
    await onSave(dataConfig);
    setLoading(false);
  };

  return (
    <GeneralConfig
      loading={loading}
      onSave={onSaveHandler}
      auth={auth}
      tag={
        <>
          <Tag color={'purple'}>{l('sys.setting.tag.kb')}</Tag>
        </>
      }
      data={data}
    />
  );
};
