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

export type KbCollectionStat = {
  /** 库编码（永不展示裸 code，见先行记录 §10.5；仅排障时显示） */
  code: string;
  /** 显示名 */
  name: string;
  /** 一句话说明 */
  description?: string;
  /** 文档数（kb-service 实时聚合；0 也可能是内容侧不可用） */
  docs: number;
  /** 切片数 */
  chunks: number;
  /** 最近入库时间（epoch 秒；0 = 空库） */
  lastTs: number;
};

/** 库内一篇文档 */
export type KbDocument = {
  /** sha256(正文)，全局唯一；打开原文用 */
  docId: string;
  filename: string;
  size?: number;
  contentType?: string;
  /** queued / parsing / embedding / ready / failed */
  status: string;
  chunks?: number;
  /** epoch 秒 */
  updatedAt?: number;
  /** 所属库编码（可能多归属） */
  collections?: string[];
  /** 解析失败原因（status=failed 时有值） */
  error?: string;
  /** 是否可在编辑区只读打开（服务端判定：仅 Markdown / 代码类文本） */
  openable: boolean;
};

/** 文档列表分页结果 */
export type KbDocumentPage = {
  collection: string;
  /** 命中总数（跨页），不是本页条数 */
  total: number;
  items: KbDocument[];
  limit: number;
  offset: number;
};

/** 文档原文（只读） */
export type KbDocumentContent = {
  docId: string;
  filename: string;
  contentType?: string;
  content: string;
  /** 正文字符数 */
  size: number;
  collections?: string[];
};

/** 列表查询参数 */
export type KbDocumentQuery = {
  collection: string;
  q?: string;
  /** 必须显式传 updated_desc，否则 kb-service 按 doc_id 哈希序返回（D-K6） */
  sort?: string;
  limit?: number;
  offset?: number;
};

/** 可推送的库（只含有写权限的：属主 或 grant(write)） */
export type KbCollectionBrief = {
  code: string;
  name: string;
  description?: string;
};

/** 推送请求（服务端注入 provider 与 path，前端不可传） */
export type KbPushRequest = {
  filename: string;
  content: string;
  collections: string[];
  /** semantic / skill / resource → 服务端映射成 path 标记 */
  source: 'semantic' | 'skill' | 'resource';
  externalId?: string;
};

/**
 * 推送结果。
 *
 * <p>`dedup=false` 是新文档（异步解析，要轮询 status）；`dedup=true` 表示同内容已存在、
 * **未重复入库**，`collections` 是合并后的最终归属。
 */
export type KbPushResult = {
  docId: string;
  dedup: boolean;
  status: string;
  collections: string[];
  message: string;
};
