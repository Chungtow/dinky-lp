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

import ThemeChip from '@/components/ThemeChip';
import { l } from '@/utils/intl';
import { ErrorMessage, InfoMessage, SuccessMessage } from '@/utils/messages';
import {
  adoptKbCollection,
  addKbGrants,
  deleteKbGrant,
  listKbGrants,
  listKbOrphans,
  listManageableKbCollections,
  saveKbCollection
} from '@/pages/RegCenter/Kb/service';
import { KbCollection, KbGrant, KbOrphan } from '@/pages/RegCenter/Kb/data.d';
import { postAll } from '@/services/api';
import {
  Button,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Typography
} from 'antd';
import { PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import React, { useCallback, useEffect, useMemo, useState } from 'react';

const { Text } = Typography;

/** 主体类型 → 取候选列表的接口（都是 POST + ProTable 参数） */
const SUBJECT_SOURCE: Record<string, string> = {
  tenant: '/api/tenant',
  role: '/api/role',
  user: '/api/user'
};

/**
 * 知识库管理页（批次 D）。
 *
 * <p><b>三个页签对应三件事</b>：管理我能管的库 / 发现并纳管 RAG 侧的孤儿库 / 给某个库配授权。
 * 授权是<b>抽屉</b>而不是独立页：它天然依附于某个库，独立页面只会让人反复选库。
 *
 * <p><b>为什么"未纳管的库"要单独一栏</b>：RAG 侧的 collection 是文档标签的扫描聚合，
 * 没有"创建"事件；外部系统直推就会凭空多出一个库（如 {@code ima_kb_62blyRRR}）。
 * 没有这一栏，管理员只能手工抄编码，抄错一位就是"纳管了但检不出东西"（D-K11/D-K12）。
 *
 * <p><b>刻意不提供的两件事</b>：① 物理删除（会让历史出处悬空，只提供停用）；
 * ② 改编码（{@code code} 是与 kb-service 的唯一连接键，改了检索立刻对不上）。
 */
const KbManage: React.FC = () => {
  const [dataSource, setDataSource] = useState<KbCollection[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [orphans, setOrphans] = useState<KbOrphan[]>([]);
  const [orphanLoading, setOrphanLoading] = useState<boolean>(false);
  const [orphanError, setOrphanError] = useState<string | undefined>();

  const [editOpen, setEditOpen] = useState<boolean>(false);
  const [editing, setEditing] = useState<KbCollection | null>(null);
  const [form] = Form.useForm();

  const [grantOpen, setGrantOpen] = useState<boolean>(false);
  const [grantTarget, setGrantTarget] = useState<KbCollection | null>(null);
  const [grants, setGrants] = useState<KbGrant[]>([]);
  const [grantLoading, setGrantLoading] = useState<boolean>(false);
  const [subjectType, setSubjectType] = useState<string>('tenant');
  const [subjectOptions, setSubjectOptions] = useState<{ label: string; value: number }[]>([]);
  const [subjectIds, setSubjectIds] = useState<number[]>([]);
  const [permission, setPermission] = useState<string>('view');

  const loadList = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listManageableKbCollections();
      if (res?.code !== 0) {
        ErrorMessage(res?.msg);
        setDataSource([]);
        return;
      }
      setDataSource(res?.data ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadOrphans = useCallback(async () => {
    setOrphanLoading(true);
    setOrphanError(undefined);
    try {
      const res = await listKbOrphans();
      if (res?.code !== 0) {
        // 非超管会被拒：这里如实显示原因，而不是显示一个空表让人以为"没有孤儿库"
        setOrphans([]);
        setOrphanError(res?.msg);
        return;
      }
      setOrphans(res?.data ?? []);
    } finally {
      setOrphanLoading(false);
    }
  }, []);

  useEffect(() => {
    loadList();
  }, []);

  /** 主体候选：租户/角色/用户三张表结构不同，名字字段做兜底 */
  useEffect(() => {
    if (!grantOpen) {
      return;
    }
    setSubjectIds([]);
    const url = SUBJECT_SOURCE[subjectType] ?? SUBJECT_SOURCE.tenant;
    postAll(url, { current: 1, size: 200 })
      .then((res: any) => {
        const rows = res?.data?.data ?? res?.data ?? [];
        setSubjectOptions(
          (Array.isArray(rows) ? rows : []).map((item: any) => ({
            value: item.id,
            label: item.name || item.roleName || item.username || `#${item.id}`
          }))
        );
      })
      .catch(() => setSubjectOptions([]));
  }, [subjectType, grantOpen]);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({ name: '', description: '', isPublic: false, enabled: true });
    setEditOpen(true);
  };

  const openEdit = (record: KbCollection) => {
    setEditing(record);
    form.setFieldsValue({
      name: record.name,
      description: record.description,
      isPublic: record.isPublic,
      enabled: record.enabled
    });
    setEditOpen(true);
  };

  const submitEdit = async () => {
    const values = await form.validateFields();
    const res = await saveKbCollection({ ...values, id: editing?.id });
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    SuccessMessage(l(editing ? 'pages.kb.updateSuccess' : 'pages.kb.createSuccess'));
    // 新建 ≠ 在 RAG 里建库：Dinky 只登记目录（code 由服务端生成），kb-service 的 collection
    // 是"文档标签的聚合"（meta_scan），所以推第一篇文档之前，库在 RAG 侧并不存在——
    // 此时检索必然 0 命中。这句话必须说出来，否则用户会以为"建好了却搜不到"是 bug。
    if (!editing) {
      InfoMessage(l('pages.kb.createHint'));
    }
    setEditOpen(false);
    loadList();
  };

  const toggleEnabled = async (record: KbCollection, enabled: boolean) => {
    const res = await saveKbCollection({ id: record.id, name: record.name, enabled });
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    loadList();
  };

  const togglePublic = async (record: KbCollection, isPublic: boolean) => {
    const res = await saveKbCollection({ id: record.id, name: record.name, isPublic });
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    loadList();
  };

  const openGrants = async (record: KbCollection) => {
    setGrantTarget(record);
    setGrantOpen(true);
    setGrantLoading(true);
    try {
      const res = await listKbGrants(record.id);
      setGrants(res?.code === 0 ? (res?.data ?? []) : []);
      if (res?.code !== 0) {
        ErrorMessage(res?.msg);
      }
    } finally {
      setGrantLoading(false);
    }
  };

  const submitGrant = async () => {
    if (!grantTarget || subjectIds.length === 0) {
      ErrorMessage(l('pages.kb.pickSubject'));
      return;
    }
    const res = await addKbGrants(grantTarget.id, { subjectType, subjectIds, permission });
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    SuccessMessage(l('pages.kb.grantSuccess'));
    setSubjectIds([]);
    openGrants(grantTarget);
  };

  const revokeGrant = async (grant: KbGrant) => {
    const res = await deleteKbGrant(grant.id);
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    if (grantTarget) {
      openGrants(grantTarget);
    }
  };

  const submitAdopt = async (record: KbOrphan) => {
    const res = await adoptKbCollection({
      code: record.code,
      name: record.code,
      description: record.sampleFilename
        ? `${record.sampleFilename} 等 ${record.docs} 篇`
        : undefined,
      // 纳管外部库默认不公开：跨系统内容进 AI 语料，先只授给个别租户验证（立项项 #5）
      isPublic: false
    });
    if (res?.code !== 0) {
      ErrorMessage(res?.msg);
      return;
    }
    SuccessMessage(l('pages.kb.adoptSuccess'));
    loadOrphans();
    loadList();
  };

  const columns = useMemo(
    () => [
      {
        title: l('pages.kb.name'),
        dataIndex: 'name',
        render: (_: any, record: KbCollection) => (
          <Space direction='vertical' size={0}>
            <Text strong>{record.name}</Text>
            <Text type='secondary' style={{ fontSize: 12 }}>
              {record.description}
            </Text>
          </Space>
        )
      },
      {
        title: l('pages.kb.code'),
        dataIndex: 'code',
        width: 180,
        render: (code: string) => <Text code>{code}</Text>
      },
      {
        title: l('pages.kb.visibility'),
        width: 130,
        render: (_: any, record: KbCollection) => (
          <Space size={4}>
            <ThemeChip tone={record.isPublic ? 'info' : 'neutral'}>
              {l(record.isPublic ? 'pages.kb.public' : 'pages.kb.private')}
            </ThemeChip>
            {/* 公开只影响"能看"：写权限恒需属主或 write 授权，这里用文案提醒，避免误解 */}
          </Space>
        )
      },
      {
        title: l('pages.kb.enabled'),
        width: 110,
        render: (_: any, record: KbCollection) => (
          <Switch
            size='small'
            checked={Boolean(record.enabled)}
            onChange={(checked) => toggleEnabled(record, checked)}
            checkedChildren={l('pages.kb.on')}
            unCheckedChildren={l('pages.kb.off')}
          />
        )
      },
      {
        title: l('pages.kb.publicSwitch'),
        width: 110,
        render: (_: any, record: KbCollection) => (
          <Switch
            size='small'
            checked={Boolean(record.isPublic)}
            onChange={(checked) => togglePublic(record, checked)}
          />
        )
      },
      { title: l('pages.kb.owner'), dataIndex: 'ownerId', width: 90 },
      { title: l('pages.kb.updateTime'), dataIndex: 'updateTime', width: 170 },
      {
        title: l('pages.kb.action'),
        width: 220,
        render: (_: any, record: KbCollection) => (
          <Space size={4}>
            <Button size='small' type='link' onClick={() => openEdit(record)}>
              {l('pages.kb.edit')}
            </Button>
            <Button
              size='small'
              type='link'
              icon={<SafetyCertificateOutlined />}
              onClick={() => openGrants(record)}
            >
              {l('pages.kb.grant')}
            </Button>
          </Space>
        )
      }
    ],
    []
  );

  const orphanColumns = [
    {
      title: l('pages.kb.code'),
      dataIndex: 'code',
      render: (code: string) => <Text code>{code}</Text>
    },
    { title: l('pages.kb.docs'), dataIndex: 'docs', width: 90 },
    { title: l('pages.kb.chunks'), dataIndex: 'chunks', width: 90 },
    {
      title: l('pages.kb.sample'),
      dataIndex: 'sampleFilename',
      render: (name: string) => <Text type='secondary'>{name || '-'}</Text>
    },
    {
      title: l('pages.kb.action'),
      width: 140,
      render: (_: any, record: KbOrphan) => (
        <Popconfirm
          title={l('pages.kb.adoptConfirm')}
          description={l('pages.kb.adoptDesc')}
          onConfirm={() => submitAdopt(record)}
        >
          <Button size='small' type='link'>
            {l('pages.kb.adopt')}
          </Button>
        </Popconfirm>
      )
    }
  ];

  const grantColumns = [
    {
      title: l('pages.kb.subjectType'),
      dataIndex: 'subjectType',
      render: (type: string) => (
        <ThemeChip tone={type === 'tenant' ? 'info' : 'neutral'}>
          {l(`pages.kb.subject.${type}`)}
        </ThemeChip>
      )
    },
    { title: l('pages.kb.subjectId'), dataIndex: 'subjectId', width: 100 },
    {
      title: l('pages.kb.permission'),
      dataIndex: 'permission',
      width: 110,
      render: (p: string) => (
        <ThemeChip tone={p === 'write' ? 'warn' : 'neutral'}>
          {l(p === 'write' ? 'pages.kb.permWrite' : 'pages.kb.permView')}
        </ThemeChip>
      )
    },
    {
      title: l('pages.kb.action'),
      width: 100,
      render: (_: any, record: KbGrant) => (
        <Popconfirm title={l('pages.kb.revokeConfirm')} onConfirm={() => revokeGrant(record)}>
          <Button size='small' type='link' danger>
            {l('pages.kb.revoke')}
          </Button>
        </Popconfirm>
      )
    }
  ];

  return (
    <PageContainer title={l('menu.registration.kb')}>
      <Tabs
        items={[
          {
            key: 'manage',
            label: l('pages.kb.tabManage'),
            children: (
              <Space direction='vertical' style={{ width: '100%' }} size={12}>
                <Space>
                  <Button type='primary' icon={<PlusOutlined />} onClick={openCreate}>
                    {l('pages.kb.create')}
                  </Button>
                  <Button icon={<ReloadOutlined />} onClick={loadList} loading={loading}>
                    {l('pages.kb.refresh')}
                  </Button>
                  <Text type='secondary' style={{ fontSize: 12 }}>
                    {l('pages.kb.manageHint')}
                  </Text>
                </Space>
                <Table
                  rowKey='id'
                  size='small'
                  loading={loading}
                  columns={columns as any}
                  dataSource={dataSource}
                />
              </Space>
            )
          },
          {
            key: 'orphans',
            label: l('pages.kb.tabOrphans'),
            children: (
              <Space direction='vertical' style={{ width: '100%' }} size={12}>
                <Space>
                  <Button icon={<ReloadOutlined />} onClick={loadOrphans} loading={orphanLoading}>
                    {l('pages.kb.refresh')}
                  </Button>
                  <Text type='secondary' style={{ fontSize: 12 }}>
                    {orphanError ?? l('pages.kb.orphanHint')}
                  </Text>
                </Space>
                <Table
                  rowKey='code'
                  size='small'
                  loading={orphanLoading}
                  columns={orphanColumns}
                  dataSource={orphans}
                />
              </Space>
            )
          }
        ]}
        onChange={(key) => {
          if (key === 'orphans') {
            loadOrphans();
          }
        }}
      />

      <Modal
        open={editOpen}
        title={editing ? l('pages.kb.editTitle') : l('pages.kb.createTitle')}
        onCancel={() => setEditOpen(false)}
        onOk={submitEdit}
        destroyOnClose
      >
        <Form form={form} layout='vertical'>
          <Form.Item
            name='name'
            label={l('pages.kb.name')}
            rules={[{ required: true, message: l('pages.kb.nameRequired') }]}
          >
            <Input placeholder={l('pages.kb.namePlaceholder')} />
          </Form.Item>
          <Form.Item name='description' label={l('pages.kb.description')}>
            <Input.TextArea rows={2} placeholder={l('pages.kb.descriptionPlaceholder')} />
          </Form.Item>
          <Form.Item name='isPublic' label={l('pages.kb.publicSwitch')} valuePropName='checked'>
            <Switch />
          </Form.Item>
          <Form.Item name='enabled' label={l('pages.kb.enabled')} valuePropName='checked'>
            <Switch />
          </Form.Item>
          <Text type='secondary' style={{ fontSize: 12 }}>
            {l('pages.kb.codeHint')}
          </Text>
        </Form>
      </Modal>

      <Drawer
        open={grantOpen}
        width={720}
        title={`${l('pages.kb.grantTitle')} — ${grantTarget?.name ?? ''}`}
        onClose={() => setGrantOpen(false)}
        destroyOnClose
      >
        <Space direction='vertical' size={12} style={{ width: '100%' }}>
          <Space wrap>
            <Select
              style={{ width: 120 }}
              value={subjectType}
              onChange={setSubjectType}
              options={[
                { value: 'tenant', label: l('pages.kb.subject.tenant') },
                { value: 'role', label: l('pages.kb.subject.role') },
                { value: 'user', label: l('pages.kb.subject.user') }
              ]}
            />
            <Select
              mode='multiple'
              style={{ minWidth: 240 }}
              value={subjectIds}
              onChange={setSubjectIds}
              options={subjectOptions}
              placeholder={l('pages.kb.pickSubject')}
            />
            <Select
              style={{ width: 140 }}
              value={permission}
              onChange={setPermission}
              options={[
                { value: 'view', label: l('pages.kb.permView') },
                { value: 'write', label: l('pages.kb.permWrite') }
              ]}
            />
            <Button type='primary' onClick={submitGrant}>
              {l('pages.kb.addGrant')}
            </Button>
          </Space>
          <Text type='secondary' style={{ fontSize: 12 }}>
            {l('pages.kb.grantHint')}
          </Text>
          <Table
            rowKey='id'
            size='small'
            loading={grantLoading}
            columns={grantColumns as any}
            dataSource={grants}
          />
        </Space>
      </Drawer>
    </PageContainer>
  );
};

export default KbManage;
