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

import { STUDIO_MODEL, STUDIO_MODEL_ASYNC } from '@/pages/DataStudio/model';
import { Dispatch } from '@umijs/max';
import {
  CenterTabDTO,
  HandleLayoutChangeDTO,
  ProjectDTO,
  SaveToolbarLayoutDTO,
  SetLayoutDTO,
  UpdateActionDTO
} from '@/pages/DataStudio/type';
import { CONFIG_MODEL_ASYNC } from '@/pages/SettingCenter/GlobalSetting/model';

export const mapDispatchToProps = (dispatch: Dispatch) => {
  return {
    setLayout: (payload: SetLayoutDTO) => {
      dispatch({
        ...payload,
        type: STUDIO_MODEL.setLayout
      });
    },
    handleLayoutChange: (payload: HandleLayoutChangeDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.handleLayoutChange
      }),
    handleToolbarShowDesc: () =>
      dispatch({
        type: STUDIO_MODEL.handleToolbarShowDesc
      }),
    handleThemeCompact: () =>
      dispatch({
        type: STUDIO_MODEL.handleThemeCompact
      }),
    saveToolbarLayout: (payload: SaveToolbarLayoutDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.saveToolbarLayout
      }),
    syncToolbarTabs: () =>
      dispatch({
        type: STUDIO_MODEL.syncToolbarTabs
      }),
    addCenterTab: (payload: CenterTabDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.addCenterTab
      }),
    updateCenterTab: (payload: CenterTabDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.updateCenterTab
      }),
    removeCenterTab: (id: string) =>
      dispatch({
        id,
        type: STUDIO_MODEL.removeCenterTab
      }),
    /**
     * 激活已存在的中间 tab（P3c / BUG-1）：只切 activeTab，不重写 tabs 内容，
     * 因此不会冲掉编辑区里未保存的修改。见 model.tsx 的同名 reducer 注释。
     */
    activateCenterTab: (id: string) =>
      dispatch({
        id,
        type: STUDIO_MODEL.activateCenterTab
      }),
    /**
     * P3b：记录语义面板当前项目。AI Chat 的项目级注入据此跟随语义树切换
     * （切换项目不会切 tab，所以不能只看 tabParams.project）。
     */
    saveCurrentSemanticProject: (project?: string) =>
      dispatch({
        project,
        type: STUDIO_MODEL.saveCurrentSemanticProject
      }),
    updateProject: (payload: ProjectDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.updateProject
      }),
    updateAction: (payload: UpdateActionDTO) =>
      dispatch({
        ...payload,
        type: STUDIO_MODEL.updateAction
      }),

    // effects
    queryFlinkEnv: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryFlinkEnv
      }),
    queryFlinkCluster: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryFlinkCluster
      }),
    queryAlertGroup: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryAlertGroup
      }),
    queryFlinkConfigOptions: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryFlinkConfigOptions
      }),
    queryFlinkUdfOptions: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryFlinkUdfOptions
      }),
    queryDataSourceDataList: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryDataSourceDataList
      }),
    querySuggestions: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.querySuggestions
      }),
    queryUserData: (params: {}) =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryUserData,
        payload: params
      }),
    queryTaskOwnerLockingStrategy: (params: string) =>
      dispatch({
        type: CONFIG_MODEL_ASYNC.queryTaskOwnerLockingStrategy,
        payload: params
      }),
    queryDsConfig: (params: string) =>
      dispatch({
        type: CONFIG_MODEL_ASYNC.queryDsConfig,
        payload: params
      }),
    queryResourceConfig: (params: string) =>
      dispatch({
        type: CONFIG_MODEL_ASYNC.queryResourceConfig,
        payload: params
      }),
    queryResource: () =>
      dispatch({
        type: STUDIO_MODEL_ASYNC.queryResource
      })
  };
};
