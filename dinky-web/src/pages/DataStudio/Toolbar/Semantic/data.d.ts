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

export type SemanticProject = {
  name: string;
  fileCount?: number;
  lastModified?: number;
};

/** 语义项目文件树节点（path 为相对项目根） */
export type SemanticFileNode = {
  path: string;
  isDir?: boolean;
  size?: number;
  readOnly?: boolean;
  lastModified?: number;
};

/** 语义文件内容（modifiedAt 保存时回传为 baseMtime） */
export type SemanticFileContent = {
  path: string;
  content: string;
  size?: number;
  modifiedAt: number;
};

/** 保存请求 */
export type SemanticFileSaveParams = {
  project: string;
  path: string;
  content: string;
  baseMtime?: number;
};

/** 新建目录请求 */
export type SemanticCreateDirParams = {
  project: string;
  path: string;
};

/** 改名请求（同目录内，目标存在则拒绝，不覆盖） */
export type SemanticRenameParams = {
  project: string;
  path: string;
  newName: string;
  isDir?: boolean;
  baseMtime?: number;
};

/** 软删请求（默认移入项目内 .trash，可恢复） */
export type SemanticDeleteParams = {
  project: string;
  path: string;
  recursive?: boolean;
  baseMtime?: number;
};

// ---------------------------------------------------------------------------
// P4 一期：语义资产反馈
// ---------------------------------------------------------------------------

/** 反馈状态机：OPEN → RESOLVED / IGNORED */
export type SemanticFeedbackStatus = 'OPEN' | 'RESOLVED' | 'IGNORED';

/** 反馈问题类型（后端不校验枚举，这里列约定值） */
export type SemanticFeedbackType = 'WRONG_DEFINITION' | 'WRONG_DIMENSION' | 'OUTDATED' | 'OTHER';

/** 语义资产类型（决定发布索引里取表切片还是指标切片） */
export type SemanticAssetType = 'TABLE' | 'METRIC';

/** 一条反馈（列表 / 待办用） */
export type SemanticFeedback = {
  id: number;
  projectName: string;
  assetName: string;
  assetType?: SemanticAssetType;
  /** 提交时的发布版本（后端解析，未发布则为空） */
  version?: string;
  /** 提交时该资产 YAML 的 sha256（后端解析，索引未就绪时为空） */
  yamlSha256?: string;
  rating: number;
  feedbackType?: SemanticFeedbackType;
  content?: string;
  sessionId?: string;
  userId?: number;
  status: SemanticFeedbackStatus;
  resolverId?: number;
  resolveNote?: string;
  resolveTime?: string;
  createTime?: string;
};

/** 提交反馈请求（版本 / 指纹刻意不传，由后端解析） */
export type SemanticFeedbackSubmitParams = {
  projectName: string;
  assetName: string;
  assetType?: SemanticAssetType;
  rating: number;
  feedbackType?: SemanticFeedbackType;
  content?: string;
  sessionId?: string;
};

/** 反馈计数（待办角标 + 运营视图） */
export type SemanticFeedbackSummary = {
  total: number;
  open: number;
  resolved: number;
  ignored: number;
  recent7d?: number;
  positive?: number;
  negative?: number;
  byFeedbackType?: Record<string, number>;
};
