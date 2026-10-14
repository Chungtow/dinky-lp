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
  /** private（仅自己）| tenant（同租户共享）| public（全体公开） */
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

/** 可见性（2026-10-14 三档：私有 / 同租户共享 / 全体公开） */
export type SkillVisibility = 'private' | 'tenant' | 'public';

/** 新建 skill 入参 */
export type SkillCreateParams = {
  name: string;
  description: string;
  /**
   * 阶段 4b 补：资产类型——`skill`（默认）或 `doc`（业务背景知识文档）。
   * 二者共用后端同一张表与同一套权限模型，仅存放目录（skills/ 与 docs/）和主文件名不同。
   */
  assetType?: 'skill' | 'doc';
  /** 2026-10-14：可见性；缺省 `private`（后端为空时同样按 private 处理） */
  visibility?: SkillVisibility;
};

/** 修改可见性入参（2026-10-14 三档） */
export type SkillVisibilityParams = {
  id: number;
  visibility: SkillVisibility;
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
