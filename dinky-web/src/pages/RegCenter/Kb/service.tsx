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

import { getData, postAll, removeById } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import { KbCollectionSave } from './data.d';

/**
 * 知识库管理接口（批次 D）。
 *
 * <p>与检索/推送接口分属不同前缀（{@code /api/kb/admin/**}）：那边是运行期链路，
 * 这边是管理链路，权限点与校验强度都不同，不要混用。
 *
 * <p>返回原始 {@code {code,msg,data}} 由调用方判 {@code code === 0}：这里的失败消息
 * （"同名知识库已存在"/"只有属主或超级管理员可以修改"）需要原样展示。
 */

export async function listManageableKbCollections() {
  return (await getData(API_CONSTANTS.KB_ADMIN_COLLECTIONS)) as any;
}

export async function saveKbCollection(payload: KbCollectionSave) {
  return (await postAll(API_CONSTANTS.KB_ADMIN_COLLECTIONS, payload)) as any;
}

/** kb-service 有、Dinky 未纳管（超管） */
export async function listKbOrphans() {
  return (await getData(API_CONSTANTS.KB_ADMIN_ORPHANS)) as any;
}

/** 纳管：code 原样沿用 RAG 标识，重编码会让检索对不上 */
export async function adoptKbCollection(params: {
  code: string;
  name?: string;
  description?: string;
  isPublic?: boolean;
}) {
  const query = new URLSearchParams({
    code: params.code,
    name: params.name ?? '',
    description: params.description ?? '',
    isPublic: String(params.isPublic ?? false)
  });
  return (await postAll(`${API_CONSTANTS.KB_ADMIN_ADOPT}?${query.toString()}`, {})) as any;
}

export async function listKbGrants(collectionId: number) {
  return (await getData(`${API_CONSTANTS.KB_ADMIN_COLLECTIONS}/${collectionId}/grants`)) as any;
}

export async function addKbGrants(
  collectionId: number,
  payload: { subjectType: string; subjectIds: number[]; permission: string }
) {
  return (await postAll(
    `${API_CONSTANTS.KB_ADMIN_COLLECTIONS}/${collectionId}/grants`,
    payload
  )) as any;
}

/** 撤销授权（DELETE，路径带 grantId；不要用 getData——那会 405） */
export async function deleteKbGrant(grantId: number) {
  return (await removeById(`${API_CONSTANTS.KB_ADMIN_GRANTS}/${grantId}`, {})) as any;
}
