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

export const SEMANTIC_EDITOR_KEY_PREFIX = 'semantic:';

/** 语义文件编辑器键：{@code semantic:<project>:<relativePath>} */
export const semanticEditorKey = (project?: string, path?: string): string | undefined =>
  project && path ? `${SEMANTIC_EDITOR_KEY_PREFIX}${project}:${path}` : undefined;

export const SKILL_EDITOR_KEY_PREFIX = 'skill:';

/**
 * skill 文件编辑器键：{@code skill:<skillId>:<relativePath>}（批次 F）。
 *
 * <p>为什么必须单独给键：AI Chat 的 Craft / Fix 链路用「编辑器键」读取<b>原文</b>作为 diff 左栏，
 * 并以同一个键回写编辑器。缺了这个键，skill tab 上 `getContent()` 恒为空 → 对比弹窗左侧空白、
 * 采纳也写不进去（与 2026-10-21 语义文件 tab 的问题同一根因，见 U9 修复记录）。
 */
export const skillEditorKey = (skillId?: number | string, path?: string): string | undefined => {
  const id = skillId === undefined || skillId === null || skillId === '' ? undefined : String(skillId);
  return id && path ? `${SKILL_EDITOR_KEY_PREFIX}${id}:${path}` : undefined;
};

/** 当前中心 tab 对应的编辑器键：作业 tab 用 taskId，语义文件用项目+路径，skill 文件用 id+路径 */
export const resolveEditorKey = (tabParams?: Record<string, any>): string | undefined =>
  tabParams?.taskId ??
  semanticEditorKey(tabParams?.project, tabParams?.path) ??
  // skill tab 的主文件可能只有 mainFile 标记而相对路径为空，两侧必须用同一口径
  skillEditorKey(
    tabParams?.skillId,
    tabParams?.relativePath || (tabParams?.mainFile ? 'SKILL.md' : undefined)
  );
