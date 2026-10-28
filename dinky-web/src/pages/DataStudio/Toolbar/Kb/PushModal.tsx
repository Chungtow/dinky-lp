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

import { KbCollectionBrief, KbPushResult } from '@/pages/DataStudio/Toolbar/Kb/data.d';
import { getKbDocument, listWritableKbCollections, pushKbDocument } from './service';
import { l } from '@/utils/intl';
import { Alert, Input, Modal, Select, Space, Spin, Typography } from 'antd';
import React, { useEffect, useRef, useState } from 'react';

const { Text } = Typography;

/** 推送请求（由三个来源 tab 各自构造） */
export type KbPushPayload = {
  /** 文件名，必须带后缀（后缀决定切分方式），默认取来源文件名 */
  filename: string;
  content: string;
  /** semantic / skill / resource（服务端据此写 provider 旁边的 path 标记） */
  source: 'semantic' | 'skill' | 'resource';
  /** Dinky 侧业务主键（skill id / 项目路径），便于回溯 */
  externalId?: string;
};

/**
 * 推送到知识库（批次 C，三个来源共用一套弹窗）。
 *
 * <p><b>为什么三种结果要分开讲</b>（先行记录 §10.3，2026-10-28 实测确认）：
 * <ul>
 *   <li>新文档 → 202 + {@code queued}，<b>解析是异步的</b>，所以要轮询到 {@code ready}/{@code failed}；</li>
 *   <li>同内容再推 → 200 + {@code dedup=true}，<b>没有重复入库</b>，返回的是合并后的最终归属——
 *       只提示"已存在"会让人以为归属变了；</li>
 *   <li>内容改过 → 新 {@code doc_id}，<b>旧版本仍在库里</b>（本侧不自动删，D-K4）。</li>
 * </ul>
 *
 * <p><b>为什么只列可写的库</b>：后端按 {@code canWrite}（属主 或 grant(write)）过滤；
 * 公开可见 ≠ 可写——否则任何人都能往全公司语料里塞内容，并借合并归属扩散（D-K3）。
 */
