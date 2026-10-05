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
import { API_CONSTANTS } from '@/services/endpoints';
import {
  SkillCreateParams,
  SkillDetail,
  SkillFileNode,
  SkillFileTargetParams,
  SkillFileWriteParams,
  SkillInfo,
  SkillSaveParams
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

/** 新建 skill：后端会建目录 /skills/<name>/ 并写入 SKILL.md 模板 */
export const createSkill = async (params: SkillCreateParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_CREATE, l('pages.skill.create'), params, cb);

/** 保存 SKILL.md 正文（仅属主；后端会重新解析 frontmatter 并回写元数据） */
export const saveSkill = async (params: SkillSaveParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_SAVE, l('pages.skill.save'), params, cb);

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

/** 删除 skill 内文件或目录（目录会被递归删除） */
export const removeSkillFile = async (params: SkillFileTargetParams, cb?: () => void) =>
  handleOption(API_CONSTANTS.SKILL_FILE_REMOVE, l('pages.skill.file.remove'), params, cb);
