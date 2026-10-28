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

import { DataStudioActionType, ToolbarSelect } from '@/pages/DataStudio/data.d';
import { Effect, Reducer } from '@@/plugin-dva/types';
import { createModelTypes } from '@/utils/modelUtils';
import { leftDefaultShowTab, ToolbarRoutes } from '@/pages/DataStudio/Toolbar/ToolbarRoute';
import { layout } from '@/pages/DataStudio/ContentLayout';
import {
  CenterTabDTO,
  FlinkCluster,
  HandleLayoutChangeDTO,
  ProjectDTO,
  ProjectState,
  SaveToolbarLayoutDTO,
  SetLayoutDTO,
  TaskState,
  TempData,
  TempDataDTO,
  UpdateActionDTO
} from '@/pages/DataStudio/type';
import { LayoutBase } from 'rc-dock/src/DockData';
import { getAllPanel } from '@/pages/DataStudio/function';
import { ToolbarPosition } from '@/pages/DataStudio/Toolbar/data.d';
import { findToolbarPositionByTabId } from '@/pages/DataStudio/DockLayoutFunction';
import { EnvType } from '@/pages/DataStudio/type';
import {
  getClusterConfigurationData,
  getEnvData,
  getFlinkConfigs,
  getFlinkUdfOptions,
  getResourceData,
  getSessionData,
  querySuggestionData
} from '@/pages/DataStudio/service';
import { Alert, ResourceInfo } from '@/types/RegCenter/data';
import { showAlertGroup } from '@/pages/RegCenter/Alert/AlertGroup/service';
import { DefaultOptionType } from 'antd/es/select';
import { getDataSourceList } from '@/pages/DataStudio/Toolbar/DataSource/service';
import { getUserData } from '@/pages/DataStudio/service';
import { UserBaseInfo } from '@/types/AuthCenter/data.d';
import { queryDataByParams } from '@/services/BusinessCrud';
import { API_CONSTANTS } from '@/services/endpoints';

/**
 * @description:
 *  zh: 中间tab 类型
 *  en: Center tab type
 */
export type CenterTabType =
  | 'web'
  | 'task'
  | 'dataSource'
  | 'resource'
  | 'semanticFile'
  /** 批次 B：知识库文档只读查看（正文来自 /api/kb/documents/{id}/content） */
  | 'kbFile';

/**
 * @description:
 *  zh: 中间 tab 信息
 *  en: Center tab informations
 */
export type CenterTab = {
  id: string;
  /**
   * zh: tab 类型
   * en: tab Type
   */
  tabType: CenterTabType;
  /**
   * zh: tab 标题
   * en: tab title
   */
  title: string;
  /**
   * zh: tab 是否存在更新
   * en: tab is updated
   */
  isUpdate: boolean;
  /**
   * zh: tab 参数
   * en: tab params
   */
  params: Record<string, any>;
};

/**
 * @description:
 *  zh: 布局状态
 *  en: Layout state
 */
export type DataStudioState = {
  /**
   * zh: 语义面板当前选中的 dbt 语义项目（P3b，供 AI Chat 项目级注入跟随语义树切换）
   * en: Currently selected dbt semantic project in the semantic panel (P3b)
   */
  currentSemanticProject?: string;
  /**
   * zh: 左侧栏「知识库」tab 当前选中的库（批次 E，供 AI Chat 默认绑定检索范围）
   * en: Currently selected knowledge base collection in the KB panel (batch E, default retrieval scope for AI Chat)
   */
  currentKbCollection?: string;
  /**
   * zh: 基础布局数据
   * en: Basic layout data
   */
  layoutData: LayoutBase;
  layoutSize: {
    leftTop: number;
    leftBottom: number;
    right: number;
    centerContent?: number | undefined;
  };
  /**
   * zh: 工具栏布局
   * en: Toolbar layouts
   */
  toolbar: {
    /**
     * zh: 是否显示描述
     * en: Whether to show descriptions
     */
    showDesc: boolean;
    /**
     * zh: 是否显示激活的tab
     * en: Whether to show the active tabs
     */
    showActiveTab: boolean;
    /**
     * zh: 左上角的tab
     * en: Left top tab
     */
    leftTop: ToolbarSelect;
    /**
     * zh: 左下角的tab
     * en: Left bottom tab
     */
    leftBottom: ToolbarSelect;
    /**
     * zh: 右边的tab
     * en: Right tabs
     */
    right: ToolbarSelect;
    /**
     * zh: 中间内容
     * en: Center contents
     */
    centerContent: ToolbarSelect;
    /**
     * zh: 项目数据状态
     * en: Project data status
     */
    project: ProjectState;
  };
  theme: {
    // 是否开启紧凑模式
    compact: boolean;
  };

  /**
   * zh: 中间内容 tab 列表
   * en: Center content tab list
   */
  centerContent: {
    /**
     * zh: tab 列表
     * en: Tab list
     */
    tabs: CenterTab[];
    /**
     * zh: 激活的tab
     * en: Active tab
     */
    activeTab?: string | undefined;
  };
  /**
   * zh: 记录按钮操作
   * en: Record button operations
   */
  action: {
    /**
     * zh: 操作类型
     * en: Action type
     */
    actionType?: DataStudioActionType;
    /**
     * zh: 参数
     * en: Params
     */
    params?: Record<string, any> | TaskState;
  };
  /**
   * zh: 临时数据
   * en: Temporary data
   */
  tempData: TempData;
  users: UserBaseInfo.User[];
};

