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
import { SkillCreateParams, SkillDetail, SkillInfo, SkillSaveParams } from '@/types/RegCenter/skill';
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
