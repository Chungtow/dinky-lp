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

import { getData, postAll } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';

/**
 * 语义项目接口。
 *
 * <p>这里刻意**不用** {@code queryDataByParams}：它会把异常吞成 undefined，而「开关未启用」
 * 恰恰要靠后端 message 提示用户（{@code semantic.disabled}）。因此统一返回原始响应
 * {@code {code, msg, data}}，由调用方判 {@code code === 0}。
 */
export async function listSemanticProjects() {
  return (await getData(API_CONSTANTS.SEMANTIC_PROJECTS)) as any;
}

export async function listSemanticFiles(project: string) {
  return (await getData(API_CONSTANTS.SEMANTIC_FILES, { project })) as any;
}

export async function readSemanticFile(project: string, path: string) {
  return (await getData(API_CONSTANTS.SEMANTIC_FILE, { project, path })) as any;
}

export async function saveSemanticFile(params: {
  project: string;
  path: string;
  content: string;
  baseMtime?: number;
}) {
  return (await postAll(API_CONSTANTS.SEMANTIC_FILE_SAVE, params)) as any;
}
