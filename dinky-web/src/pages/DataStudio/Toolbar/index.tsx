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

import { Flex, Row, Tooltip, Typography } from 'antd';
import React from 'react';
import './index.less';
import { HasAuthority } from '@/hooks/useAccess';
import { ToolbarPosition, ToolbarProp } from '@/pages/DataStudio/Toolbar/data.d';
import { ToolbarRoutes } from '@/pages/DataStudio/Toolbar/ToolbarRoute';
import { PermissionConstants } from '@/types/Public/constants';
import { ReactSortable } from 'react-sortablejs';

export default React.memo((props: ToolbarProp) => {
  const { showDesc, onClick, toolbarSelect, position, saveToolbarLayout, height } = props;
  // 2026-10-14 权限登记：无 aiChat:use 权限时不展示 AI Chat 面板（hooks 须在组件顶层调用）
  const canUseAiChat = HasAuthority(PermissionConstants.DATASTUDIO_AI_CHAT);
  // P2：无 datastudio:semantic:view 时不展示 dbt 语义项目面板（与 AI Chat 同一套可见性约定）
  const canUseSemantic = HasAuthority(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW);
  // 批次 B：无 registration:kb:view 时不展示「知识库」面板。
  // ⚠️ 判定用的是 **F 类**权限点的 path（/registration/kb/view，迁移 .1.4.13 新增）——
  //    `HasAuthority` 只收集 type='F' 的菜单（useAccess.tsx:80-93），拿 C 类页面菜单的 path
  //    （/registration/kb）判会**恒为 false**，非超管永远看不到 tab。
  const canUseKb = HasAuthority(PermissionConstants.KB_VIEW);
  // 批次 F：无 registration:skill:view 时不展示「技能」面板。
  // 同样用 **F 类**权限点的 path（/registration/skill/view，迁移 .1.4.0__skill_menu.sql）——
  // 拿 C 类页面菜单 path（/registration/skill）判会恒为 false。
  const canUseSkill = HasAuthority(PermissionConstants.REGISTRATION_SKILL_VIEW);
  const routes = toolbarSelect.allTabs.filter(
    (key) =>
      (key !== 'aiChat' || canUseAiChat) &&
      (key !== 'semantic' || canUseSemantic) &&
      (key !== 'kb' || canUseKb) &&
      (key !== 'skill' || canUseSkill)
  );
  const currentRoutes = routes
    .map((value) => ToolbarRoutes.find((item) => item.key === value)!!)
    .map((item) => ({
      ...item,
      position
    }));
  const list = currentRoutes.map((item) => ({ id: item.key, name: item.title() }));
  const justifyContent = position === 'leftBottom' ? 'flex-end' : 'flex-start';
  return (
    <Flex wrap gap={1} justify={'center'} className={'toolbar-side'} id={position}>
      <ReactSortable
        style={{
          width: '100%',
          height: '100%',
          flexDirection: 'column',
          display: 'flex',
          justifyContent
        }}
        group={'toolbar'}
        multiDragKey={position}
        animation={150}
        list={list}
        setList={(newState, sortable) => {
          if (sortable) {
            const datas = list.map((item) => item.id);
            if (newState.length !== datas.length) {
              saveToolbarLayout(
                sortable?.options.multiDragKey!! as ToolbarPosition,
                newState.map((item) => item.id)
              );
            } else {
              for (let i = 0; i < newState.length; i++) {
                if (newState[i].id !== datas[i]) {
                  // 元素发生改变删除
                  saveToolbarLayout(
                    sortable?.options.multiDragKey!! as ToolbarPosition,
                    newState.map((item) => item.id)
                  );
                  break;
                }
              }
            }
          }
        }}
      >
        {currentRoutes.map((item) => {
          // 设置显示样式
          let className = 'toolbar-icon-container';
          if (toolbarSelect?.currentSelect === item.key) {
            className += ' toolbar-icon-container-select';
          } else if (toolbarSelect?.allOpenTabs?.includes(item.key)) {
            className += ' toolbar-icon-container-open';
          }
          return (
            <Tooltip title={item.title()} placement={'right'} arrow={false} key={item.key}>
              <div style={{ padding: '5px 5px 0' }}>
                <Row
                  id={item.key}
                  className={className}
                  style={{ height }}
                  align={'middle'}
                  justify={'center'}
                  key={item.key}
                  onClick={() => {
                    onClick(item);
                  }}
                >
                  <span
                    style={{
                      width: '100%',
                      textAlign: 'center'
                    }}
                  >
                    {React.cloneElement(item.icon, {
                      className: 'toolbar-icon',
                      style: { fontSize: height === 60 ? 23 : 18 }
                    })}
                  </span>
                  {showDesc && (
                    <Typography.Paragraph className={'toolbar-desc'}>
                      {item.title()}
                    </Typography.Paragraph>
                  )}
                </Row>
              </div>
            </Tooltip>
          );
        })}
      </ReactSortable>
    </Flex>
  );
});
