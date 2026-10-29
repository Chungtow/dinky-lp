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

export type KbCollection = {
  id: number;
  /** 库编码：系统生成 / 纳管时沿用 RAG 标识，**永不可改** */
  code: string;
  name: string;
  description?: string;
  ownerId?: number;
  tenantId?: number;
  /** 公开 = 全体可见；但公开**不等于可写** */
  isPublic?: boolean;
  /** false = 停用（草稿态，不参与候选与检索） */
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
};

export type KbCollectionSave = {
  /** 空 = 新建 */
  id?: number;
  name: string;
  description?: string;
  isPublic?: boolean;
  enabled?: boolean;
};

/** kb-service 有、Dinky 未纳管的库 */
export type KbOrphan = {
  code: string;
  docs: number;
  chunks: number;
  lastTs: number;
  sampleFilename?: string;
};

export type KbGrant = {
  id: number;
  collectionId: number;
  /** tenant / role / user */
  subjectType: string;
  subjectId: number;
  /** view / write */
  permission: string;
  createTime?: string;
};
