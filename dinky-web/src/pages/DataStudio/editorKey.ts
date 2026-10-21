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

/** 当前中心 tab 对应的编辑器键：作业 tab 用 taskId，语义文件 tab 用项目+路径 */
export const resolveEditorKey = (tabParams?: Record<string, any>): string | undefined =>
  tabParams?.taskId ?? semanticEditorKey(tabParams?.project, tabParams?.path);
