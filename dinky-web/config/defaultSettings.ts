/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ProLayoutProps } from '@ant-design/pro-components';

/**
 * 品牌 logo 路径：品牌元数据（`brand/<id>.json` 的 `logo` 字段）优先，
 * 缺省回退公共层的 `brand-logo.svg`。`__BRAND__` 由 `config/config.ts` 的 define 在构建期注入；
 * 该文件也会被 config 层（Node）加载，那时 `__BRAND__` 不存在，走回退分支。
 */
const brandLogo: string =
  (typeof __BRAND__ !== 'undefined' && (__BRAND__ as { logo?: string })?.logo) || './brand-logo.svg';

/**
 * @name
 */
const Settings: ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
} = {
  navTheme: 'light',
  // 主色统一为 antd 5 默认蓝（2026-11-05）：改造前这里是 `#1890ff`，而 antd 全局没配 theme，
  // 按钮/链接实际走的是 antd 默认 `#1677ff`，于是「菜单选中一种蓝、按钮另一种蓝」。
  // 现统一到 `#1677ff`（DataStudio 侧见 `pages/DataStudio/themeToken.ts`）。
  colorPrimary: '#1677ff',
  layout: 'mix',
  contentWidth: 'Fluid',
  fixedHeader: false,
  fixSiderbar: true,
  colorWeak: false,
  // 这里不能删除,需要先设置,否则会导致页面白屏, 然后在 layout 进行重新赋值
  title: 'Dinky Real-time Platform ',
  pwa: true,
  // 品牌 logo 由品牌元数据的 `logo` 字段决定（构建期以 __BRAND__ 注入），
  // 允许 .svg / .png；config 层（Node）无 __BRAND__ 时回退默认文件名。
  logo: brandLogo,
  iconfontUrl: '',
  splitMenus: true,
  menu: {
    locale: true
  },
  token: {
    // bgLayout: '', //layout 的背景颜色

    // 参见ts声明，demo 见文档，通过token 修改样式
    // https://procomponents.ant.design/components/layout#%E9%80%9A%E8%BF%87-token-%E4%BF%AE%E6%94%B9%E6%A0%B7%E5%BC%8F
    header: {
      colorBgHeader: '#292f33',
      colorHeaderTitle: '#fff',
      colorTextMenu: '#dfdfdf',
      colorTextMenuSecondary: '#dfdfdf',
      colorTextMenuSelected: '#fff',
      colorBgMenuItemSelected: '#1e252a',
      colorTextMenuActive: 'rgba(255,255,255,0.85)',
      colorTextRightActionsItem: '#dfdfdf'
    },
    sider: {
      colorBgMenuItemSelected: '#3399FF'
    },
    pageContainer: {
      paddingBlockPageContainerContent: 5,
      paddingInlinePageContainerContent: 10
    }
  }
};

export default Settings;
