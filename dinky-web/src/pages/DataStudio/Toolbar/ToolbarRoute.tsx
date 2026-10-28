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

import { ToolbarRoute } from '@/pages/DataStudio/Toolbar/data.d';
import {
  BookOutlined,
  CodeOutlined,
  ConsoleSqlOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  FileZipOutlined,
  FunctionOutlined,
  RobotOutlined,
  SettingOutlined,
  TableOutlined,
  ToolOutlined
} from '@ant-design/icons';
import React, { lazy, ReactElement, ReactNode, Suspense } from 'react';
import { l } from '@/utils/intl';

export const lazyComponent = (element: ReactNode): ReactElement => {
  return <Suspense fallback={<div>loading...</div>}>{element}</Suspense>;
};
const Project = lazy(() => import('@/pages/DataStudio/Toolbar/Project'));
const StartIntro = lazy(() => import('@/pages/DataStudio/CenterTabContent/StartIntroPage'));
const DataSource = lazy(() => import('@/pages/DataStudio/Toolbar/DataSource'));
const GlobalVariable = lazy(() => import('@/pages/DataStudio/Toolbar/GlobalVariable'));
const Service = lazy(() => import('@/pages/DataStudio/Toolbar/Service'));
const Tool = lazy(() => import('@/pages/DataStudio/Toolbar/Tool'));
const Catalog = lazy(() => import('@/pages/DataStudio/Toolbar/Catalog'));
const AiChat = lazy(() => import('@/pages/DataStudio/Toolbar/AiChat'));
const FlinkSqlClient = lazy(() => import('@/pages/DataStudio/Toolbar/FlinkSqlClient'));
const Resource = lazy(() => import('@/pages/DataStudio/Toolbar/Resource'));
const Semantic = lazy(() => import('@/pages/DataStudio/Toolbar/Semantic'));
const Kb = lazy(() => import('@/pages/DataStudio/Toolbar/Kb'));
export const ToolbarRoutes: ToolbarRoute[] = [
  {
    key: 'quick-start',
    title: () => l('menu.datastudio.quickStart'),
    icon: <></>,
    position: 'centerContent',
    content: () => lazyComponent(<StartIntro />)
  },
  {
    key: 'project',
    title: () => l('menu.datastudio.project'),
    icon: <ConsoleSqlOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<Project />)
  },
  {
    key: 'catalog',
    title: () => l('menu.datastudio.catalog'),
    icon: <TableOutlined />,
    position: 'right',
    content: () => lazyComponent(<Catalog />)
  },
  {
    key: 'aiChat',
    title: () => l('menu.datastudio.aiChat'),
    icon: <RobotOutlined />,
    position: 'right',
    content: () => lazyComponent(<AiChat />)
  },
  {
    key: 'datasource',
    title: () => l('menu.datastudio.datasource'),
    icon: <DatabaseOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<DataSource />)
  },
  {
    key: 'function',
    title: () => l('menu.registration.fragment'),
    icon: <FunctionOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<GlobalVariable />)
  },
  {
    key: 'service',
    title: () => l('menu.datastudio.service'),
    icon: <SettingOutlined />,
    position: 'leftBottom',
    content: () => lazyComponent(<Service />)
  },
  {
    key: 'tool',
    title: () => l('menu.datastudio.tool'),
    icon: <ToolOutlined />,
    position: 'leftBottom',
    content: () => lazyComponent(<Tool />)
  },
  {
    key: 'flinkSqlClient',
    title: () => l('menu.datastudio.flinkSqlClient'),
    icon: <CodeOutlined />,
    position: 'leftBottom',
    content: () => lazyComponent(<FlinkSqlClient />)
  },
  {
    key: 'resource',
    title: () => l('datastudio.middle.qg.resource'),
    icon: <FileZipOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<Resource />)
  },
  {
    // P2：dbt 语义项目在线编辑（HDFS /semantic）。建项目/发布仍在 JupyterLab
    key: 'semantic',
    title: () => l('menu.datastudio.semantic'),
    icon: <FileTextOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<Semantic />)
  },
  {
    // 批次 B：知识库（@kb）只读浏览。内容在 kb-service，目录与权限在 Dinky；
    // 不做目录树、不做编辑——要改内容回原 tab 改完再推送（批次 C 提供推送入口）
    key: 'kb',
    title: () => l('menu.datastudio.kb'),
    icon: <BookOutlined />,
    position: 'leftTop',
    content: () => lazyComponent(<Kb />)
  }
];

export const leftDefaultShowTab = ToolbarRoutes[1];
