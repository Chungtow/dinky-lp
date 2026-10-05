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

export type SkillInfo = {
  id: number;
  name: string;
  description?: string;
  dirFullName?: string;
  ownerId?: number;
  tenantId?: number;
  /** private | tenant */
  visibility?: string;
  enabled?: boolean;
  /** local | third_party */
  source?: string;
  assetType?: string;
  version?: number;
  contentHash?: string;
  createTime?: string;
  updateTime?: string;
};

/** Skill 详情（元数据 + SKILL.md 正文） */
export type SkillDetail = {
  skill: SkillInfo;
  content?: string;
  /** 当前用户是否可编辑（仅属主为 true） */
  editable?: boolean;
};

/** 新建 skill 入参 */
export type SkillCreateParams = {
  name: string;
  description: string;
};

/** 保存 skill 正文入参 */
export type SkillSaveParams = {
  id: number;
  content: string;
};

/** skill 内文件树节点（阶段 4b；只含相对路径，前端不感知存储绝对路径） */
export type SkillFileNode = {
  name: string;
  relativePath: string;
  directory: boolean;
  /** 是否为主文件（SKILL.md / DOC.md） */
  mainFile?: boolean;
  size?: number;
  updateTime?: string;
  children?: SkillFileNode[];
};

/** 写 skill 内文件入参 */
export type SkillFileWriteParams = {
  skillId: number;
  relativePath: string;
  content: string;
};

/** skill 内路径目标（新建目录 / 删除） */
export type SkillFileTargetParams = {
  skillId: number;
  relativePath: string;
};

/** 资产类型（阶段 4b：skill = 流程知识；doc = 业务背景知识） */
export type SkillAssetType = 'skill' | 'doc';
