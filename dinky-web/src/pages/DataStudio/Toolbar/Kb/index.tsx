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
import ThemeChip from '@/components/ThemeChip';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { KbCollectionStat, KbDocument } from '@/pages/DataStudio/Toolbar/Kb/data.d';
import { listKbCollectionStats, listKbDocuments, readKbDocument } from './service';
import { l } from '@/utils/intl';
import { WarningMessage } from '@/utils/messages';
import { getTabIcon } from '@/pages/DataStudio/function';
import { Empty, Input, List, Space, Spin, Tooltip, Typography } from 'antd';
import { BookOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import React, { useCallback, useEffect, useRef, useState } from 'react';

const { Text } = Typography;

/** 每页条数：kb-service 支持 limit/offset，服务端分页，浏览器永远只持有已加载的部分 */
const PAGE_SIZE = 50;

/**
 * 已加载条数的硬上限。
 *
 * <p>为什么要有上限：库里可能有几万篇（IMA 这类外部系统会持续推），无限滚动一路拉下去
 * 会让 DOM 与内存线性增长，最后卡死的是浏览器而不是后端。到上限后停止自动加载，
 * 提示"用文件名搜索缩小范围"——搜索是**服务端**过滤，不要求先加载。
 */
const MAX_LOADED = 500;

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
 * <p><b>大库策略（2026-10-28 按反馈调整）</b>：
 * <ul>
 *   <li>列表<b>服务端分页</b>（`limit/offset`），每页 {@value PAGE_SIZE} 条；</li>
 *   <li>滚动到底<b>自动续拉</b>下一页，不要求用户点页码；</li>
 *   <li>累计到 {@value MAX_LOADED} 条即停止自动加载并提示改用搜索——见 {@code MAX_LOADED} 注释；</li>
 *   <li>搜索是<b>服务端</b>过滤（`q` 传 kb-service），不会为了搜一个文件名先把全库拉下来。</li>
 * </ul>
 */
const Kb = (props: {
  centerContent: DataStudioState['centerContent'];
  addCenterTab: (payload: any) => void;
  /** 已打开的文档要"定位过去"而不是重开一个（重开会把已看的位置重置） */
  activateCenterTab: (id: string) => void;
  /** 批次 E：把当前选中的库写进 model，供 AI Chat 作默认检索范围 */
  saveCurrentKbCollection?: (collection: string) => void;
}) => {
  const { centerContent, addCenterTab, activateCenterTab, saveCurrentKbCollection } = props;

  const [collections, setCollections] = useState<KbCollectionStat[]>([]);
  const [collection, setCollection] = useState<string | undefined>();
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [keyword, setKeyword] = useState<string>('');
  const [query, setQuery] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingDocs, setLoadingDocs] = useState<boolean>(false);
  const [error, setError] = useState<string | undefined>();

  // 请求代次：切换库/改搜索词时递增，丢弃"旧请求后到"的结果（否则会出现 A 库的文档
  // 覆盖 B 库列表这种串台现象）
  const generation = useRef<number>(0);
  const scrollBox = useRef<HTMLDivElement>(null);

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

  /** 拉一页文档；append=true 表示滚动续拉，false 表示换库/换搜索词后重来 */
  const loadDocuments = useCallback(
    async (code: string | undefined, q: string, offset: number, append: boolean) => {
      if (!code) {
        setDocuments([]);
        setTotal(0);
        return;
      }
      const current = ++generation.current;
      setLoadingDocs(true);
      setError(undefined);
      try {
        const res = await listKbDocuments({
          collection: code,
          q,
          sort: 'updated_desc',
          limit: PAGE_SIZE,
          offset
        });
        if (current !== generation.current) {
          return; // 已有更新的请求发出，丢弃本次结果
        }
        if (res?.code !== 0) {
          setDocuments((prev) => (append ? prev : []));
          setTotal(0);
          setError(res?.msg ?? l('datastudio.kb.loadFailed'));
          return;
        }
        const items: KbDocument[] = res?.data?.items ?? [];
        setDocuments((prev) => (append ? [...prev, ...items] : items));
        setTotal(res?.data?.total ?? 0);
      } catch (e: any) {
        if (current !== generation.current) {
          return;
        }
        setDocuments((prev) => (append ? prev : []));
        setTotal(0);
        setError(e?.message ?? l('datastudio.kb.loadFailed'));
      } finally {
        if (current === generation.current) {
          setLoadingDocs(false);
        }
      }
    },
    []
  );

  useEffect(() => {
    loadCollections();
  }, []);

  // 换库 / 换搜索词：从第 0 页重新拉
  useEffect(() => {
    if (scrollBox.current) {
      scrollBox.current.scrollTop = 0;
    }
    loadDocuments(collection, query, 0, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collection, query]);

  /** 滚动到底自动续拉；到上限就停，提示改用搜索 */
  const onScroll = () => {
    const box = scrollBox.current;
    if (!box || loadingDocs || !collection) {
      return;
    }
    if (documents.length >= total || documents.length >= MAX_LOADED) {
      return;
    }
    if (box.scrollTop + box.clientHeight >= box.scrollHeight - 80) {
      loadDocuments(collection, query, documents.length, true);
    }
  };

  const onSearch = (value: string) => {
    setQuery(value.trim());
  };

  const onSelectCollection = (code: string) => {
    if (code === collection) {
      return;
    }
    setCollection(code);
    setKeyword('');
    setQuery('');
    // 批次 E：把"用户正在看哪个库"写进 dva model，供 AI Chat 作默认检索范围。
    // 只在这里（用户主动点选）写入——自动选中的首个库不算绑定，否则用户只是打开面板
    // 就会被动改变问答范围（浏览态与绑定态必须分离，D-K18）。
    saveCurrentKbCollection?.(code);
  };

  /** 库编码 → 名称（打开文档时随 tab 参数带给只读查看器，避免它再查一次） */
  const collectionNames = React.useMemo(() => {
    const map: Record<string, string> = {};
    collections.forEach((item) => {
      map[item.code] = item.name;
    });
    return map;
  }, [collections]);

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
          // 只带 name：标题栏要显示"库名称"，编码对用户没有信息量
          collectionNames: (data.collections ?? doc.collections ?? []).map((code: string) => ({
            code,
            name: collectionNames[code] ?? code
          })),
          chunks: doc.chunks
        }
      };
      addCenterTab(payload);
    } catch (e: any) {
      setError(e?.message ?? l('datastudio.kb.readFailed'));
    }
  };

  const reachedCap = documents.length >= MAX_LOADED && documents.length < total;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', padding: '0 4px' }}>
      {/* 上半栏：库列表（名称 + 篇数 + 最近入库） */}
      <div style={{ flex: '0 0 auto', maxHeight: '40%', overflow: 'auto' }}>
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
                      background: active ? 'rgba(22,119,255,0.12)' : undefined,
                      borderRadius: 4
                    }}
                    onClick={() => onSelectCollection(item.code)}
                  >
                    <div style={{ width: '100%' }}>
                      <Tooltip title={item.description || item.name}>
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

      {/* 下半栏：文档列表 + 服务端搜索 + 滚动续拉 */}
      <div
        style={{
          flex: 1,
          minHeight: 0,
          borderTop: '1px solid rgba(128,128,128,0.24)',
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
            {query
              ? `${l('datastudio.kb.searchHit')} ${total} ${l('datastudio.kb.docsUnit')}`
              : `${l('datastudio.kb.totalPrefix')} ${total} ${l('datastudio.kb.docsUnit')}`}
            {documents.length > 0 && documents.length < total
              ? ` · ${l('datastudio.kb.loadedPrefix')} ${documents.length}`
              : ''}
          </Text>
        </Space>
        <div
          ref={scrollBox}
          onScroll={onScroll}
          style={{ flex: 1, minHeight: 0, overflow: 'auto' }}
        >
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
                  style={{ padding: '4px 8px', cursor: item.openable ? 'pointer' : 'not-allowed' }}
                  onClick={() => openDocument(item)}
                >
                  <div style={{ width: '100%', opacity: item.openable ? 1 : 0.6 }}>
                    <Tooltip title={item.filename}>
                      <Text ellipsis style={{ display: 'block' }}>
                        {getTabIcon(item.filename?.split('.').pop() || 'txt', 14)} {item.filename}
                      </Text>
                    </Tooltip>
                    <Space size={6} wrap>
                      {isProcessing(item.status) && (
                        <ThemeChip tone='info'>{l('datastudio.kb.statusProcessing')}</ThemeChip>
                      )}
                      {item.status === 'failed' && (
                        <ThemeChip tone='danger' title={item.error || ''}>
                          {l('datastudio.kb.statusFailed')}
                        </ThemeChip>
                      )}
                      <Text type='secondary' style={{ fontSize: 12 }}>
                        {item.chunks ?? 0} {l('datastudio.kb.chunksUnit')}
                      </Text>
                      {item.updatedAt ? (
                        <Text type='secondary' style={{ fontSize: 12 }}>
                          {relativeTime(item.updatedAt)}
                        </Text>
                      ) : null}
                      {!item.openable && <ThemeChip>{l('datastudio.kb.notOpenableTag')}</ThemeChip>}
                    </Space>
                  </div>
                </List.Item>
              )}
            />
          )}
          <div style={{ textAlign: 'center', padding: '6px 0' }}>
            {loadingDocs ? (
              <Spin size='small' />
            ) : reachedCap ? (
              <Text type='secondary' style={{ fontSize: 12 }}>
                {l('datastudio.kb.reachedCap')}
              </Text>
            ) : documents.length > 0 && documents.length >= total ? (
              <Text type='secondary' style={{ fontSize: 12 }}>
                {l('datastudio.kb.allLoaded')}
              </Text>
            ) : null}
          </div>
        </div>
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
