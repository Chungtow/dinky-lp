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

import { FormContextValue } from '@/components/Context/FormContext';
import { NORMAL_MODAL_OPTIONS, SWITCH_OPTIONS } from '@/services/constants';
import { l } from '@/utils/intl';
import {
  ModalForm,
  ProFormCheckbox,
  ProFormDigit,
  ProFormGroup,
  ProFormSelect,
  ProFormSwitch,
  ProFormText,
  ProFormTextArea
} from '@ant-design/pro-components';

import {
  PriorityList,
  TimeoutNotifyStrategy
} from '@/pages/DataStudio/CenterTabContent/SqlTask/PushDolphin/constants';
import { transformPushDolphinParams } from '@/pages/DataStudio/CenterTabContent/SqlTask/PushDolphin/function';
import {
  DolphinTaskDefinition,
  DolphinTaskGroupInfo,
  DolphinTaskMinInfo,
  PushDolphinParams
} from '@/types/Studio/data.d';
import { InitPushDolphinParams } from '@/types/Studio/init.d';
import { Button, Form, Tag } from 'antd';
import { DefaultOptionType } from 'antd/es/select';
import React, { useEffect, useState } from 'react';
import { TaskState } from '@/pages/DataStudio/type';

type PushDolphinProps = {
  onCancel: () => void;
  dolphinTaskList: DolphinTaskMinInfo[];
  dolphinTaskGroup: DolphinTaskGroupInfo[];
  dolphinDefinitionTask: Partial<DolphinTaskDefinition>;
  modalVisible: boolean;
  currentDinkyTaskValue: Partial<TaskState>;
  loading: boolean;
  onSubmit: (values: DolphinTaskDefinition) => void;
  formValuesInfo: DolphinTaskDefinition;
};

