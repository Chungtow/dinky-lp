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

import { getData } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { KbDocumentQuery } from './data.d';

/**
 * 知识库浏览接口（批次 B，全部只读）。
 *
 * <p>与 Semantic 的 service 同一约定：<b>统一返回原始响应</b> {@code {code, msg, data}}，
 * 由调用方判 {@code code === 0}。不用 `queryDataByParams`——它会把异常吞成 undefined，
 * 而这里的错误消息（"无权访问该知识库"/"原文未保留"）恰恰是要原样展示给用户的。
 *
 * <p><b>浏览器永不直连 kb-service</b>：这三个地址都在 Dinky 后端代理层，API Key 不出服务端。
 */

/** 可见库 + 计数（上半栏） */
export async function listKbCollectionStats() {
  return (await getData(API_CONSTANTS.KB_COLLECTION_STATS)) as any;
}

/** 库内文档列表（下半栏；分页与搜索都在服务端） */
export async function listKbDocuments(params: KbDocumentQuery) {
  return (await getData(API_CONSTANTS.KB_DOCUMENTS, {
    collection: params.collection,
    q: params.q,
    // 显式传排序：kb-service 默认 doc_id，不传就是哈希乱序（实施计划 D-K6）
    sort: params.sort ?? 'updated_desc',
    limit: params.limit ?? 20,
    offset: params.offset ?? 0
  })) as any;
}

/** 文档原文（只读打开） */
export async function readKbDocument(docId: string) {
  return (await getData(`${API_CONSTANTS.KB_DOCUMENTS}/${docId}/content`)) as any;
}
