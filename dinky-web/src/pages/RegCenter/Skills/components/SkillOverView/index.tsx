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

import { Authorized } from '@/hooks/useAccess';
import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import { CloudUploadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import PushModal, { KbPushPayload } from '@/pages/DataStudio/Toolbar/Kb/PushModal';
import { message } from 'antd';
import { Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import {
  createSkill,
  detailSkill,
  listSkills,
  removeSkill,
  saveSkill,
  updateSkillVisibility
} from '../../service';
import SkillFilePanel from '../SkillFilePanel';
import type { SkillInfo } from '@/types/RegCenter/skill';

/** 可见性展示（2026-10-14 三档）：文案 + Tag 颜色 */
const visibilityLabel = (value?: string) => {
  switch (value) {
    case 'public':
      return { label: l('pages.skill.visibilityPublic'), color: 'gold' };
    case 'tenant':
      return { label: l('pages.skill.visibilityTenant'), color: 'blue' };
    default:
      return { label: l('pages.skill.visibilityPrivate'), color: 'default' };
  }
};

const SkillOverView = () => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<SkillInfo[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  /**
   * 阶段 4b 补（UAT R2）：本次新建弹窗的目标资产类型。
   * skill 与 doc 共用同一套后端能力与表单，只在入口按钮上区分（doc 落在 docs/<name>/DOC.md）。
   */
  const [createAssetType, setCreateAssetType] = useState<'skill' | 'doc'>('skill');
  const [createForm] = Form.useForm();
  const [editing, setEditing] = useState<{ id: number; name: string; content: string }>();
  const [saving, setSaving] = useState(false);
  // 阶段 4b：文件面板（管理 skill 目录树与 references/ 子文件）
  const [filePanel, setFilePanel] = useState<{
    id: number;
    name: string;
    assetType?: string;
    editable: boolean;
  }>();
  // 2026-10-14 三档可见性：修改可见性弹窗
  const [visibilityTarget, setVisibilityTarget] = useState<{
    id: number;
    name: string;
    visibility?: string;
  }>();
  const [visibilityForm] = Form.useForm();
  const [visibilitySaving, setVisibilitySaving] = useState(false);
  /** 批次 C：待推送的 skill（载荷 null = 弹窗关闭） */
  const [pushPayload, setPushPayload] = useState<KbPushPayload | null>(null);

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

  /** 阶段 4b：打开文件面板（先取详情拿 editable——非属主只读查看） */
  const openFiles = async (row: SkillInfo) => {
    const detail = await detailSkill(row.id);
    setFilePanel({
      id: row.id,
      name: row.name,
      assetType: row.assetType,
      editable: detail?.editable !== false
    });
  };

  /** 2026-10-14 三档可见性：打开「修改可见性」弹窗（属主校验由后端负责） */
  /**
   * 打开推送弹窗：<b>先取最新正文</b>再推。
   *
   * <p>不能用列表行数据——列表不含 content；也不能用编辑弹窗里的草稿——那可能是没保存的改动，
   * 推上去的就不是"当前生效的 skill"。
   */
  const openPush = async (row: SkillInfo) => {
    try {
      const detail = await detailSkill(row.id);
      setPushPayload({
        // skill 正文是 markdown：后缀决定按标题切分，别省
        filename: `${row.name}.md`,
        content: detail?.content ?? '',
        source: 'skill',
        // id 作为业务主键：服务端据此把 doc_id 写回 dinky_skill.kb_doc_id
        externalId: String(row.id)
      });
    } catch (e: any) {
      message.error(e?.message ?? l('datastudio.kb.readFailed'));
    }
  };

  const openVisibility = (row: SkillInfo) => {
    visibilityForm.setFieldsValue({ visibility: row.visibility ?? 'private' });
    setVisibilityTarget({ id: row.id, name: row.name, visibility: row.visibility });
  };

  const handleVisibilitySave = async () => {
    if (!visibilityTarget) {
      return;
    }
    const values = await visibilityForm.validateFields();
    setVisibilitySaving(true);
    try {
      await updateSkillVisibility(
        { id: visibilityTarget.id, visibility: values.visibility },
        () => {
          setVisibilityTarget(undefined);
        }
      );
      await load();
    } finally {
      setVisibilitySaving(false);
    }
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
    await createSkill({ ...values, assetType: createAssetType }, () => {
      setCreateOpen(false);
      createForm.resetFields();
    });
    await load();
  };

  const columns = [
    { title: l('pages.skill.name'), dataIndex: 'name', width: 200 },
    {
      // 阶段 4b 补（UAT R2）：同表承载 skill 与知识文档，列表上明确区分
      title: l('pages.skill.assetType'),
      dataIndex: 'assetType',
      width: 100,
      render: (value: string) => (
        <Tag color={value === 'doc' ? 'purple' : 'green'}>
          {value === 'doc' ? l('pages.skill.typeDoc') : l('pages.skill.typeSkill')}
        </Tag>
      )
    },
    { title: l('pages.skill.description'), dataIndex: 'description', ellipsis: true },
    {
      title: l('pages.skill.visibility'),
      dataIndex: 'visibility',
      width: 110,
      render: (value: string) => {
        const meta = visibilityLabel(value);
        return <Tag color={meta.color}>{meta.label}</Tag>;
      }
    },
    { title: l('pages.skill.version'), dataIndex: 'version', width: 80 },
    { title: l('pages.skill.updateTime'), dataIndex: 'updateTime', width: 170 },
    {
      title: l('pages.skill.action'),
      // 2026-10-14：本列为 4 个操作（查看/编辑、文件、可见性、删除），原 230 会裁掉末尾的删除按钮；
      // 同时给 Table 加了 scroll.x 兜底，窄屏横向滚动而非裁切；右侧固定，滚动时按钮始终可见。
      // 批次 C 起本列是 5 个操作（查看/编辑、文件、可见性、推送知识库、删除），300 会裁掉末尾
      width: 420,
      fixed: 'right' as const,
      render: (_: any, row: SkillInfo) => (
        <Space>
          <Authorized path={PermissionConstants.REGISTRATION_SKILL_SAVE}>
            <Button size={'small'} onClick={() => openEdit(row)}>
              {l('pages.skill.viewOrEdit')}
            </Button>
          </Authorized>
          <Authorized path={PermissionConstants.REGISTRATION_SKILL_FILE}>
            <Button size={'small'} type={'link'} onClick={() => openFiles(row)}>
              {l('pages.skill.fileManage')}
            </Button>
          </Authorized>
          {/* 2026-10-14 三档可见性：入口对持有 save 权限者可见，「是否本人资产」由后端校验 */}
          <Authorized path={PermissionConstants.REGISTRATION_SKILL_SAVE}>
            <Button size={'small'} type={'link'} onClick={() => openVisibility(row)}>
              {l('pages.skill.visibilityChange')}
            </Button>
          </Authorized>
          {/* 批次 C：把 skill 正文推入知识库。有 kb:write 只代表能调接口，
              具体能不能推某个库由后端按该库的 grant(write) 判定 */}
          <Authorized path={PermissionConstants.KB_WRITE}>
            <Button
              size={'small'}
              type={'link'}
              icon={<CloudUploadOutlined />}
              onClick={() => openPush(row)}
            >
              {l('datastudio.kb.pushFromSkill')}
            </Button>
          </Authorized>
          <Authorized path={PermissionConstants.REGISTRATION_SKILL_DELETE}>
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
          </Authorized>
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
            <Authorized path={PermissionConstants.REGISTRATION_SKILL_FILE}>
              <Button
                icon={<PlusOutlined />}
                onClick={() => {
                  setCreateAssetType('doc');
                  setCreateOpen(true);
                }}
              >
                {l('pages.skill.createDoc')}
              </Button>
            </Authorized>
            <Authorized path={PermissionConstants.REGISTRATION_SKILL_ADD}>
              <Button
                type={'primary'}
                icon={<PlusOutlined />}
                onClick={() => {
                  setCreateAssetType('skill');
                  setCreateOpen(true);
                }}
              >
                {l('button.create')}
              </Button>
            </Authorized>
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
          // 2026-10-14：各固定列合计 960px +「说明」列自适应。用 scroll.x 兜底——
          // 宽屏时表格铺满容器（多余宽度分给「说明」列），窄屏时横向滚动，
          // 而不是像之前那样把操作列的按钮直接裁掉。
          scroll={{ x: 1220 }}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </Card>

      {/* 阶段 4b：文件面板（目录树 + references 子文件；与 AI 工具共用同一组后端接口） */}
      <PushModal
        open={Boolean(pushPayload)}
        payload={pushPayload}
        onClose={() => setPushPayload(null)}
      />

      <SkillFilePanel
        skillId={filePanel?.id}
        skillName={filePanel?.name}
        assetType={filePanel?.assetType}
        editable={filePanel?.editable}
        open={!!filePanel}
        onClose={() => setFilePanel(undefined)}
      />

      <Modal
        open={createOpen}
        title={
          createAssetType === 'doc' ? l('pages.skill.createDocTitle') : l('pages.skill.createTitle')
        }
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
          {/* 2026-10-14 三档可见性：创建时即可选择（默认私有），创建后可在列表行「可见性」中修改 */}
          <Form.Item
            name={'visibility'}
            label={l('pages.skill.visibility')}
            initialValue={'private'}
          >
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
        </Form>
      </Modal>

      {/* 2026-10-14 三档可见性：修改可见性（仅属主；设为「全体公开」还需 registration:skill:share 权限） */}
      <Modal
        open={!!visibilityTarget}
        title={`${l('pages.skill.visibilityChangeTitle')}${visibilityTarget?.name ?? ''}`}
        confirmLoading={visibilitySaving}
        okText={l('button.save')}
        cancelText={l('button.cancel')}
        onCancel={() => setVisibilityTarget(undefined)}
        onOk={handleVisibilitySave}
      >
        <Form form={visibilityForm} layout={'vertical'}>
          <Form.Item
            name={'visibility'}
            label={l('pages.skill.visibility')}
            rules={[{ required: true }]}
          >
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
