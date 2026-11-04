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
import { Form, Input, Modal, Select } from 'antd';
import React, { ReactNode, useEffect, useState } from 'react';

/** 新建资产时提交的表单值（skill 与 doc 共用） */
export type CreateAssetValues = {
  name: string;
  description: string;
  visibility?: 'private' | 'tenant' | 'public';
};

export type CreateAssetModalProps = {
  open: boolean;
  /** `doc` → 「新建知识文档」；`skill` → 「新建 Skill」。只影响标题，表单完全一致 */
  assetType?: 'skill' | 'doc';
  /** 可选：表单下方附加说明。AI Chat 用来提示"本轮回答将作为正文保存"，注册中心不传即无 */
  extraHint?: ReactNode;
  /** 可选：打开时回填。本批两处调用方都不传（三个字段都由用户自己填） */
  initialValues?: Partial<CreateAssetValues>;
  /**
   * 提交回调，**必须返回是否成功**。
   *
   * <p>不能靠 reject 判失败：本仓 `BusinessCrud.handleOption` 业务失败时同样 resolve
   * （只弹 WarningMessage 并返回 undefined），所以调用方要显式返回 `Boolean(result)`。
   * 组件只在拿到 `true` 时关闭并重置表单——失败时保持打开，用户可改完再提交。
   */
  onSubmit: (values: CreateAssetValues) => Promise<boolean>;
  onCancel: () => void;
};

/**
 * 「新建 skill / 新建知识文档」公共弹窗（2026-11-04 从 `SkillOverView` 下沉）。
 *
 * <p><b>为什么下沉</b>：原先这是内联在注册中心页面里的一段 `<Modal><Form>`，AI Chat 的
 * 「保存为知识文档」需要完全相同的表单，只能复制一份——两处表单从此各自漂移。下沉后
 * 注册中心与 AI Chat 共用同一份代码，**改一处两处生效**。
 *
 * <p>字段与校验与下沉前逐字一致：名称（slug 规则）、说明（必填）、可见性（默认私有 + 三档 + 底部灰字）。
 *
 * <p>相比下沉前补了两点：① 提交中 `confirmLoading`（原先可重复点击）；② 每次打开都
 * `resetFields`（原先关闭后重开会残留上次输入）。
 */
const CreateAssetModal: React.FC<CreateAssetModalProps> = ({
  open,
  assetType = 'skill',
  extraHint,
  initialValues,
  onSubmit,
  onCancel
}) => {
  const [form] = Form.useForm<CreateAssetValues>();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }
    form.resetFields();
    if (initialValues) {
      form.setFieldsValue(initialValues);
    }
  }, [open]);

  const handleOk = async () => {
    let values: CreateAssetValues;
    try {
      values = await form.validateFields();
    } catch {
      // 校验不通过：antd 已在对应字段下给出提示，这里只需不提交
      return;
    }
    setSubmitting(true);
    try {
      const ok = await onSubmit(values);
      if (ok) {
        form.resetFields();
        onCancel();
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={assetType === 'doc' ? l('pages.skill.createDocTitle') : l('pages.skill.createTitle')}
      okText={l('button.create')}
      cancelText={l('button.cancel')}
      confirmLoading={submitting}
      maskClosable={!submitting}
      onCancel={() => {
        if (!submitting) {
          onCancel();
        }
      }}
      onOk={handleOk}
    >
      <Form form={form} layout={'vertical'}>
        <Form.Item
          name={'name'}
          label={l('pages.skill.name')}
          rules={[
            { required: true, message: l('pages.skill.nameRequired') },
            { pattern: /^[a-z0-9][a-z0-9-]{1,63}$/, message: l('pages.skill.nameRule') }
          ]}
        >
          <Input placeholder={'dw-sql-review'} />
        </Form.Item>
        <Form.Item
          name={'description'}
          label={l('pages.skill.description')}
          rules={[{ required: true, message: l('pages.skill.descriptionRequired') }]}
        >
          <Input placeholder={l('pages.skill.descriptionPlaceholder')} />
        </Form.Item>
        {/* 2026-10-14 三档可见性：创建时即可选择（默认私有），创建后可在列表行「可见性」中修改 */}
        <Form.Item name={'visibility'} label={l('pages.skill.visibility')} initialValue={'private'}>
          <Select
            options={[
              { value: 'private', label: l('pages.skill.visibilityPrivate') },
              { value: 'tenant', label: l('pages.skill.visibilityTenant') },
              { value: 'public', label: l('pages.skill.visibilityPublic') }
            ]}
          />
        </Form.Item>
        <div style={{ color: 'rgba(0, 0, 0, 0.45)', fontSize: 12 }}>
          {l('pages.skill.visibilityHint')}
        </div>
        {extraHint ? (
          <div style={{ marginTop: 8, color: 'rgba(0, 0, 0, 0.45)', fontSize: 12 }}>
            {extraHint}
          </div>
        ) : null}
      </Form>
    </Modal>
  );
};

export default CreateAssetModal;