export const PushDolphin: React.FC<PushDolphinProps> = (props) => {
  const {
    onCancel,
    onSubmit,
    modalVisible,
    dolphinTaskList,
    dolphinDefinitionTask,
    dolphinTaskGroup,
    currentDinkyTaskValue,
    loading,
    formValuesInfo
  } = props;

  const [formValues, setFormValues] = React.useState<PushDolphinParams>(
    transformPushDolphinParams(
      dolphinDefinitionTask as DolphinTaskDefinition,
      { ...InitPushDolphinParams, taskId: currentDinkyTaskValue?.taskId ?? '' },
      true
    ) as PushDolphinParams
  );
  useEffect(() => {
    if (JSON.stringify(formValuesInfo) != '{}') {
      const isWARN = formValuesInfo?.timeoutNotifyStrategy?.includes('WARN') ? 'WARN' : false;
      const isFAILED = formValuesInfo?.timeoutNotifyStrategy?.includes('FAILED') ? 'FAILED' : false;

      const temp: any = {
        ...formValuesInfo,
        flag: formValuesInfo.flag == 'NO' ? false : true,
        timeoutFlag: formValuesInfo.timeoutFlag == 'CLOSE' ? false : true,
        isCache: formValuesInfo.isCache == 'YES' ? true : false,
        timeoutNotifyStrategy: [isWARN, isFAILED].filter((item) => item)
      };
      form.setFieldsValue(temp);
    }
  }, []);
  /**
   * init form
   */
  const [form] = Form.useForm<PushDolphinParams>();

  /**
   * init form context
   */
  const formContext = React.useMemo<FormContextValue>(
    () => ({
      resetForm: () => form.resetFields() // 定义 resetForm 方法
    }),
    [form]
  );

  /**
   * cancel choose
   */
  const handleCancel = () => {
    onCancel();
    formContext.resetForm();
  };

  const handlePushDolphinSubmit = async () => {
    const values = form.validateFields();
    if (!values) {
      return;
    }
    const transformPushDolphinParamsValue: DolphinTaskDefinition = transformPushDolphinParams(
      dolphinDefinitionTask as DolphinTaskDefinition,
      formValues,
      false
    ) as DolphinTaskDefinition;

    onSubmit(transformPushDolphinParamsValue);
    handleCancel();
  };

  const renderFooter = () => {
    return [
      <Button key={'pushCancel'} onClick={handleCancel}>
        {l('button.cancel')}
      </Button>,
      <Button
        key={'push'}
        type='primary'
        htmlType={'submit'}
        loading={loading}
        onClick={() => handlePushDolphinSubmit()}
      >
        {l('button.finish')}
      </Button>
    ];
  };

  const buildUpstreamTaskOptions = (
    data: DolphinTaskMinInfo[] | undefined,
    keyword = ''
  ): DefaultOptionType[] => {
    if (!data || data.length === 0) {
      return [];
    }
    // 默认按「修改时间倒序」排列（时间缺失或相同时按任务名升序兜底）：
    // 前置任务候选直接沿用接口返回顺序时毫无规律，任务一多就无法定位。
    // 时间字段是 'yyyy-MM-dd HH:mm:ss' 字符串，字典序即时间序（用 new Date 解析该格式不可靠，
    // 解析失败会退化成按名称排序）。
    const timeKey = (item: DolphinTaskMinInfo) =>
      String(item.taskUpdateTime ?? item.taskCreateTime ?? '');
    const sorted = [...data].sort((a, b) => {
      const ta = timeKey(a);
      const tb = timeKey(b);
      if (tb !== ta) {
        return tb.localeCompare(ta);
      }
      return (a.taskName ?? '').localeCompare(b.taskName ?? '');
    });
    // 关键字模糊匹配（任务名/任务类型/所属工作流），大小写不敏感。
    // 这里自行过滤而不是交给 antd：options 的 label 是 ReactNode，
    // antd 只能按 value（任务编码）匹配，输入任务名必然「暂无数据」。
    const kw = keyword.trim().toLowerCase();
    const matched = kw
      ? sorted.filter((item) =>
          `${item.taskName ?? ''} ${item.taskType ?? ''} ${item.processDefinitionName ?? ''}`
            .toLowerCase()
            .includes(kw)
        )
      : sorted;
    return matched.map((item) => {
      const label = (
        <>
          <Tag color={'purple'}>
            {l('datastudio.header.pushdolphin.taskName', '', { name: item.taskName })}
          </Tag>
          <span style={{ color: '#8a8a8a' }}>
            {l('datastudio.header.pushdolphin.taskNameExt', '', {
              type: item.taskType,
              processDefinitionName: item.processDefinitionName
            })}
          </span>
        </>
      );
      return {
        label: label,
        value: item.taskCode.toString(),
        key: item.taskCode
      };
    });
  };

  /** 前置任务下拉的搜索关键字（外部过滤，见 buildUpstreamTaskOptions 注释） */
  const [upstreamKeyword, setUpstreamKeyword] = useState<string>('');

  const handleValueChange = (changedValues: any, allValues: any) => {
    if (allValues) {
      setFormValues({ ...formValues, ...allValues });
    }
  };

  const pushDolphinForm = () => {
    return (
      <>
        <ProFormText name={'taskId'} label={l('datastudio.header.pushdolphin.taskId')} disabled />
        <ProFormSelect
          label={l('datastudio.header.pushdolphin.upstreamCodes')}
          name={'upstreamCodes'}
          showSearch
          mode={'multiple'}
          placeholder={'可按任务名/类型/工作流模糊搜索；列表默认按修改时间倒序'}
          extra={l('datastudio.header.pushdolphin.upstreamCodesTip')}
          options={buildUpstreamTaskOptions(dolphinTaskList, upstreamKeyword) as any}
          fieldProps={{
            // 关闭 antd 内置过滤（label 是 ReactNode，内置过滤只能按 value=任务编码匹配，
            // 输入任务名必然“暂无数据”），改为在 options 里按关键字自行过滤。
            filterOption: false,
            onSearch: (value: string) => setUpstreamKeyword(value)
          }}
        />

        <ProFormGroup>
          <ProFormSelect
            label={l('datastudio.header.pushdolphin.taskPriority')}
            name={'taskPriority'}
            width={'sm'}
            options={PriorityList}
          />
          <ProFormSelect
            label={l('datastudio.header.pushdolphin.taskGroup')}
            name={'taskGroupId'}
            width={'sm'}
            options={dolphinTaskGroup}
            fieldProps={{
              fieldNames: { label: 'name', value: 'id' }
            }}
            // fieldNames={label:'label', value: 'value'}
          />
          <ProFormDigit
            label={l('datastudio.header.pushdolphin.taskGroupPriority')}
            name={'taskGroupPriority'}
            initialValue={formValues.taskGroupPriority}
            width={'sm'}
            min={0}
            max={99}
            fieldProps={{
              precision: 0
            }}
          />
          <ProFormDigit
            label={l('datastudio.header.pushdolphin.failRetryTimes')}
            name={'failRetryTimes'}
            initialValue={formValues.failRetryTimes}
            width={'sm'}
            min={0}
            max={99}
            fieldProps={{
              precision: 0
            }}
          />

          <ProFormDigit
            label={l('datastudio.header.pushdolphin.failRetryInterval')}
            name={'failRetryInterval'}
            width={'sm'}
            min={0}
            fieldProps={{
              precision: 0
            }}
          />

          <ProFormDigit
            label={l('datastudio.header.pushdolphin.delayTime')}
            name={'delayTime'}
            width={'sm'}
            min={0}
            fieldProps={{
              precision: 0
            }}
          />

          <ProFormSwitch
            label={l('datastudio.header.pushdolphin.timeoutFlag')}
            {...SWITCH_OPTIONS()}
            name={'timeoutFlag'}
          />

          <ProFormSwitch
            label={l('datastudio.header.pushdolphin.flag')}
            {...SWITCH_OPTIONS()}
            name={'flag'}
          />

          <ProFormSwitch
            label={l('datastudio.header.pushdolphin.isCache')}
            {...SWITCH_OPTIONS()}
            name={'isCache'}
          />
        </ProFormGroup>
        {/*如果是失败告警，则需要设置告警策略*/}
        {formValues.timeoutFlag && (
          <>
            <ProFormGroup>
              <ProFormCheckbox.Group
                label={l('datastudio.header.pushdolphin.timeoutNotifyStrategy')}
                name={'timeoutNotifyStrategy'}
                width={'sm'}
                options={TimeoutNotifyStrategy}
              />
              <ProFormDigit
                label={l('datastudio.header.pushdolphin.timeout')}
                name={'timeout'}
                width={'sm'}
                min={0}
                max={30}
                fieldProps={{
                  precision: 0
                }}
              />
            </ProFormGroup>
          </>
        )}

        <ProFormTextArea label={l('global.table.note')} name={'description'} />
      </>
    );
  };

  return (
    <ModalForm<PushDolphinParams>
      {...NORMAL_MODAL_OPTIONS}
      title={l('datastudio.header.pushdolphin.title', '', {
        name: currentDinkyTaskValue?.name ?? ''
      })}
      open={modalVisible}
      form={form}
      initialValues={formValues}
      modalProps={{
        onCancel: handleCancel,
        destroyOnClose: true
      }}
      submitter={{ render: () => [...renderFooter()] }}
      onValuesChange={handleValueChange}
      loading={loading}
    >
      {pushDolphinForm()}
    </ModalForm>
  );
};

export default PushDolphin;
