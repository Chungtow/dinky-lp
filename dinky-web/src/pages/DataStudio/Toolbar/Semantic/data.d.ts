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

export type SemanticProject = {
  name: string;
  fileCount?: number;
  lastModified?: number;
};

/** 语义项目文件树节点（path 为相对项目根） */
export type SemanticFileNode = {
  path: string;
  isDir?: boolean;
  size?: number;
  readOnly?: boolean;
  lastModified?: number;
};

/** 语义文件内容（modifiedAt 保存时回传为 baseMtime） */
export type SemanticFileContent = {
  path: string;
  content: string;
  size?: number;
  modifiedAt: number;
};

/** 保存请求 */
export type SemanticFileSaveParams = {
  project: string;
  path: string;
  content: string;
  baseMtime?: number;
};
