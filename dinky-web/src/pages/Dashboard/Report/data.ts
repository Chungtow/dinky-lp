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

export type ReportData = {
  id?: number;
  name?: string;
  remark?: string;
  resourceId?: number;
  resourcePath?: string;
  /** 资源文件名（列表展示用，后端按 resource_id 反查填充） */
  resourceName?: string;
  sortNum?: number;
  enabled?: boolean;
  tenantId?: number;
  /** 授权角色（详情回显 / 保存时全量替换） */
  roleIds?: number[];
  createTime?: string;
  updateTime?: string;
};

/** 资源中心节点（只保留前端用得到的字段） */
export type HtmlResourceData = {
  id: number;
  fileName?: string;
  fullName?: string;
  isDirectory?: boolean;
  size?: number;
  pid?: number;
  children?: HtmlResourceData[];
};

/** 报表单文件大小上限（与后端 ReportServiceImpl.MAX_REPORT_SIZE 保持一致：20MB） */
export const MAX_REPORT_SIZE = 20 * 1024 * 1024;