export type StudioModelType = {
  namespace: string;
  state: DataStudioState;
  effects: {
    queryFlinkEnv: Effect;
    queryFlinkCluster: Effect;
    queryAlertGroup: Effect;
    queryFlinkConfigOptions: Effect;
    queryFlinkUdfOptions: Effect;
    queryDataSourceDataList: Effect;
    querySuggestions: Effect;
    queryUserData: Effect;
    queryResource: Effect;
  };
  reducers: {
    // 保存布局
    setLayout: Reducer<DataStudioState, SetLayoutDTO>;
    // 监听布局变化
    handleLayoutChange: Reducer<DataStudioState, HandleLayoutChangeDTO>;
    // 操作工具栏显示描述
    handleToolbarShowDesc: Reducer<DataStudioState>;
    // 切换紧凑模式
    handleThemeCompact: Reducer<DataStudioState>;
    // 保存工具栏布局
    saveToolbarLayout: Reducer<DataStudioState, SaveToolbarLayoutDTO>;
    // 对齐工具栏 tab 列表（补齐新增 tab / 剔除已删除 tab）
    syncToolbarTabs: Reducer<DataStudioState>;
    // 更新中间tab
    updateCenterTab: Reducer<DataStudioState, CenterTabDTO>;
    // 添加中间tab
    addCenterTab: Reducer<DataStudioState, CenterTabDTO>;
    // 删除中间tab
    removeCenterTab: Reducer<DataStudioState>;
    /**
     * 激活（切到）已存在的中间 tab：只改 {@code activeTab}，不触碰 tabs。
     *
     * <p>⚠️ 为什么不复用 updateCenterTab：编辑器每次击键都会调它（SemanticFile/index.tsx 的 onChange），
     * 若让它顺手设 activeTab，正在编辑的 tab 会反复抢焦点，且波及全站所有 tabType。
     * 该 reducer 是为「目录树点已打开的文件要定位过去」而单独开的口子（P3c / BUG-1）。
     */
    activateCenterTab: Reducer<DataStudioState, { id: string }>;
    /**
     * P3b：记录语义面板当前选中的项目，供 AI Chat 的项目级注入跟随语义树切换。
     */
    saveCurrentSemanticProject: Reducer<DataStudioState, { project?: string }>;
    /**
     * 批次 E：记录知识库面板当前选中的库，供 AI Chat 作默认检索范围
     * （省去用户手打 `@kb/<8 位随机编码>`）。
     */
    saveCurrentKbCollection: Reducer<DataStudioState, { collection?: string }>;
    //更新 project
    updateProject: Reducer<DataStudioState, ProjectDTO>;
    // 更新操作
    updateAction: Reducer<DataStudioState, UpdateActionDTO>;
    saveTempData: Reducer<DataStudioState, TempDataDTO>;
    saveUserData: Reducer<DataStudioState>;
  };
};

