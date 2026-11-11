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

import globalHeader from './zh-CN/global';
import menu from './zh-CN/menu';
import pages from './zh-CN/pages';
import pwa from './zh-CN/pwa';
import request from './zh-CN/request';
import response from './zh-CN/response';
import shortcutKey from './zh-CN/shortcutKey';
import welcome from '@/locales/zh-CN/welcome';
import { BRAND } from '@/branding';

export default {
  'navBar.lang': '语言',
  'layout.user.link.help': '帮助',
  'layout.user.link.privacy': '隐私',
  'layout.user.link.terms': '条款',
  'app.copyright.produced': BRAND.copyright || 'Produced by Dinky Community',
  // 数据开发底部状态条的欢迎语（中文界面用中文短名）
  'app.footer.welcome': `Welcome to ${BRAND.shortName || BRAND.name} !`,
  // 归属声明默认文案：这里只提供"品牌未配置"时的兜底，
  // 品牌包显式配置（含空串=不展示）由 utils/intl.tsx 的 brandAttribution() 在渲染层决策。
  // 注意：不能在本文件用空串表达"不展示"——formatMessage 对空消息会回退成 key 本身并透出到界面。
  'app.copyright.attribution': '基于 Dinky 二次开发',
  ...globalHeader,
  ...pages,
  ...menu,
  ...pwa,
  ...request,
  ...response,
  ...shortcutKey,
  ...welcome
};
