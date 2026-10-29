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

  /**
   * 取指定作业编辑器的<b>全文</b>（阶段 2：Craft 模式需以编辑器当前内容作为 diff 基准）。
   *
   * <p>与 {@link getSelection} 的区别：本方法返回整个 model 的内容，而非选中片段。
   */
  getContent: (taskId?: string) => string;

  /**
   * 以<b>整块替换</b>的方式把内容写入编辑器（阶段 2：Craft 采纳后落地）。
   *
   * <p><b>必须走 {@code executeEdits} 而非 {@code setValue}</b>：后者会重置 monaco 的 undo 栈，
   * 导致用户 Ctrl+Z 无法撤销 AI 的改动（阶段 2 设计要求「写入必须保留 undo 栈」）。
   *
   * <p>写入后由 monaco 的 {@code onDidChangeModelContent} 驱动 CodeEdit 的 onChange，
   * 进而更新 dva {@code tabs[].params.statement}，因此本方法<b>不</b>手动改任何状态。
   *
   * @return 是否写入成功；编辑器未注册 / 已 dispose / content 为空时返回 false
   */
  applyFullContent: (taskId?: string, content?: string) => boolean;

  /**
   * 以<b>选中范围</b>为界替换内容（阶段 2b：局部改写采纳）。
   *
   * <p>与 {@link applyFullContent}（整块全文）区分：本方法只替换用户当前选中的片段，
   * 选区之外一个字符都不动；无选中 / 编辑器未注册时返回 false。
   * 同样走 {@code executeEdits} 以保留 monaco undo 栈（采纳后仍可 Ctrl+Z）。
   */
  applyToSelection: (taskId?: string, text?: string) => boolean;

  /**
   * 订阅指定作业编辑器的<b>选区变化</b>（阶段 2b：选区浮层入口需响应式显示）。
   *
   * @param taskId 作业 id
   * @param listener 选区文本变化回调（无选中时回传空串）
   * @return 取消订阅函数
   */
  onSelectionChange: (taskId: string | undefined, listener: (text: string) => void) => () => void;
};

export type DataStudioContextType = {
  theme: 'realDark' | 'light';
  /** 可选：仅数据开发主页面提供；其它复用本 Context 的场景（如血缘图）不存在编辑器 */
  editorRegistry?: EditorRegistry;
};
export const DataStudioContext = createContext({
  theme: 'light'
} as DataStudioContextType);
