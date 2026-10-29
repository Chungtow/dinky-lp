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

import { HasAuthority } from '@/hooks/useAccess';
import ThemeChip from '@/components/ThemeChip';
import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import { SuccessMessage, WarningMessage } from '@/utils/messages';
import {
  Badge,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Input,
  List,
  Modal,
  Radio,
  Segmented,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import React, { useEffect, useMemo, useState } from 'react';
import type {
  SemanticAssetType,
  SemanticFeedback,
  SemanticFeedbackStatus,
  SemanticFeedbackSummary,
  SemanticFeedbackType
} from './data.d';
import {
  getSemanticFeedbackSummary,
  listSemanticFeedback,
  resolveSemanticFeedback,
  submitSemanticFeedback
} from './service';

/**
 * 由语义文件路径推断「资产名 + 资产类型」。
 *
 * <p>dbt 约定：{@code models/xxx.yml} 是语义模型（TABLE），{@code metrics/xxx.yml} 是指标（METRIC）。
 * 推断不准也不会出错——后端在类型未知时会先试 TABLE 再试 METRIC（见 `resolveSlicePath`）。
 */
export const semanticAssetOf = (
  path?: string
): { assetName: string; assetType: SemanticAssetType } => {
  const value = path ?? '';
  const file = value.split('/').pop() ?? '';
  return {
    assetName: file.replace(/\.(ya?ml)$/i, ''),
    assetType: /(^|\/)metrics\//i.test(value) ? 'METRIC' : 'TABLE'
  };
};

/** 问题类型候选（与后端注释约定一致，后端不强制枚举） */
const FEEDBACK_TYPES: SemanticFeedbackType[] = [
  'WRONG_DEFINITION',
  'WRONG_DIMENSION',
  'OUTDATED',
  'OTHER'
];

const statusTag = (status: SemanticFeedbackStatus) => {
  if (status === 'RESOLVED') {
    return <Tag color={'success'}>{l(`datastudio.semantic.feedback.status.${status}`)}</Tag>;
  }
  if (status === 'IGNORED') {
    return <Tag>{l(`datastudio.semantic.feedback.status.${status}`)}</Tag>;
  }
  return <Tag color={'warning'}>{l(`datastudio.semantic.feedback.status.${status}`)}</Tag>;
};

// ---------------------------------------------------------------------------
// ① 提交弹窗
// ---------------------------------------------------------------------------

/**
 * P4 一期：语义资产反馈的前端两件套（本文件）。
 *
 * <ol>
 *   <li>提交入口 {@link SemanticFeedbackModal}：从「语义树右键 → 反馈问题」或
 *       「语义文件编辑区 → 反馈此资产」打开，针对一个具体资产写一条意见
 *       （有用/有误 + 问题类型 + 正文）；</li>
 *   <li>待办入口 {@link SemanticFeedbackDrawer}：列出反馈并把状态收敛到 RESOLVED / IGNORED，
 *       需要新增的 {@code datastudio:semantic:feedback}（无权限者看不到处理按钮）。</li>
 * </ol>
 *
 * <p><b>为什么不让前端算版本 / 指纹</b>：用户看到的是编辑区内容，而「这条反馈针对哪一版口径」
 * 只有发布版本说了算。后端在提交时按最新发布版本解析 {@code version} 与 {@code yaml_sha256}，
 * 前端只负责说明「哪个资产的什么问题」。
 */
export type SemanticFeedbackModalProps = {
  open: boolean;
  onClose: () => void;
  /** 语义项目名（从面板/文件 tab 带入；空则允许手动填） */
  project?: string;
  /** 资产名（通常由文件路径推断） */
  assetName?: string;
  /** 资产类型（TABLE / METRIC） */
  assetType?: SemanticAssetType;
  /** 来源 AI 会话 id（可空，用于回查上下文） */
  sessionId?: string;
  /** 提交成功后的回调（例如刷新待办角标） */
  onSubmitted?: () => void;
};

export const SemanticFeedbackModal = (props: SemanticFeedbackModalProps) => {
  const { open, onClose, project, assetName, assetType, sessionId, onSubmitted } = props;
  const [rating, setRating] = useState<number>(-1);
  const [feedbackType, setFeedbackType] = useState<SemanticFeedbackType>('WRONG_DEFINITION');
  const [content, setContent] = useState<string>('');
  const [projectValue, setProjectValue] = useState<string>(project ?? '');
  const [assetValue, setAssetValue] = useState<string>(assetName ?? '');
  const [submitting, setSubmitting] = useState<boolean>(false);

  // 每次打开都按调用方带入的上下文重置：避免上一次的意见残留在另一个资产上
  useEffect(() => {
    if (!open) {
      return;
    }
    setRating(-1);
    setFeedbackType('WRONG_DEFINITION');
    setContent('');
    setProjectValue(project ?? '');
    setAssetValue(assetName ?? '');
  }, [open, project, assetName]);

  const onSubmit = async () => {
    const finalProject = projectValue.trim();
    const finalAsset = assetValue.trim();
    if (!finalProject || !finalAsset) {
      await WarningMessage(l('datastudio.semantic.feedback.needAsset'));
      return;
    }
    setSubmitting(true);
    try {
      const res = await submitSemanticFeedback({
        projectName: finalProject,
        assetName: finalAsset,
        assetType,
        rating,
        // 只有「有误」才需要问题类型；「有用」时清空，避免统计口径里混入噪声
        feedbackType: rating === -1 ? feedbackType : undefined,
        content: content.trim() || undefined,
        sessionId
      });
      if (res?.code !== 0) {
        await WarningMessage(res?.msg ?? l('datastudio.semantic.feedback.submitFailed'));
        return;
      }
      await SuccessMessage(l('datastudio.semantic.feedback.submitted'));
      onSubmitted?.();
      onClose();
    } catch (e: any) {
      await WarningMessage(e?.message ?? l('datastudio.semantic.feedback.submitFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={l('datastudio.semantic.feedback.submitTitle')}
      okText={l('datastudio.semantic.feedback.submit')}
      cancelText={l('button.cancel')}
      confirmLoading={submitting}
      onOk={onSubmit}
      onCancel={onClose}
      destroyOnClose
    >
      <Descriptions column={1} size={'small'} style={{ marginBottom: 8 }}>
        <Descriptions.Item label={l('datastudio.semantic.feedback.asset')}>
          <Space size={4} wrap>
            {project ? (
              <Typography.Text strong>{project}</Typography.Text>
            ) : (
              <Input
                size={'small'}
                style={{ width: 180 }}
                placeholder={l('datastudio.semantic.feedback.projectPlaceholder')}
                value={projectValue}
                onChange={(e) => setProjectValue(e.target.value)}
              />
            )}
            <Typography.Text type={'secondary'}>/</Typography.Text>
            {assetName ? (
              <Typography.Text strong>{assetName}</Typography.Text>
            ) : (
              <Input
                size={'small'}
                style={{ width: 180 }}
                placeholder={l('datastudio.semantic.feedback.assetPlaceholder')}
                value={assetValue}
                onChange={(e) => setAssetValue(e.target.value)}
              />
            )}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label={l('datastudio.semantic.feedback.rating')}>
          <Radio.Group value={rating} onChange={(e) => setRating(e.target.value)}>
            <Radio.Button value={1}>{l('datastudio.semantic.feedback.ratingUp')}</Radio.Button>
            <Radio.Button value={-1}>{l('datastudio.semantic.feedback.ratingDown')}</Radio.Button>
          </Radio.Group>
        </Descriptions.Item>
        {rating === -1 && (
          <Descriptions.Item label={l('datastudio.semantic.feedback.type')}>
            <Select
              size={'small'}
              style={{ width: 240 }}
              value={feedbackType}
              onChange={(value) => setFeedbackType(value)}
              options={FEEDBACK_TYPES.map((item) => ({
                value: item,
                label: l(`datastudio.semantic.feedback.type.${item}`)
              }))}
            />
          </Descriptions.Item>
        )}
      </Descriptions>
      <Input.TextArea
        autoSize={{ minRows: 3, maxRows: 8 }}
        maxLength={4000}
        showCount
        placeholder={l('datastudio.semantic.feedback.content')}
        value={content}
        onChange={(e) => setContent(e.target.value)}
      />
      <Typography.Text type={'secondary'} style={{ fontSize: 12 }}>
        {l('datastudio.semantic.feedback.versionTip')}
      </Typography.Text>
    </Modal>
  );
};

// ---------------------------------------------------------------------------
// ② 待办抽屉
// ---------------------------------------------------------------------------

export type SemanticFeedbackDrawerProps = {
  open: boolean;
  onClose: () => void;
  /** 只看向某个项目的反馈（可空 = 全部） */
  project?: string;
  /** 数量变化时回调（面板上的角标据此刷新） */
  onCountChange?: (open: number) => void;
};

export const SemanticFeedbackDrawer = (props: SemanticFeedbackDrawerProps) => {
  const { open, onClose, project, onCountChange } = props;
  const [status, setStatus] = useState<'OPEN' | 'ALL'>('OPEN');
  const [items, setItems] = useState<SemanticFeedback[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [summary, setSummary] = useState<SemanticFeedbackSummary>();
  /** 非空即弹出「处理」小窗（先选结论再填说明，避免直接点就把状态改掉） */
  const [resolveTarget, setResolveTarget] = useState<{
    item: SemanticFeedback;
    status: 'RESOLVED' | 'IGNORED';
  }>();
  const [note, setNote] = useState<string>('');
  const [resolving, setResolving] = useState<boolean>(false);

  const canResolve = HasAuthority(PermissionConstants.DATASTUDIO_SEMANTIC_FEEDBACK);

  const load = async (nextStatus: 'OPEN' | 'ALL' = status, silent = false) => {
    if (!silent) {
      setLoading(true);
    }
    try {
      const [listRes, summaryRes] = await Promise.all([
        listSemanticFeedback({
          project,
          status: nextStatus === 'OPEN' ? 'OPEN' : undefined,
          limit: 200
        }),
        getSemanticFeedbackSummary(project)
      ]);
      setItems(listRes?.code === 0 ? (listRes.data ?? []) : []);
      if (summaryRes?.code === 0) {
        setSummary(summaryRes.data);
        onCountChange?.(summaryRes.data?.open ?? 0);
      }
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) {
      load(status);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, status, project]);

  const onResolve = async () => {
    if (!resolveTarget) {
      return;
    }
    setResolving(true);
    try {
      const res = await resolveSemanticFeedback(resolveTarget.item.id, {
        status: resolveTarget.status,
        note: note.trim() || undefined
      });
      if (res?.code !== 0) {
        await WarningMessage(res?.msg ?? l('datastudio.semantic.feedback.resolveFailed'));
        return;
      }
      await SuccessMessage(
        l('datastudio.semantic.feedback.resolved', undefined, {
          status: l(`datastudio.semantic.feedback.status.${resolveTarget.status}`)
        })
      );
      setResolveTarget(undefined);
      setNote('');
      await load(status, true);
    } catch (e: any) {
      await WarningMessage(e?.message ?? l('datastudio.semantic.feedback.resolveFailed'));
    } finally {
      setResolving(false);
    }
  };

  const counters = useMemo(
    () => [
      {
        key: 'open',
        label: l('datastudio.semantic.feedback.status.OPEN'),
        value: summary?.open ?? 0
      },
      {
        key: 'resolved',
        label: l('datastudio.semantic.feedback.status.RESOLVED'),
        value: summary?.resolved ?? 0
      },
      {
        key: 'ignored',
        label: l('datastudio.semantic.feedback.status.IGNORED'),
        value: summary?.ignored ?? 0
      }
    ],
    [summary]
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={560}
      title={
        <Space size={8}>
          <InboxOutlined />
          {l('datastudio.semantic.feedback.todoTitle')}
          <Segmented
            size={'small'}
            value={status}
            onChange={(value) => setStatus(value as 'OPEN' | 'ALL')}
            options={[
              { label: l('datastudio.semantic.feedback.filterOpen'), value: 'OPEN' },
              { label: l('datastudio.semantic.feedback.filterAll'), value: 'ALL' }
            ]}
          />
        </Space>
      }
      extra={
        <Space size={6}>
          {/* 用 ThemeChip 而不是 antd Tag：DataStudio 的 ConfigProvider 把 colorBgContainer
              设成了 CSS 变量字符串，Tag 的派生底色会退化，深色主题下计数标签「黑字黑底」看不清 */}
          {counters.map((item) => (
            <ThemeChip key={item.key} tone={item.key === 'open' ? 'warn' : 'neutral'}>
              {item.label} {item.value}
            </ThemeChip>
          ))}
          <Button size={'small'} loading={loading} onClick={() => load(status)}>
            {l('datastudio.semantic.refresh')}
          </Button>
        </Space>
      }
    >
      <Spin spinning={loading}>
        {items.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={l('datastudio.semantic.feedback.empty')}
          />
        ) : (
          <List
            dataSource={items}
            renderItem={(item) => (
              <List.Item
                key={item.id}
                actions={
                  canResolve && item.status === 'OPEN'
                    ? [
                        <Button
                          key={'resolved'}
                          type={'link'}
                          size={'small'}
                          onClick={() => {
                            setResolveTarget({ item, status: 'RESOLVED' });
                            setNote('');
                          }}
                        >
                          {l('datastudio.semantic.feedback.markResolved')}
                        </Button>,
                        <Button
                          key={'ignored'}
                          type={'link'}
                          size={'small'}
                          onClick={() => {
                            setResolveTarget({ item, status: 'IGNORED' });
                            setNote('');
                          }}
                        >
                          {l('datastudio.semantic.feedback.markIgnored')}
                        </Button>
                      ]
                    : []
                }
              >
                <List.Item.Meta
                  title={
                    <Space size={6} wrap>
                      <Tag color={item.rating === -1 ? 'error' : 'success'}>
                        {item.rating === -1
                          ? l('datastudio.semantic.feedback.ratingDown')
                          : l('datastudio.semantic.feedback.ratingUp')}
                      </Tag>
                      <Typography.Text strong>
                        {item.projectName} / {item.assetName}
                      </Typography.Text>
                      {statusTag(item.status)}
                      {item.feedbackType ? (
                        <Tag>{l(`datastudio.semantic.feedback.type.${item.feedbackType}`)}</Tag>
                      ) : null}
                      {item.version ? (
                        <Tooltip title={`yaml_sha256: ${item.yamlSha256 ?? '-'}`}>
                          <Tag color={'blue'}>
                            {l('datastudio.semantic.feedback.version')} {item.version}
                          </Tag>
                        </Tooltip>
                      ) : null}
                    </Space>
                  }
                  description={
                    <Space direction={'vertical'} size={2} style={{ width: '100%' }}>
                      {item.content ? (
                        <Typography.Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                          {item.content}
                        </Typography.Paragraph>
                      ) : null}
                      <Typography.Text type={'secondary'} style={{ fontSize: 12 }}>
                        {l('datastudio.semantic.feedback.from')} {item.createTime ?? '-'}
                        {item.resolveNote
                          ? ` ｜ ${l('datastudio.semantic.feedback.note')}: ${item.resolveNote}`
                          : ''}
                      </Typography.Text>
                    </Space>
                  }
                />
              </List.Item>
            )}
          />
        )}
      </Spin>

      <Modal
        open={Boolean(resolveTarget)}
        title={l('datastudio.semantic.feedback.resolveTitle')}
        okText={l('datastudio.semantic.feedback.confirmResolve')}
        cancelText={l('button.cancel')}
        confirmLoading={resolving}
        onOk={onResolve}
        onCancel={() => setResolveTarget(undefined)}
        destroyOnClose
      >
        <Space direction={'vertical'} size={8} style={{ width: '100%' }}>
          <Typography.Text>
            {resolveTarget?.item.projectName} / {resolveTarget?.item.assetName}
            {' → '}
            {resolveTarget ? l(`datastudio.semantic.feedback.status.${resolveTarget.status}`) : ''}
          </Typography.Text>
          <Input.TextArea
            autoSize={{ minRows: 2, maxRows: 6 }}
            maxLength={2000}
            placeholder={l('datastudio.semantic.feedback.notePlaceholder')}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </Space>
      </Modal>
    </Drawer>
  );
};

/** 待办角标（面板头部用；无待办时不显示红点，避免噪声） */
export const SemanticFeedbackBadge = (props: { count: number; children: React.ReactNode }) => (
  <Badge count={props.count} size={'small'} offset={[2, -2]}>
    {props.children}
  </Badge>
);

export default SemanticFeedbackModal;
