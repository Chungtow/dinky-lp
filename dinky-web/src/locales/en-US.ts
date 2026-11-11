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

import globalHeader from './en-US/global';
import menu from './en-US/menu';
import pages from './en-US/pages';
import pwa from './en-US/pwa';
import request from './en-US/request';
import response from './en-US/response';
import shortcutKey from './en-US/shortcutKey';
import welcome from '@/locales/en-US/welcome';
import { BRAND } from '@/branding';

export default {
  'navBar.lang': 'Languages',
  'layout.user.link.help': 'Help',
  'layout.user.link.privacy': 'Privacy',
  'layout.user.link.terms': 'Terms',
  // 英文界面版权：优先 copyrightEn，缺省回退 copyright（品牌包未配英文版权时不至于空白）
  'app.copyright.produced': BRAND.copyrightEn || BRAND.copyright || 'Produced by Dinky Community',
  // 数据开发底部状态条的欢迎语（英文界面用英文短名）
  'app.footer.welcome': `Welcome to ${BRAND.shortNameEn || BRAND.nameEn || BRAND.name} !`,
  // 归属声明默认文案：这里只提供"品牌未配置"时的兜底，
  // 品牌包显式配置（含空串=不展示）由 utils/intl.tsx 的 brandAttribution() 在渲染层决策。
  // 注意：不能在本文件用空串表达"不展示"——formatMessage 对空消息会回退成 key 本身并透出到界面。
  'app.copyright.attribution': 'Built on Dinky',
  ...globalHeader,
  ...menu,
  ...pwa,
  ...pages,
  ...request,
  ...response,
  ...shortcutKey,
  ...welcome
};