const PushModal: React.FC<{
  open: boolean;
  payload: KbPushPayload | null;
  onClose: () => void;
}> = ({ open, payload, onClose }) => {
  const [collections, setCollections] = useState<KbCollectionBrief[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [pushing, setPushing] = useState<boolean>(false);
  const [filename, setFilename] = useState<string>('');
  const [selected, setSelected] = useState<string[]>([]);
  const [result, setResult] = useState<KbPushResult | null>(null);
  const [error, setError] = useState<string | undefined>();
  const [polling, setPolling] = useState<boolean>(false);
  // 轮询代次：关闭弹窗/重新推送后，旧定时器不许再改状态
  const generation = useRef<number>(0);

  useEffect(() => {
    if (!open) {
      generation.current += 1;
      return;
    }
    setFilename(payload?.filename ?? '');
    setSelected([]);
    setResult(null);
    setError(undefined);
    setPolling(false);
    setLoading(true);
    listWritableKbCollections()
      .then((res: any) => {
        if (res?.code !== 0) {
          setError(res?.msg ?? l('datastudio.kb.loadFailed'));
          setCollections([]);
          return;
        }
        setCollections(res?.data ?? []);
      })
      .catch((e: any) => setError(e?.message ?? l('datastudio.kb.loadFailed')))
      .finally(() => setLoading(false));
  }, [open, payload]);

  /** 推送后轮询状态：queued/parsing/embedding → ready/failed（最多 60 s，超时就如实说"仍在处理"） */
  const pollStatus = (docId: string) => {
    const current = generation.current;
    setPolling(true);
    let tries = 0;
    const timer = window.setInterval(async () => {
      if (current !== generation.current) {
        window.clearInterval(timer);
        return;
      }
      tries += 1;
      try {
        const res: any = await getKbDocument(docId);
        if (res?.code === 0) {
          const status = res?.data?.status;
          setResult((prev) => (prev ? { ...prev, status } : prev));
          if (status === 'ready' || status === 'failed') {
            window.clearInterval(timer);
            setPolling(false);
            if (status === 'failed') {
              setError(res?.data?.error || l('datastudio.kb.pushFailedStatus'));
            }
          }
        }
      } catch {
        // 轮询失败静默重试；真正的失败由超时兜住
      }
      if (tries >= 20) {
        window.clearInterval(timer);
        setPolling(false);
      }
    }, 3000);
  };

  const doPush = async () => {
    if (!payload) {
      return;
    }
    if (selected.length === 0) {
      setError(l('datastudio.kb.pickCollection'));
      return;
    }
    setPushing(true);
    setError(undefined);
    setResult(null);
    try {
      const res: any = await pushKbDocument({
        filename: filename.trim(),
        content: payload.content,
        collections: selected,
        source: payload.source,
        externalId: payload.externalId
      });
      if (res?.code !== 0) {
        setError(res?.msg ?? l('datastudio.kb.pushFailed'));
        return;
      }
      const data: KbPushResult = res?.data;
      setResult(data);
      if (!data?.dedup) {
        pollStatus(data.docId);
      }
    } catch (e: any) {
      setError(e?.message ?? l('datastudio.kb.pushFailed'));
    } finally {
      setPushing(false);
    }
  };

  const statusText = (status?: string): string => {
    if (!status) return '';
    const key = `datastudio.kb.pushStatus.${status}`;
    const text = l(key);
    // 没有对应词条时 l 会回显 key，这里直接回退成状态原文，避免界面出现 key
    return text === key ? status : text;
  };

  return (
    <Modal
      open={open}
      title={l('datastudio.kb.pushTitle')}
      onCancel={onClose}
      onOk={doPush}
      okText={l('datastudio.kb.pushOk')}
      okButtonProps={{ loading: pushing, disabled: !payload || selected.length === 0 }}
      destroyOnClose
    >
      <Spin spinning={loading}>
        <Space direction='vertical' size={10} style={{ width: '100%' }}>
          <div>
            <Text type='secondary' style={{ fontSize: 12 }}>
              {l('datastudio.kb.pushFilename')}
            </Text>
            <Input
              value={filename}
              onChange={(e) => setFilename(e.target.value)}
              placeholder={l('datastudio.kb.pushFilenamePlaceholder')}
            />
            <Text type='secondary' style={{ fontSize: 12 }}>
              {l('datastudio.kb.pushFilenameTip')}
            </Text>
          </div>
          <div>
            <Text type='secondary' style={{ fontSize: 12 }}>
              {l('datastudio.kb.pushTargets')}
            </Text>
            <Select
              mode='multiple'
              style={{ width: '100%' }}
              value={selected}
              onChange={setSelected}
              placeholder={
                collections.length === 0
                  ? l('datastudio.kb.noWritable')
                  : l('datastudio.kb.pickCollection')
              }
              options={collections.map((item) => ({
                value: item.code,
                label: item.name
              }))}
              notFoundContent={<Text type='secondary'>{l('datastudio.kb.noWritable')}</Text>}
            />
          </div>
          {error && <Alert type='error' showIcon message={error} />}
          {result && (
            <Alert
              type={result.dedup ? 'info' : 'success'}
              showIcon
              message={result.message}
              description={
                <Space direction='vertical' size={0}>
                  <Text type='secondary' style={{ fontSize: 12 }}>
                    doc_id: {String(result.docId ?? '').slice(0, 12)}…
                  </Text>
                  <Text type='secondary' style={{ fontSize: 12 }}>
                    {l('datastudio.kb.pushStatusLabel')}: {statusText(result.status)}
                    {polling ? `（${l('datastudio.kb.pushPolling')}）` : ''}
                  </Text>
                </Space>
              }
            />
          )}
        </Space>
      </Spin>
    </Modal>
  );
};

export default PushModal;
