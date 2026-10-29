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

import { getData, postAll, putDataJson } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import {
  SemanticCreateDirParams,
  SemanticDeleteParams,
  SemanticFeedbackSubmitParams,
  SemanticRenameParams
} from './data.d';

/**
 * 语义项目接口。
 *
 * <p>这里刻意**不用** {@code queryDataByParams}：它会把异常吞成 undefined，而「开关未启用」
 * 恰恰要靠后端 message 提示用户（{@code semantic.disabled}）。因此统一返回原始响应
 * {@code {code, msg, data}}，由调用方判 {@code code === 0}。
 */
export async function listSemanticProjects() {
  return (await getData(API_CONSTANTS.SEMANTIC_PROJECTS)) as any;
}

export async function listSemanticFiles(project: string) {
  return (await getData(API_CONSTANTS.SEMANTIC_FILES, { project })) as any;
}

export async function readSemanticFile(project: string, path: string) {
  return (await getData(API_CONSTANTS.SEMANTIC_FILE, { project, path })) as any;
}

export async function saveSemanticFile(params: {
  project: string;
  path: string;
  content: string;
  baseMtime?: number;
}) {
  return (await postAll(API_CONSTANTS.SEMANTIC_FILE_SAVE, params)) as any;
}

/** 新建目录（父目录必须已存在） */
export async function createSemanticDir(params: SemanticCreateDirParams) {
  return (await postAll(API_CONSTANTS.SEMANTIC_DIR_CREATE, params)) as any;
}

/** 改名（同目录内；目标已存在则拒绝，不覆盖） */
export async function renameSemanticEntry(params: SemanticRenameParams) {
  return (await postAll(API_CONSTANTS.SEMANTIC_RENAME, params)) as any;
}

/** 软删除（移入项目内 .trash，可由运维脚本按保留期清理） */
export async function deleteSemanticEntry(params: SemanticDeleteParams) {
  return (await postAll(API_CONSTANTS.SEMANTIC_DELETE, params)) as any;
}

// ---------------------------------------------------------------------------
// P4 一期：语义资产反馈（口径错了有地方说）
// ---------------------------------------------------------------------------

/**
 * 提交一条语义资产反馈。
 *
 * <p>只传「针对哪个资产的什么意见」；{@code version} / {@code yaml_sha256} 由后端按
 * 提交时的最新发布版本解析——客户端传这两项等于让「是否已随版本失效」的判断失效。
 */
export async function submitSemanticFeedback(params: SemanticFeedbackSubmitParams) {
  return (await postAll(API_CONSTANTS.SEMANTIC_FEEDBACK, params)) as any;
}

/** 反馈列表（默认按时间倒序；status=OPEN 即待办） */
export async function listSemanticFeedback(params: {
  project?: string;
  asset?: string;
  status?: string;
  limit?: number;
}) {
  return (await getData(API_CONSTANTS.SEMANTIC_FEEDBACK_LIST, params)) as any;
}

/** 处理反馈：RESOLVED（已处理）/ IGNORED（已忽略）；需 datastudio:semantic:feedback */
export async function resolveSemanticFeedback(
  id: number,
  params: { status: 'RESOLVED' | 'IGNORED'; note?: string }
) {
  return (await putDataJson(
    `${API_CONSTANTS.SEMANTIC_FEEDBACK_RESOLVE}/${id}/resolve`,
    params
  )) as any;
}

/** 反馈计数（待办角标：open；运营视图：类型分布 / 正负评价） */
export async function getSemanticFeedbackSummary(project?: string) {
  return (await getData(API_CONSTANTS.SEMANTIC_FEEDBACK_SUMMARY, { project })) as any;
}
