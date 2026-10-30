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

import { handleOption, handleRemoveById, queryDataByParams } from '@/services/BusinessCrud';
import { postAll } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';
import {
  SkillCreateParams,
  SkillDetail,
  SkillFileNode,
  SkillFileRenameParams,
  SkillFileTargetParams,
  SkillFileWriteParams,
  SkillHandleValidation,
  SkillInfo,
  SkillSaveParams,
  SkillVisibilityParams
} from '@/types/RegCenter/skill';
import { l } from '@/utils/intl';

/** 当前用户可见的 skill 列表 */
export const listSkills = async (): Promise<SkillInfo[]> => {
  const data = await queryDataByParams<SkillInfo[]>(API_CONSTANTS.SKILL_LIST);
  return data ?? [];
};

/** skill 详情（含 SKILL.md 正文） */
export const detailSkill = async (id: number): Promise<SkillDetail | undefined> =>
  queryDataByParams<SkillDetail>(API_CONSTANTS.SKILL_DETAIL, { id });

/**
 * 新建 skill：后端会建目录 `/skills/u<ownerId>/<name>/`（2026-10-14 起按属主隔离，
 * 避免不同用户建同名资产时复用同一目录并撞资源表唯一键）并写入 SKILL.md 模板。
 */
export const createSkill = async (params: SkillCreateParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_CREATE, l('pages.skill.create'), params, cb);

/**
 * 修改可见性（2026-10-14 三档：private / tenant / public）。
 * 仅属主可调；设为「全体公开」还需 `registration:skill:share` 权限（后端二次校验）。
 */
export const updateSkillVisibility = async (params: SkillVisibilityParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_VISIBILITY, l('pages.skill.visibilityChange'), params, cb);

/** 保存 SKILL.md 正文（仅属主；后端会重新解析 frontmatter 并回写元数据） */
export const saveSkill = async (params: SkillSaveParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_SAVE, l('pages.skill.save'), params, cb);

/**
 * 校验正文里的 `@` 句柄（保存后校验 / E4）。
 *
 * <p><b>只读、不阻断</b>：后端把"业务失败"表达为 `handles[].status`，接口本身恒成功；
 * 前端拿到结果后只做提示（Monaco marker / 通知），**绝不影响保存结果**。
 *
 * @param content     SKILL.md 全文
 * @param contentHash 正文指纹（原样回显，用于丢弃"用户已继续编辑"的过期结果）
 */
export const validateSkillContent = async (
  content: string,
  contentHash?: string
): Promise<SkillHandleValidation | undefined> => {
  try {
    const res: any = await postAll(API_CONSTANTS.SKILL_VALIDATE, { content, contentHash });
    return res?.code === 0 ? (res.data as SkillHandleValidation) : undefined;
  } catch (e) {
    // 校验是"加分项"：失败不打扰用户（保存已经成功）
    return undefined;
  }
};

/** 删除 skill（同时删除其资源目录与文件；前端需二次确认） */
export const removeSkill = async (id: number, cb?: () => void) =>
  handleRemoveById(API_CONSTANTS.SKILL_REMOVE, id, cb);

// ==================== 文件管理（阶段 4b：目录树与 references 子文件） ====================

/** skill 内文件树（目录 + 文件；只含相对路径） */
export const listSkillFiles = async (id: number): Promise<SkillFileNode[]> => {
  const data = await queryDataByParams<SkillFileNode[]>(API_CONSTANTS.SKILL_FILES, { id });
  return data ?? [];
};

/** 读取 skill 内某个文件（relativePath 留空表示读主文件 SKILL.md / DOC.md） */
export const readSkillFile = async (id: number, relativePath?: string): Promise<string> => {
  const data = await queryDataByParams<string>(API_CONSTANTS.SKILL_FILE_READ, { id, relativePath });
  return data ?? '';
};

/** 写入 skill 内文件（新建或覆盖；父目录不存在会自动创建） */
export const writeSkillFile = async (params: SkillFileWriteParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_FILE_WRITE, l('pages.skill.file.write'), params, cb);

/** 新建 skill 内目录（幂等） */
export const mkdirSkill = async (params: SkillFileTargetParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_FILE_MKDIR, l('pages.skill.file.mkdir'), params, cb);

/**
 * 重命名 skill 内文件（批次 F；仅属主）。
 *
 * <p>只改文件名不换目录：{@code newName} 不允许含 `/`；主文件（SKILL.md / DOC.md）后端拒绝。
 * 服务端实现是"读旧正文 → 写新路径 → 删旧路径"，失败会自动回滚新文件。
 */
export const renameSkillFile = async (params: SkillFileRenameParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_FILE_RENAME, l('pages.skill.file.rename'), params, cb);

/** 删除 skill 内文件或目录（目录会被递归删除） */
export const removeSkillFile = async (params: SkillFileTargetParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_FILE_REMOVE, l('pages.skill.file.remove'), params, cb);
