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

import type { editor } from 'monaco-editor';
import { createContext } from 'react';

/**
 * 编辑器实例登记表（阶段 1a：AI Chat 需读取「用户在编辑区选中的片段」）。
 *
 * <p>数据开发编辑区（{@code SqlTask}）在 monaco {@code editorDidMount} 时注册自身实例，
 * AI Chat 面板按当前作业的 taskId 取回实例并读取 selection。
 *
 * <p>之所以走 Context 而不是把 editor 写进 dva model：editor 是不可序列化的运行时对象，
 * 放进全局状态会污染状态树并引发无谓重渲染；此处只在「发送提问」时按需读取，无需响应式。
 */
export type EditorRegistry = {
  register: (taskId: string, instance: editor.IStandaloneCodeEditor) => void;
  unregister: (taskId: string) => void;
  /** 取指定作业编辑器中的选中文本；无选中 / 编辑器已卸载时返回空串 */
  getSelection: (taskId?: string) => string;
};

export type DataStudioContextType = {
  theme: 'realDark' | 'light';
  /** 可选：仅数据开发主页面提供；其它复用本 Context 的场景（如血缘图）不存在编辑器 */
  editorRegistry?: EditorRegistry;
};
export const DataStudioContext = createContext({
  theme: 'light'
} as DataStudioContextType);
