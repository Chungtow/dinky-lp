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

import { LoadCustomEditorLanguage } from '@/components/CustomEditor/languages';
import { DIFF_EDITOR_PARAMS } from '@/pages/DataStudio/CenterTabContent/SqlTask/constants';
import { convertCodeEditTheme } from '@/utils/function';
import { DiffEditor, loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import React, { memo } from 'react';

loader.config({ monaco });

export type SkillDiffProps = {
  /** 变更前内容（左栏）；新建文件时为空串 */
  original: string;
  /** 变更后内容（右栏），即用户确认后将写入文件的内容 */
  modified: string;
  /** 目标相对路径（仅用于按扩展名选高亮语言） */
  path?: string;
  height?: string;
};

/** 与 SkillFile 编辑 tab 同口径：按扩展名给 monaco 语言 */
const languageOf = (path?: string): string => {
  const lower = (path ?? '').toLowerCase();
  if (lower.endsWith('.md')) return 'markdown';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.json')) return 'json';
  if (lower.endsWith('.sql')) return 'sql';
  if (lower.endsWith('.py')) return 'python';
  return 'plaintext';
};

/**
 * 写 skill 文件确认弹窗里的「原文 vs 改写后」只读对比。
 *
 * <p><b>为什么单独抽一个组件</b>：此前确认弹窗（kind=skill_file）只渲染 {@code afterContent}
 * 一个 {@code <pre>}，用户看不到原文，无法判断 AI 到底改了哪几行——与 2026-10-21 语义文件
 * Craft diff「左侧为空」同一类缺陷（根因都是"原文来源缺失/未渲染"）。这里复用
 * {@link CraftDiff} 同一个 {@code DiffEditor} 与自定义语言注册，<b>零新增依赖</b>。
 *
 * <p>只读：写入动作本身由外层弹窗的「确认 / 拒绝」按钮决定，这里不提供任何采纳入口，
 * 避免出现两套确认路径（关掉弹窗 ≠ 写入）。
 */
const SkillDiff: React.FC<SkillDiffProps> = (props) => {
  const { original, modified, path, height = '40vh' } = props;

  return (
    <div style={{ height }}>
      <DiffEditor
        {...DIFF_EDITOR_PARAMS}
        height={'100%'}
        language={languageOf(path)}
        beforeMount={(monacoIns) =>
          LoadCustomEditorLanguage(monacoIns.languages, monacoIns.editor)
        }
        original={original}
        modified={modified}
        theme={convertCodeEditTheme()}
      />
    </div>
  );
};

export default memo(SkillDiff);
