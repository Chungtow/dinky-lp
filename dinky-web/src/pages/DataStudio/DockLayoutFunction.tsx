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

import { BoxData } from 'rc-dock/es';
import { DataStudioState } from '@/pages/DataStudio/model';
import { ToolbarPosition, ToolbarRoute } from '@/pages/DataStudio/Toolbar/data.d';
import { PanelData, TabData } from 'rc-dock/es/DockData';
import { DockLayout, LayoutData } from 'rc-dock';
import { Filter } from 'rc-dock/es/Algorithm';

export const activeTab = (
  dockLayout: DockLayout,
  layoutData: LayoutData,
  sourceTabData: TabData,
  targetId: string
) => {
  const tabPanel = find(layoutData, sourceTabData.id!!) as PanelData;
  const targetTabPanel = find(layoutData, targetId) as PanelData;
  if (!tabPanel && targetTabPanel) {
    // 新增tab
    targetTabPanel.tabs = [sourceTabData, ...targetTabPanel.tabs];
    targetTabPanel.activeId = sourceTabData.id;
  } else {
    // 切换tab
    if (tabPanel.activeId === sourceTabData.id) {
      dockLayout.loadLayout(layoutData);
      return;
    }
    if (tabPanel.tabs.length === 0) {
      tabPanel.tabs = [sourceTabData];
    } else {
      if (tabPanel.tabs.some((tab) => tab.id === sourceTabData.id)) {
        tabPanel.activeId = sourceTabData.id;
      }
    }
  }

  dockLayout.loadLayout(layoutData);
};

/**
 * 判断 dock 的某个子项是否属于「右侧列」（AI Chat 等 group = right 的面板）。
 *
 * <p>子项有两种形态：{@link PanelData}（自身带 group）/ {@link BoxData}（group 挂在首个 tab 上），
 * 两种都要识别，否则右侧列会被误判为「左侧 + 中间」的一部分。
 */
const isRightDocked = (child: BoxData | PanelData): boolean => {
  if ('group' in child && (child as PanelData).group === 'right') {
    return true;
  }
  const first = (child as BoxData).children?.[0] as PanelData | undefined;
  return !!first && 'group' in first && first.group === 'right';
};

/** 同 isRightDocked：识别「服务面板」（group = leftBottom，含输出 / 结果两栏） */
const isLeftBottomDocked = (child: BoxData | PanelData): boolean => {
  if ('group' in child && (child as PanelData).group === 'leftBottom') {
    return true;
  }
  const first = (child as BoxData).children?.[0] as PanelData | undefined;
  return !!first && 'group' in first && first.group === 'leftBottom';
};

