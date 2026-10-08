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

interface KafkaConfigProps {
  data: BaseConfigProperties[];
  onSave: (data: BaseConfigProperties) => void;
  auth: string;
}

/**
 * Kafka 配置（AI Chat × FlinkSQL P0）。
 *
 * <p><b>定位</b>：这里存的是「平台级 Kafka 连接事实」（接入地址 / 安全协议 / SASL / 命名约定），
 * 供 AI Chat 生成或解读 FlinkSQL 时取权威值并做漂移校验。<b>它不会自动注入到作业 DDL</b>——
 * Flink 的 connector options 只能写在 `CREATE TABLE ... WITH(...)` 里，作业仍显式写地址。
 *
 * <p>与 EnvConfig / LLMConfig 一样复用 GeneralConfig 渲染；配置项由后端 SystemConfiguration
 * 反射注册（`sys.kafka.settings.*`），改已有项即时生效，<b>新增字段需重启</b>。
 */
export const KafkaConfig = ({ data, onSave, auth }: KafkaConfigProps) => {
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
