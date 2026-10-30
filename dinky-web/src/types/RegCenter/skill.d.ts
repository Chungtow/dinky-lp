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
  /**
   * 批次 F 乐观锁：编辑起点的 `version`。非空且与库里不一致时后端拒绝保存
   * （skill 无 git 历史、无 .trash，覆盖后无从找回）。为空表示不校验。
   */
  baseVersion?: number;
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
  /** 批次 F 乐观锁：**仅主文件**生效（附件不递增 version，后端会忽略） */
  baseVersion?: number;
};

/** skill 内路径目标（新建目录 / 删除） */
export type SkillFileTargetParams = {
  skillId: number;
  relativePath: string;
};

/**
 * 重命名 skill 内文件（批次 F）。
 *
 * <p>{@code newName} 只能是纯文件名（不含 `/`）：一期只改名不换目录；
 * 主文件（SKILL.md / DOC.md）后端拒绝改名。
 */
export type SkillFileRenameParams = {
  skillId: number;
  relativePath: string;
  newName: string;
};

/** 资产类型（阶段 4b：skill = 流程知识；doc = 业务背景知识） */
export type SkillAssetType = 'skill' | 'doc';

/**
 * `@` 句柄校验（与后端 `SkillHandleValidationVO` 一一对应）。
 *
 * <p>`syntax` 决定渲染强度：`explicit`（反引号包裹）按状态告警；
 * `bare`（历史裸写）**只汇总成一条 info**，不逐个画波浪线——否则存量 skill 一打开满屏黄线。
 */
export type SkillHandleStatus = 'ok' | 'not_found' | 'denied' | 'unknown_type';

export type SkillHandleValidationHandle = {
  /** 原文片段（explicit 含反引号） */
  raw: string;
  syntax: 'explicit' | 'bare';
  type: string;
  code: string;
  /** 1-based，指向 `@` 字符 */
  line: number;
  /** 1-based，指向 `@` 字符（不含反引号） */
  column: number;
  status: SkillHandleStatus;
  title?: string | null;
  message?: string | null;
};

export type SkillHandleValidation = {
  summary: {
    total: number;
    ok: number;
    notFound: number;
    denied: number;
    unknownType: number;
    /** 裸写（未用反引号包裹）的条数——只给 info 提示 */
    bareOnly: number;
  };
  handles: SkillHandleValidationHandle[];
  /** 入参 contentHash 的原样回显：与当前正文指纹不一致时说明结果已过期 */
  contentHash?: string | null;
};