export const createNewPanel = (
  layoutData: LayoutData,
  route: ToolbarRoute,
  size?: number
): LayoutData => {
  // todo 这里有布局混乱导致算法崩溃风险
  const panelData: PanelData = {
    group: route.position,
    size,
    tabs: [
      {
        id: route.key,
        content: <></>,
        title: route.title(),
        group: route.position
      }
    ]
  };
  const boxData: BoxData = {
    mode: 'vertical',
    size: size ?? 1000,
    children: [panelData]
  };

  const dockbox = layoutData.dockbox;
  if (dockbox.mode === 'horizontal') {
    if (route.position == 'right') {
      (dockbox.children as BoxData[]).push(boxData);
    } else if (route.position === 'leftTop') {
      dockbox.children = [boxData, ...dockbox.children];
    } else if (route.position === 'leftBottom') {
      // 服务面板只挂在「左侧 + 中间内容区」下方；右侧列（AI Chat 等）保持整列全高，
      // 否则服务面板会横跨整个 dock（含 AI Chat 下方），把 AI Chat 压到上面去。
      const children = [...(dockbox.children as BoxData[])];
      const rightChildren = children.filter(isRightDocked);
      if (rightChildren.length === 0) {
        // 没有右侧列：保持原有行为（整行下方）
        return {
          ...layoutData,
          dockbox: {
            mode: 'vertical',
            children: [
              {
                mode: 'horizontal',
                children
              },
              boxData
            ]
          }
        };
      }
      const restChildren = children.filter((child) => !isRightDocked(child));
      return {
        ...layoutData,
        dockbox: {
          mode: 'horizontal',
          children: [
            {
              mode: 'vertical',
              children: [
                {
                  mode: 'horizontal',
                  children: restChildren
                },
                boxData
              ]
            },
            ...rightChildren
          ]
        }
      };
    } else if (route.position === 'centerContent') {
      if (dockbox.children.length === 0) {
        dockbox.children = [...dockbox.children, boxData];
      } else {
        if ((dockbox.children[0] as PanelData).group === 'leftTop') {
          dockbox.children = [dockbox.children[0], boxData, ...dockbox.children.slice(1)];
        } else if ((dockbox.children[0] as PanelData).group === 'right') {
          dockbox.children = [boxData, ...dockbox.children];
        }
      }
    }
  } else if (dockbox.mode === 'vertical') {
    if (dockbox.children.length === 0) {
      dockbox.children.push(boxData);
    } else {
      if (route.position === 'right') {
        // AI Chat 优先级最高：若此前（无右侧列时）服务面板被 fallback 挂到顶层底部、
        // 横跨全宽，会把本次要加的右侧列压成「右上角」。这里先把服务面板收纳进
        // 主区行内部，再让自己作为顶层右侧列加入，保证任何时候 AI Chat 都占满整列高度。
        const svcIdx = dockbox.children.findIndex((child) =>
          isLeftBottomDocked(child as BoxData)
        );
        if (svcIdx >= 0) {
          const svc = dockbox.children[svcIdx];
          const others = dockbox.children.filter(
            (child, i) => i !== svcIdx && !isRightDocked(child as BoxData)
          );
          const rights = dockbox.children.filter((child) => isRightDocked(child as BoxData));
          dockbox.mode = 'horizontal';
          dockbox.children = [
            {
              mode: 'vertical',
              children: [...others, svc]
            } as BoxData,
            ...rights,
            boxData
          ];
          return layoutData;
        }
      }
      if (route.position === 'leftBottom') {
        // 同 horizontal 分支：服务面板只挂在「左侧 + 中间」下方，右侧列（AI Chat）保持全高。
        // 找出那一 row（horizontal）里含右侧列的子项，把服务面板塞进它内部的非右侧区下方。
        const rowIndex = dockbox.children.findIndex(
          (child) =>
            (child as BoxData).mode === 'horizontal' &&
            ((child as BoxData).children as (BoxData | PanelData)[])?.some(isRightDocked)
        );
        if (rowIndex < 0) {
          // 没有右侧列：保持原有行为
          dockbox.children.push(boxData);
        } else {
          const row = dockbox.children[rowIndex] as BoxData;
          const kids = [...((row.children ?? []) as (BoxData | PanelData)[])];
          const rightKids = kids.filter(isRightDocked);
          const restKids = kids.filter((child) => !isRightDocked(child));
          const mainWithService: BoxData = {
            mode: 'vertical',
            children: [
              {
                mode: 'horizontal',
                children: restKids
              },
              boxData
            ]
          };
          if (dockbox.children.length === 1) {
            // dockbox 里只有这一 row：直接摊平成 horizontal（右侧列与主区左右并排）
            dockbox.mode = 'horizontal';
            dockbox.children = [mainWithService, ...rightKids];
          } else {
            dockbox.children[rowIndex] = rightKids.length
              ? { mode: 'horizontal', children: [mainWithService, ...rightKids] }
              : mainWithService;
          }
        }
      } else {
        for (let i = 0; i < dockbox.children.length; i++) {
          if ((dockbox.children[i] as PanelData).group !== 'leftBottom') {
            if (route.position === 'leftTop') {
              if ('tabs' in dockbox.children[i]) {
                // panel
                dockbox.children[i] = {
                  mode: 'horizontal',
                  children: [boxData, dockbox.children[i] as PanelData]
                };
              } else {
                // box
                (dockbox.children[i] as BoxData).children = [
                  boxData,
                  ...(dockbox.children[i] as BoxData).children
                ];
              }
            } else if (route.position === 'right') {
              if ('tabs' in dockbox.children[i]) {
                // panel
                dockbox.children[i] = {
                  mode: 'horizontal',
                  children: [dockbox.children[i] as PanelData, boxData]
                };
              } else {
                // box
                (dockbox.children[i] as BoxData).children.push(boxData);
              }
            } else if (route.position === 'centerContent') {
              if ('tabs' in dockbox.children[i]) {
                // panel
                if ((dockbox.children[i] as PanelData).group === 'leftTop') {
                  dockbox.children[i] = [dockbox.children[i], panelData];
                } else if ((dockbox.children[i] as PanelData).group === 'right') {
                  dockbox.children[i] = [panelData, dockbox.children[i]];
                }
                dockbox.children[i] = {
                  mode: 'horizontal',
                  children: [...(dockbox.children[i] as PanelData[])]
                };
              } else {
                if ((dockbox.children[i].children[0] as PanelData).group === 'leftTop') {
                  (dockbox.children[i] as BoxData).children = [
                    dockbox.children[i].children[0],
                    panelData,
                    ...dockbox.children[i].children.slice(1)
                  ];
                }
              }
            }
            break;
          }
        }
      }
    }
  }
  return layoutData;
};

export const findToolbarPositionByTabId = (
  toolbar: DataStudioState['toolbar'],
  tabId: string
): ToolbarPosition | undefined => {
  if (toolbar.leftTop.allOpenTabs.includes(tabId)) {
    return 'leftTop';
  } else if (toolbar.leftBottom.allOpenTabs.includes(tabId)) {
    return 'leftBottom';
  } else if (toolbar.right.allOpenTabs.includes(tabId)) {
    return 'right';
  }
  return undefined;
};

export function find(
  layout: LayoutData,
  id: string,
  filter: Filter = Filter.AnyTabPanel
): PanelData | TabData | BoxData | undefined {
  let result: PanelData | TabData | BoxData | undefined;

  if (filter & Filter.Docked) {
    result = findInBox(layout.dockbox, id, filter);
  }
  if (result) return result;

  if (filter & Filter.Floated) {
    result = findInBox(layout.floatbox, id, filter);
  }
  if (result) return result;

  if (filter & Filter.Windowed) {
    result = findInBox(layout.windowbox, id, filter);
  }
  if (result) return result;

  if (filter & Filter.Max) {
    result = findInBox(layout.maxbox, id, filter);
  }

  return result;
}

function findInBox(
  box: BoxData | undefined,
  id: string,
  filter: Filter
): PanelData | TabData | BoxData | undefined {
  let result: PanelData | TabData | BoxData | undefined;
  if (filter | Filter.Box && box?.id === id) {
    return box;
  }
  if (!box?.children) {
    return undefined;
  }
  for (let child of box.children) {
    if ('children' in child) {
      if ((result = findInBox(child, id, filter))) {
        break;
      }
    } else if ('tabs' in child) {
      if ((result = findInPanel(child, id, filter))) {
        break;
      }
    }
  }
  return result;
}

function findInPanel(
  panel: PanelData,
  id: string,
  filter: Filter
): PanelData | TabData | undefined {
  if (panel.id === id && filter & Filter.Panel) {
    return panel;
  }
  if (filter & Filter.Tab) {
    for (let tab of panel.tabs) {
      if (tab.id === id) {
        return panel;
      }
    }
  }
  return undefined;
}
