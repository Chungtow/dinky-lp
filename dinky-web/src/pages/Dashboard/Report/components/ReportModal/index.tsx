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

import { ReportData } from '@/pages/Dashboard/Report/data';
import HtmlResourcePicker from '@/pages/Dashboard/Report/components/HtmlResourcePicker';
import { API_CONSTANTS } from '@/services/endpoints';
import { queryList } from '@/services/api';
import { l } from '@/utils/intl';
import { ProForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { ProFormInstance } from '@ant-design/pro-form/lib';
import { Button, Col, Form, Modal, Row, Tag } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';

type ReportModalProps = {
  open: boolean;
  /** 编辑时传入详情（含 roleIds）；新增时为 undefined */
  values?: ReportData;
  onCancel: () => void;
  onSubmit: (data: ReportData) => Promise<void> | void;
};

/**
 * 报表新建/编辑弹窗
 *
 * <p>三块内容：名称/备注、HTML 资源（从注册中心资源树里选，只允许 .html/.htm）、可见角色（角色级授权）。
 * 资源与角色都以「全量替换」方式保存：roleIds 传空数组 = 清空授权（报表转为公共可见）。
 */
const ReportModal: React.FC<ReportModalProps> = ({ open, values, onCancel, onSubmit }) => {
  const formRef = useRef<ProFormInstance>();
  const [roleOptions, setRoleOptions] = useState<{ label: string; value: number }[]>([]);
  const [resourceName, setResourceName] = useState<string>('');
  const [resourceId, setResourceId] = useState<number | undefined>();
  const [resourcePath, setResourcePath] = useState<string | undefined>();
  const [pickerOpen, setPickerOpen] = useState(false);

  const queryRoles = useCallback(async () => {
    const result = await queryList(API_CONSTANTS.ROLE);
    const roles = (result?.data as { id: number; roleName?: string; roleCode?: string }[]) ?? [];
    setRoleOptions(
      roles.map((role) => ({
        label: role.roleName ?? role.roleCode ?? String(role.id),
        value: role.id
      }))
    );
  }, []);

  useEffect(() => {
    if (!open) {
      return;
    }
    queryRoles();
    formRef.current?.resetFields();
    formRef.current?.setFieldsValue({
      name: values?.name,
      remark: values?.remark,
      roleIds: values?.roleIds ?? []
    });
    setResourceId(values?.resourceId);
    setResourcePath(values?.resourcePath);
    setResourceName(values?.resourceName ?? values?.resourcePath ?? '');
  }, [open, values, queryRoles]);

  const handleSelected = (resource: { id: number; fullName?: string; fileName?: string }) => {
    setResourceId(resource.id);
    setResourcePath(resource.fullName);
    setResourceName(resource.fileName ?? resource.fullName ?? '');
    setPickerOpen(false);
  };

  const handleOk = async () => {
    const fields = await formRef.current?.validateFields();
    if (resourceId == null) {
      formRef.current?.setFields([
        { name: 'resource', errors: [l('dashboard.report.resourceRequired')] }
      ]);
      return;
    }
    await onSubmit({
      ...values,
      ...fields,
      resourceId,
      resourcePath
    });
  };

  return (
    <Modal
      open={open}
      title={values?.id ? l('dashboard.report.edit') : l('dashboard.report.create')}
      onCancel={onCancel}
      onOk={handleOk}
      width={640}
      destroyOnClose
    >
      <ProForm<ReportData>
        formRef={formRef}
        submitter={false}
        preserve={false}
        onFinish={async () => {
          // 提交统一走 Modal 的 onOk，避免两套提交入口
          return true;
        }}
      >
        <ProFormText
          name='name'
          label={l('dashboard.report.name')}
          placeholder={l('dashboard.report.namePlaceholder')}
          rules={[{ required: true, message: l('dashboard.report.namePlaceholder') }]}
        />
        <ProFormTextArea
          name='remark'
          label={l('dashboard.report.remark')}
          placeholder={l('dashboard.report.remarkPlaceholder')}
        />
        <Form.Item
          name='resource'
          label={l('dashboard.report.resource')}
          required
          validateStatus={resourceId == null ? 'error' : undefined}
          help={resourceId == null ? l('dashboard.report.resourceRequired') : undefined}
        >
          <Row gutter={8} align='middle'>
            <Col flex='auto'>
              {resourceName ? (
                <Tag color='blue' style={{ maxWidth: '100%', overflow: 'hidden' }}>
                  {resourceName}
                </Tag>
              ) : (
                <span style={{ color: 'rgba(0,0,0,0.45)' }}>
                  {l('dashboard.report.resourceNotSelected')}
                </span>
              )}
            </Col>
            <Col>
              <Button onClick={() => setPickerOpen(true)}>
                {l('dashboard.report.selectResource')}
              </Button>
            </Col>
          </Row>
        </Form.Item>
        <ProFormSelect
          name='roleIds'
          label={l('dashboard.report.visibleRoles')}
          mode='multiple'
          options={roleOptions}
          fieldProps={{ allowClear: true, optionFilterProp: 'label' }}
          tooltip={l('dashboard.report.visibleRolesTip')}
        />
      </ProForm>
      <HtmlResourcePicker
        open={pickerOpen}
        onCancel={() => setPickerOpen(false)}
        onSelected={handleSelected}
      />
    </Modal>
  );
};

export default ReportModal;
