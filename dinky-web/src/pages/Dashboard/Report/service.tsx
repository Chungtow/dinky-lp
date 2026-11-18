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

import { ReportData } from '@/pages/Dashboard/Report/data';
import {
  getDataByParamsReturnResult,
  handleAddOrUpdate,
  handleDeleteOperation
} from '@/services/BusinessCrud';
import { API_CONSTANTS } from '@/services/endpoints';

/**
 * 当前用户可见的报表列表
 *
 * <p>可见性由后端判定：超管/租户管理员看全部；其余人看「被授权角色的」+「未配置授权的」。
 */
export const getReportList = () => {
  return getDataByParamsReturnResult(API_CONSTANTS.REPORT_LIST);
};

/** 报表详情（含 roleIds，供管理弹窗回显） */
export const getReportDetail = (id: number) => {
  return getDataByParamsReturnResult(API_CONSTANTS.REPORT_DETAIL, { id });
};

/** 新增/修改报表（roleIds 全量替换） */
export const saveOrUpdateReport = (data: ReportData) => {
  return handleAddOrUpdate(API_CONSTANTS.REPORT_SAVE_OR_UPDATE, data);
};

/** 删除报表（含授权关系） */
export const deleteReport = (id: number) => {
  return handleDeleteOperation(API_CONSTANTS.REPORT_DELETE, { id }, 'Report');
};

/**
 * 生成带签名的预览地址（短期有效）
 *
 * <p>iframe 只用返回的地址：沙箱下 Cookie 行为跨浏览器不一致，靠签名换取免登录预览，
 * 同时不把完全免登录的端点暴露出去。
 */
export const getReportPreviewUrl = (id: number) => {
  return getDataByParamsReturnResult(API_CONSTANTS.REPORT_PREVIEW_URL, { id });
};

/** HTML 资源树（后端已过滤：目录 + .html/.htm 文件） */
export const getHtmlResources = () => {
  return getDataByParamsReturnResult(API_CONSTANTS.REPORT_HTML_RESOURCES);
};
