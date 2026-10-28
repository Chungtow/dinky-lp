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

import { connect } from '@@/exports';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { KbCollectionStat, KbDocument } from '@/pages/DataStudio/Toolbar/Kb/data.d';
import { listKbCollectionStats, listKbDocuments, readKbDocument } from './service';
import { l } from '@/utils/intl';
import { WarningMessage } from '@/utils/messages';
import { getTabIcon } from '@/pages/DataStudio/function';
import { Empty, Input, List, Pagination, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { BookOutlined, FileTextOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useState } from 'react';

const { Text } = Typography;

const PAGE_SIZE = 20;

/** epoch 秒 → 「x 分钟前 / y 天前」；0 表示该库还没有文档 */
const relativeTime = (epochSeconds?: number): string => {
  if (!epochSeconds) {
    return '';
  }
  const diff = Date.now() / 1000 - epochSeconds;
  if (diff < 60) return l('datastudio.kb.justNow');
  if (diff < 3600) return `${Math.floor(diff / 60)} ${l('datastudio.kb.minutesAgo')}`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} ${l('datastudio.kb.hoursAgo')}`;
  return `${Math.floor(diff / 86400)} ${l('datastudio.kb.daysAgo')}`;
};

/** 处理中状态：这些文档还不能检索，界面要显示"处理中"而不是让人以为坏了 */
const isProcessing = (status: string): boolean =>
  ['queued', 'parsing', 'embedding'].includes((status ?? '').toLowerCase());

/**
 * 左侧栏「知识库」tab（批次 B）。
 *
 * <p><b>只读</b>：这里没有任何编辑/保存/删除入口。改内容请回原 tab 改完再推送——
 * 知识库的内容真相源在 kb-service，正文一变 `doc_id`（sha256）就变，Dinky 侧改字等于造假。
 *
 * <p><b>不做目录树</b>：文档零散且海量，树没有意义。只有"库 → 平铺文档列表 + 文件名搜索 + 分页"。
 *
 * <p><b>搜索走服务端</b>（`GET /api/kb/documents?q=`）：全量拉回浏览器再过滤会拖垮前端，
 * 而且可见集文档数没有上限。
 */
const Kb = (props: {
  centerContent: DataStudioState['centerContent'];
  addCenterTab: (payload: any) => void;
  /** 已打开的文档要"定位过去"而不是重开一个（重开会把已看的位置重置） */
  activateCenterTab: (id: string) => void;
}) => {
  const { centerContent, addCenterTab, activateCenterTab } = props;

  const [collections, setCollections] = useState<KbCollectionStat[]>([]);
  const [collection, setCollection] = useState<string | undefined>();
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [offset, setOffset] = useState<number>(0);
  const [keyword, setKeyword] = useState<string>('');
  const [query, setQuery] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingDocs, setLoadingDocs] = useState<boolean>(false);
  const [error, setError] = useState<string | undefined>();

  const loadCollections = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const res = await listKbCollectionStats();
      if (res?.code !== 0) {
        setCollections([]);
        setError(res?.msg ?? l('datastudio.kb.loadFailed'));
        return;
      }
      const list: KbCollectionStat[] = res?.data ?? [];
      setCollections(list);
      // 默认选中最近有入库的那个库，省掉一次点击
      setCollection((prev) => prev ?? list[0]?.code);
    } catch (e: any) {
      setCollections([]);
      setError(e?.message ?? l('datastudio.kb.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDocuments = useCallback(async (code?: string, page?: number, q?: string) => {
    if (!code) {
      setDocuments([]);
      setTotal(0);
      return;
    }
    setLoadingDocs(true);
    setError(undefined);
    try {
      const res = await listKbDocuments({
        collection: code,
        q,
        sort: 'updated_desc',
        limit: PAGE_SIZE,
        offset: page ?? 0
      });
      if (res?.code !== 0) {
        setDocuments([]);
        setTotal(0);
        setError(res?.msg ?? l('datastudio.kb.loadFailed'));
        return;
      }
      setDocuments(res?.data?.items ?? []);
      setTotal(res?.data?.total ?? 0);
    } catch (e: any) {
      setDocuments([]);
      setTotal(0);
      setError(e?.message ?? l('datastudio.kb.loadFailed'));
    } finally {
      setLoadingDocs(false);
    }
  }, []);

  useEffect(() => {
    loadCollections();
  }, []);

  useEffect(() => {
    loadDocuments(collection, offset, query);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collection, offset, query]);

  /**
   * 打开文档：已打开则只激活，不重读。
   *
   * <p>与语义文件同一教训（P3c / BUG-1）：用 `updateCenterTab` 覆盖正文会丢掉用户滚动位置，
   * 且 `activeTab` 不跟着变——`activateCenterTab` 才是"定位过去"。
   */
  const openDocument = async (doc: KbDocument) => {
    if (!doc.openable) {
      await WarningMessage(l('datastudio.kb.notOpenable'));
      return;
    }
    const tabId = `kb_${doc.docId}`;
    const existed = (centerContent?.tabs ?? []).some((tab) => tab.id === tabId);
    if (existed) {
      activateCenterTab(tabId);
      return;
    }
    setError(undefined);
    try {
      const res = await readKbDocument(doc.docId);
      if (res?.code !== 0) {
        setError(res?.msg ?? l('datastudio.kb.readFailed'));
        return;
      }
      const data = res?.data ?? {};
      const payload: CenterTab = {
        id: tabId,
        tabType: 'kbFile',
        title: data.filename ?? doc.filename,
        isUpdate: false,
        params: {
          docId: doc.docId,
          filename: data.filename ?? doc.filename,
          contentType: data.contentType,
          // 与语义文件同构：正文放 statement，只读标记恒 true（从根上不给编辑可能）
          statement: data.content ?? '',
          readOnly: true,
          collections: data.collections ?? doc.collections ?? [],
          chunks: doc.chunks
        }
      };
      addCenterTab(payload);
    } catch (e: any) {
      setError(e?.message ?? l('datastudio.kb.readFailed'));
    }
  };

  const onSearch = (value: string) => {
    setOffset(0);
    setQuery(value.trim());
  };

  const onSelectCollection = (code: string) => {
    if (code === collection) {
      return;
    }
    setCollection(code);
    setOffset(0);
    setKeyword('');
    setQuery('');
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '0 4px' }}>
      {/* 上半栏：库列表（名称 + 篇数 + 最近入库） */}
      <div style={{ flex: '0 0 auto', maxHeight: '45%', overflow: 'auto' }}>
        <Space style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 4px' }}>
          <Text strong>
            <BookOutlined /> {l('datastudio.kb.collections')}
          </Text>
          <Tooltip title={l('datastudio.kb.refresh')}>
            <ReloadOutlined style={{ cursor: 'pointer' }} onClick={loadCollections} />
          </Tooltip>
        </Space>
        <Spin spinning={loading} size='small'>
          {collections.length === 0 && !loading ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={error ?? l('datastudio.kb.noCollection')}
            />
          ) : (
            <List
              size='small'
              dataSource={collections}
              renderItem={(item) => {
                const active = item.code === collection;
                return (
                  <List.Item
                    style={{
                      cursor: 'pointer',
                      padding: '4px 8px',
                      background: active ? 'rgba(22,119,255,0.08)' : undefined,
                      borderRadius: 4
                    }}
                    onClick={() => onSelectCollection(item.code)}
                  >
                    <div style={{ width: '100%' }}>
                      <Tooltip title={item.description || item.code}>
                        <Text strong={active} ellipsis style={{ display: 'block' }}>
                          {item.name}
                        </Text>
                      </Tooltip>
                      <Space size={6}>
                        <Text type='secondary' style={{ fontSize: 12 }}>
                          {item.docs} {l('datastudio.kb.docsUnit')} · {item.chunks}{' '}
                          {l('datastudio.kb.chunksUnit')}
                        </Text>
                        {item.lastTs > 0 && (
                          <Text type='secondary' style={{ fontSize: 12 }}>
                            {relativeTime(item.lastTs)}
                          </Text>
                        )}
                      </Space>
                    </div>
                  </List.Item>
                );
              }}
            />
          )}
        </Spin>
      </div>

      {/* 下半栏：文档列表 + 服务端搜索 + 分页 */}
      <div
        style={{
          flex: 1,
          minHeight: 0,
          borderTop: '1px solid rgba(5,5,5,0.06)',
          display: 'flex',
          flexDirection: 'column'
        }}
      >
        <Space style={{ padding: '4px', width: '100%' }} direction='vertical' size={2}>
          <Input
            size='small'
            allowClear
            prefix={<SearchOutlined />}
            placeholder={l('datastudio.kb.searchPlaceholder')}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={(e) => onSearch((e.target as HTMLInputElement).value)}
            onClear={() => onSearch('')}
            disabled={!collection}
          />
          <Text type='secondary' style={{ fontSize: 12 }}>
            {l('datastudio.kb.totalPrefix')} {total} {l('datastudio.kb.docsUnit')}
          </Text>
        </Space>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <Spin spinning={loadingDocs} size='small'>
            {documents.length === 0 && !loadingDocs ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  error ??
                  (collection ? l('datastudio.kb.emptyDocs') : l('datastudio.kb.noCollection'))
                }
              />
            ) : (
              <List
                size='small'
                dataSource={documents}
                renderItem={(item) => (
                  <List.Item
                    style={{
                      padding: '4px 8px',
                      cursor: item.openable ? 'pointer' : 'not-allowed'
                    }}
                    onClick={() => openDocument(item)}
                  >
                    <div style={{ width: '100%', opacity: item.openable ? 1 : 0.55 }}>
                      <Tooltip title={item.filename}>
                        <Text ellipsis style={{ display: 'block' }}>
                          {getTabIcon(item.filename?.split('.').pop() || 'txt', 14)} {item.filename}
                        </Text>
                      </Tooltip>
                      <Space size={6} wrap>
                        {isProcessing(item.status) && (
                          <Tag color='processing'>{l('datastudio.kb.statusProcessing')}</Tag>
                        )}
                        {item.status === 'failed' && (
                          <Tooltip title={item.error || ''}>
                            <Tag color='error'>{l('datastudio.kb.statusFailed')}</Tag>
                          </Tooltip>
                        )}
                        <Text type='secondary' style={{ fontSize: 12 }}>
                          {item.chunks ?? 0} {l('datastudio.kb.chunksUnit')}
                        </Text>
                        {item.updatedAt ? (
                          <Text type='secondary' style={{ fontSize: 12 }}>
                            {relativeTime(item.updatedAt)}
                          </Text>
                        ) : null}
                        {!item.openable && (
                          <Tag color='default'>{l('datastudio.kb.notOpenableTag')}</Tag>
                        )}
                      </Space>
                    </div>
                  </List.Item>
                )}
              />
            )}
          </Spin>
        </div>
        {total > PAGE_SIZE && (
          <Pagination
            size='small'
            simple
            current={Math.floor(offset / PAGE_SIZE) + 1}
            pageSize={PAGE_SIZE}
            total={total}
            style={{ textAlign: 'center', padding: '4px 0' }}
            onChange={(page) => setOffset((page - 1) * PAGE_SIZE)}
          />
        )}
      </div>
    </div>
  );
};

// connect 的 OwnProps 推断对"父组件不传 props"的面板不成立，与 Toolbar/Semantic / Resource 同一类既有告警；
// 运行期注入仍是 mapDispatchToProps 的 addCenterTab / activateCenterTab。
export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(Kb as any) as React.ComponentType<any>;
