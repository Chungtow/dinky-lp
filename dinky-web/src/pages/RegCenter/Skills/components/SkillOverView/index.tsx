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
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import { createSkill, detailSkill, listSkills, removeSkill, saveSkill } from '../../service';
import type { SkillInfo } from '@/types/RegCenter/skill';

const SkillOverView = () => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<SkillInfo[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [editing, setEditing] = useState<{ id: number; name: string; content: string }>();
  const [saving, setSaving] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setRows(await listSkills());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openEdit = async (row: SkillInfo) => {
    setSaving(false);
    const detail = await detailSkill(row.id);
    if (detail?.editable === false) {
      // 非属主：只读查看（后端也会拒绝写入）
      Modal.info({ title: row.name, content: detail?.content ?? '' });
      return;
    }
    setEditing({ id: row.id, name: row.name, content: detail?.content ?? '' });
  };

  const handleSave = async () => {
    if (!editing) {
      return;
    }
    setSaving(true);
    try {
      await saveSkill({ id: editing.id, content: editing.content }, () => {
        setEditing(undefined);
      });
      await load();
    } finally {
      setSaving(false);
    }
  };

  const handleCreate = async () => {
    const values = await createForm.validateFields();
    await createSkill(values, () => {
      setCreateOpen(false);
      createForm.resetFields();
    });
    await load();
  };

  const columns = [
    { title: l('pages.skill.name'), dataIndex: 'name', width: 200 },
    { title: l('pages.skill.description'), dataIndex: 'description', ellipsis: true },
    {
      title: l('pages.skill.visibility'),
      dataIndex: 'visibility',
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'tenant' ? 'blue' : 'default'}>
          {value === 'tenant' ? l('pages.skill.visibilityTenant') : l('pages.skill.visibilityPrivate')}
        </Tag>
      )
    },
    { title: l('pages.skill.version'), dataIndex: 'version', width: 80 },
    { title: l('pages.skill.updateTime'), dataIndex: 'updateTime', width: 170 },
    {
      title: l('pages.skill.action'),
      width: 150,
      render: (_: any, row: SkillInfo) => (
        <Space>
          <Button size={'small'} onClick={() => openEdit(row)}>
            {l('pages.skill.viewOrEdit')}
          </Button>
          <Popconfirm
            title={l('pages.skill.removeConfirm')}
            okText={l('button.confirm')}
            cancelText={l('button.cancel')}
            onConfirm={() => removeSkill(row.id, load)}
          >
            <Button size={'small'} danger>
              {l('button.delete')}
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <>
      <Card
        title={l('pages.skill.listTitle')}
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load}>
              {l('button.refresh')}
            </Button>
            <Button type={'primary'} icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              {l('button.create')}
            </Button>
          </Space>
        }
      >
        <Table
          rowKey={'id'}
          size={'small'}
          loading={loading}
          dataSource={rows}
          // 列定义由 antd 动态推导，这里放开类型以简化
          columns={columns as any}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </Card>

      <Modal
        open={createOpen}
        title={l('pages.skill.createTitle')}
        okText={l('button.create')}
        cancelText={l('button.cancel')}
        onCancel={() => setCreateOpen(false)}
        onOk={handleCreate}
      >
        <Form form={createForm} layout={'vertical'}>
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
        </Form>
      </Modal>

      <Modal
        open={!!editing}
        title={`${l('pages.skill.editTitle')}${editing?.name ?? ''}`}
        width={880}
        confirmLoading={saving}
        okText={l('button.save')}
        cancelText={l('button.cancel')}
        onCancel={() => setEditing(undefined)}
        onOk={handleSave}
      >
        <Input.TextArea
          value={editing?.content ?? ''}
          onChange={(e) =>
            setEditing((prev) => (prev ? { ...prev, content: e.target.value } : prev))
          }
          autoSize={{ minRows: 16, maxRows: 28 }}
          style={{ fontFamily: 'monospace' }}
        />
        <div style={{ marginTop: 8, color: 'rgba(0, 0, 0, 0.45)', fontSize: 12 }}>
          {l('pages.skill.editHint')}
        </div>
      </Modal>
    </>
  );
};

export default SkillOverView;