const StudioModel: StudioModelType = {
  namespace: 'DataStudio',
  state: {
    layoutData: layout,
    // 工具栏
    toolbar: {
      showDesc: false,
      leftTop: {
        currentSelect: leftDefaultShowTab.key,
        allOpenTabs: [leftDefaultShowTab.key],
        allTabs: ToolbarRoutes.filter((x) => x.position === 'leftTop').map((x) => x.key)
      },
      leftBottom: {
        allTabs: ToolbarRoutes.filter((x) => x.position === 'leftBottom').map((x) => x.key),
        allOpenTabs: []
      },
      right: {
        allTabs: ToolbarRoutes.filter((x) => x.position === 'right').map((x) => x.key),
        allOpenTabs: []
      },
      showActiveTab: false,
      // 这个没有用到，只用到下方
      centerContent: {
        currentSelect: undefined,
        allOpenTabs: [],
        allTabs: []
      },
      project: {
        expandKeys: [],
        selectedKeys: []
      }
    },
    theme: {
      compact: false
    },
    // P3b：语义面板当前项目（未打开过语义面板时为 undefined → AI Chat 不注入项目清单）
    currentSemanticProject: undefined,
    // 批次 E：知识库面板当前选中的库（仅在用户点选时写入；自动选中首个库不算"绑定"）
    currentKbCollection: undefined,
    centerContent: {
      tabs: [],
      activeTab: undefined
    },
    action: {
      actionType: undefined,
      params: undefined
    },
    tempData: {
      flinkEnv: [],
      flinkCluster: [],
      alertGroup: [],
      flinkConfigOptions: [],
      flinkUdfOptions: [],
      dataSourceDataList: [],
      suggestions: [],
      resourceDataList: []
    },
    layoutSize: {
      leftTop: 200,
      leftBottom: 400,
      right: 200
    },
    users: []
  },
  effects: {
    *queryFlinkEnv({ payload }, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const response: EnvType[] = yield call(getEnvData, payload);
      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          flinkEnv: (response ?? []).map((item) => ({
            id: item.id,
            name: item.name,
            fragment: item.fragment
          }))
        }
      });
    },
    *queryFlinkCluster({ payload }, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const sessionData: FlinkCluster[] = yield call(getSessionData, payload);
      const clusterConfigurationData: FlinkCluster[] = yield call(
        getClusterConfigurationData,
        payload
      );
      const flinkClusterData = [...(sessionData ?? []), ...(clusterConfigurationData ?? [])].map(
        (x) => ({
          id: x.id,
          name: x.name,
          enabled: x.enabled,
          type: x.type
        })
      );
      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          flinkCluster: flinkClusterData
        }
      });
    },
    *queryAlertGroup({}, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const data: Alert.AlertGroup[] = yield call(showAlertGroup);
      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          alertGroup: data ?? []
        }
      });
    },
    *queryFlinkConfigOptions({}, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const data: DefaultOptionType[] = yield call(getFlinkConfigs);
      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          flinkConfigOptions: data ?? []
        }
      });
    },
    *queryFlinkUdfOptions({}, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const data: [] = yield call(getFlinkUdfOptions);

      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          flinkUdfOptions: data ?? []
        }
      });
    },
    *queryDataSourceDataList({}, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const data: [] = yield call(getDataSourceList);

      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          dataSourceDataList: data ?? []
        }
      });
    },
    *querySuggestions({}, { call, put, select }) {
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);
      const data: [] = yield call(querySuggestionData, { enableSchemaSuggestions: false });

      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          suggestions: data ?? []
        }
      });
    },
    *queryUserData({ payload }, { call, put }) {
      const response: [] = yield call(getUserData, payload);
      yield put({
        type: 'saveUserData',
        payload: response ?? []
      });
    },
    *queryResource({}, { call, put, select }) {
      const data: [] = yield call(getResourceData);
      const tempData: TempData = yield select((state: any) => state.DataStudio.tempData);

      // 移除数据，并保留当前类别的属性
      yield put({
        type: 'saveTempData',
        payload: {
          ...tempData,
          resourceDataList: data ?? []
        }
      });
    }
  },
  reducers: {
    setLayout(state, { layout }) {
      return {
        ...state,
        layoutData: layout
      };
    },
    handleLayoutChange(state, { dockLayout, newLayout, currentTabId, direction }) {
      if (direction === 'remove') {
        // 删除工具栏选中
        if (currentTabId) {
          const toolbarPosition = findToolbarPositionByTabId(state.toolbar, currentTabId);
          if (toolbarPosition) {
            state.toolbar[toolbarPosition].allOpenTabs = state.toolbar[
              toolbarPosition
            ].allOpenTabs?.filter((t) => t !== currentTabId);
            if (state.toolbar[toolbarPosition].currentSelect === currentTabId) {
              state.toolbar[toolbarPosition].currentSelect = undefined;
            }
            state.layoutSize[toolbarPosition] = dockLayout.find(currentTabId)?.parent?.size ?? 200;
          } else {
            if (state.centerContent.tabs.map((x) => x.id).includes(currentTabId)) {
              // 中间内容
              state.centerContent.tabs = state.centerContent.tabs.filter(
                (x) => x.id !== currentTabId
              );
              if (state.centerContent.activeTab === currentTabId) {
                state.centerContent.activeTab = state.centerContent.tabs[0]?.id;
              }
              if (state.centerContent.tabs.length === 0) {
                // 进入快速开始界面
                dockLayout.updateTab(
                  currentTabId,
                  {
                    closable: false,
                    id: 'quick-start',
                    title: '快速开始',
                    content: <></>,
                    group: 'centerContent'
                  },
                  true
                );
              }
            }
          }
        }
      } else if (direction === 'active') {
        if (state.centerContent.tabs.map((x) => x.id).includes(currentTabId!!)) {
          state.centerContent.activeTab = currentTabId;
        }
      }

      state.toolbar.leftBottom.currentSelect = undefined;
      state.toolbar.right.currentSelect = undefined;
      state.toolbar.leftTop.currentSelect = undefined;
      // 获取所有panel,并更正工具栏的显示
      getAllPanel(newLayout).forEach((panel) => {
        const toolbarPosition = panel.group as ToolbarPosition;
        if (
          toolbarPosition &&
          (toolbarPosition === 'leftTop' ||
            toolbarPosition === 'leftBottom' ||
            toolbarPosition === 'right')
        ) {
          state.toolbar[toolbarPosition].allOpenTabs = panel.activeId ? [panel.activeId] : [];
          state.toolbar[toolbarPosition].currentSelect = panel.activeId;
        }
      });
      state.layoutData = newLayout;
      return { ...state };
    },
    // 操作工具栏显示描述
    handleToolbarShowDesc(state, {}) {
      return {
        ...state,
        toolbar: {
          ...state.toolbar,
          showDesc: !state.toolbar.showDesc
        }
      };
    },
    handleThemeCompact(state, {}) {
      return {
        ...state,
        theme: {
          ...state.theme,
          compact: !state.theme.compact
        }
      };
    },
    // 保存工具栏布局
    saveToolbarLayout(state, { position, list }) {
      return {
        ...state,
        toolbar: {
          ...state.toolbar,
          [position]: {
            ...state.toolbar[position],
            allTabs: list
          }
        }
      };
    },

    /**
     * 对齐工具栏 tab 列表。
     *
     * <p>为什么需要：`app.tsx` 的 `persistReducer` 未设白名单，整个 dva state（含
     * `toolbar.{leftTop,leftBottom,right}.allTabs`）会被 redux-persist 写进浏览器
     * IndexedDB 并在启动时回灌。**新增的 tab（如 P2 的「语义项目」）在历史用户的持久化
     * 布局里并不存在**，于是入口永远不出现（清缓存才会出现），且该列表还参与
     * `index.tsx` 的 tab 定位判断。
     *
     * <p>语义：保留用户既有顺序与位置（含手动拖到别的位置的 tab），仅
     * ① 剔除已从 `ToolbarRoutes` 移除的 key；② 把 routes 中有、但任何位置都没有的 key
     * 追加到它的默认位置末尾。幂等，可重复 dispatch。
     */
    syncToolbarTabs(state) {
      const positions: ToolbarPosition[] = ['leftTop', 'leftBottom', 'right'];
      const validKeys = new Set(ToolbarRoutes.map((route) => route.key));
      const present = new Set<string>();
      positions.forEach((position) =>
        (state.toolbar[position]?.allTabs ?? []).forEach((key) => present.add(key))
      );
      let changed = false;
      const toolbar = { ...state.toolbar };
      positions.forEach((position) => {
        const current = state.toolbar[position]?.allTabs ?? [];
        const kept = current.filter((key) => validKeys.has(key));
        const defaults = ToolbarRoutes.filter((route) => route.position === position).map(
          (route) => route.key
        );
        const missing = defaults.filter((key) => !present.has(key) && !kept.includes(key));
        if (missing.length > 0 || kept.length !== current.length) {
          changed = true;
          toolbar[position] = { ...state.toolbar[position], allTabs: [...kept, ...missing] };
        }
      });
      return changed ? { ...state, toolbar } : state;
    },
    updateCenterTab(state, { id, tabType, title, params, isUpdate }) {
      return {
        ...state,
        centerContent: {
          ...state.centerContent,
          tabs: state.centerContent.tabs.map((x) => {
            if (x.id === id) {
              return {
                id,
                tabType,
                title,
                params,
                isUpdate
              };
            } else {
              return x;
            }
          })
        }
      };
    },
    addCenterTab(state, { id, tabType, title, params }) {
      const newTab = {
        id,
        tabType,
        title,
        params,
        isUpdate: false
      };
      let tabs = state.centerContent.tabs;
      if (!state.centerContent.tabs.map((x) => x.id).includes(id)) {
        tabs = [newTab, ...state.centerContent.tabs];
      }
      return {
        ...state,
        centerContent: {
          ...state.centerContent,
          tabs: tabs,
          activeTab: id
        }
      };
    },
    removeCenterTab: function (prevState: DataStudioState, { id }): DataStudioState {
      const tabs = prevState.centerContent.tabs.filter((x) => x.id !== id);

      return {
        ...prevState,
        centerContent: {
          ...prevState.centerContent,
          tabs: tabs,
          activeTab:
            prevState.centerContent.activeTab === id
              ? tabs[0]?.id
              : prevState.centerContent.activeTab
        }
      };
    },
    /**
     * 激活已存在的中间 tab（P3c / BUG-1 修复）。
     *
     * <p>只改 {@code centerContent.activeTab}，<b>不碰 tabs</b>——因此不会覆盖任何 tab 的
     * params/statement（不会像 updateCenterTab 那样把未保存的修改冲掉）。
     * 中间内容区由 {@code DataStudio/index.tsx} 里「监听 activeTab 去切换 dock 面板」的
     * effect 驱动，所以改这一个字段就够了。
     *
     * <p>tab 不存在时原样返回：调用方（如目录树点开文件）可能带着过期状态，
     * 这里不做隐式新增，避免出现"空 tab"。
     */
    activateCenterTab(state, { id }) {
      if (!id || !state.centerContent.tabs.some((x) => x.id === id)) {
        return state;
      }
      if (state.centerContent.activeTab === id) {
        return state;
      }
      return {
        ...state,
        centerContent: {
          ...state.centerContent,
          activeTab: id
        }
      };
    },
    /**
     * 记录语义面板当前项目（P3b）。
     *
     * <p><b>为什么不能只看 activeTab.params.project</b>（UAT 2026-10-22 反馈）：在语义目录树上
     * 切换项目<b>不会</b>切换中间 tab，所以 AI Chat 的 {@code tabParams} 一直停在上次打开的文件所属
     * 项目上——用户切到 sem_demo3 后提问，AI 仍认为在 sem_demo2。语义树是"当前关注哪个语义项目"
     * 的唯一可信来源，故由它写入这里，AI Chat 读这里。
     */
    saveCurrentSemanticProject(state, { project }) {
      if (state.currentSemanticProject === project) {
        return state;
      }
      return {
        ...state,
        currentSemanticProject: project
      };
    },
    /**
     * 记录知识库面板当前选中的库（批次 E）。
     *
     * <p>与 {@link #saveCurrentSemanticProject} 同构：AI Chat 从这里取"默认检索范围"，
     * 不必要求用户手打 `@kb/<code>`（一个 8 位随机编码，谁也记不住）。
     *
     * <p>同值不触发变更：这个值是上下文指纹的输入之一，无意义的写入会让每次点同一行都
     * 触发"上下文变了 → 重注入"，白白多花一轮 schema token。
     */
    saveCurrentKbCollection(state, { collection }) {
      if (state.currentKbCollection === collection) {
        return state;
      }
      return {
        ...state,
        currentKbCollection: collection
      };
    },
    updateProject(state, { expandKeys, selectedKeys }) {
      return {
        ...state,
        toolbar: {
          ...state.toolbar,
          project: {
            expandKeys: expandKeys ?? state.toolbar.project.expandKeys,
            selectedKeys: selectedKeys ?? state.toolbar.project.selectedKeys
          }
        }
      };
    },
    updateAction(state, { actionType, params }) {
      return {
        ...state,
        action: {
          actionType,
          params
        }
      };
    },
    saveTempData: function (prevState: DataStudioState, action: TempDataDTO): DataStudioState {
      return {
        ...prevState,
        tempData: action.payload
      };
    },
    saveUserData(state, { payload }) {
      const users = payload.users.filter((user: UserBaseInfo.User) => {
        return payload.userIds.includes(user.id);
      });
      return {
        ...state,
        users: users
      };
    }
  }
};

export const [STUDIO_MODEL, STUDIO_MODEL_ASYNC] = createModelTypes(StudioModel);

export default StudioModel;
