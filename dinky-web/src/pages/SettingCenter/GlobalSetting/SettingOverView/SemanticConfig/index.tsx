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

interface SemanticConfigProps {
  data: BaseConfigProperties[];
  onSave: (data: BaseConfigProperties) => void;
  auth: string;
}

/**
 * 语义层配置（{@code sys.semantic.settings.*}）。
 *
 * <p>只有两项：是否启用「数据开发 - 语义项目」、语义根路径。语义根必须与 JupyterLab 侧
 * {@code webhdfs.yml:semantic_base} 一致，否则两边各写一个目录且互不报错。
 */
export const SemanticConfig = ({ data, onSave, auth }: SemanticConfigProps) => {
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
          <Tag color={'blue'}>{l('sys.setting.tag.semantic')}</Tag>
        </>
      }
      data={data}
    />
  );
};
