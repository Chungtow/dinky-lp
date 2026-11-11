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

import { BRAND } from '@/branding';
import { getIntl } from '@umijs/max';

export const l = (id: string, defaultMessage?: string, value?: {}): string => {
  if (!id) {
    return '';
  }
  return getIntl().formatMessage({ id, defaultMessage }, value);
};

/** 当前界面语言（形如 zh-CN / en-US）；非 umi 环境取不到时返回空串 */
export const currentLocale = (): string => {
  try {
    return getIntl().locale || '';
  } catch {
    return '';
  }
};

/**
 * 品牌短名（按当前界面语言取值）。
 * 中文取 `shortName`（回退 `name`），英文取 `shortNameEn`（回退 `nameEn` → `shortName` → `name`），
 * 避免英文界面露出中文短名（如页脚外链标题、logo alt 等非 i18n 文案位）。
 */
export const brandShortName = (): string => {
  const locale = currentLocale();
  const isZh = !locale || locale.toLowerCase().startsWith('zh');
  if (isZh) {
    return BRAND.shortName || BRAND.name || '';
  }
  return BRAND.shortNameEn || BRAND.nameEn || BRAND.shortName || BRAND.name || '';
};

/**
 * 品牌归属声明取值（“基于 Dinky 二次开发”这类）。
 *
 * - 品牌包 `brand/<id>.json` 配了 `attribution`：按当前语言取 `zh`/`en`，**显式空串 = 该品牌不展示**
 * - 品牌包没配该字段：返回 undefined，由调用方回退 i18n 默认文案（`app.copyright.attribution`）
 *
 * 为什么不在 locales 里用空串表达"不展示"：`formatMessage` 对空消息按"缺翻译"处理并返回 id，
 * 会把 key（`app.copyright.attribution`）直接透出到界面，故"关闭"语义必须放在渲染层判断。
 */
export const brandAttribution = (): string | undefined => {
  const attr = BRAND.attribution;
  if (!attr) {
    return undefined;
  }
  const locale = currentLocale();
  const isZh = !locale || locale.toLowerCase().startsWith('zh');
  return (isZh ? attr.zh : attr.en) ?? '';
};

export const parseSplitI18nToWaterMarkList = (i18nMsg: string, splitter: string): string[] => {
  return i18nMsg.split(splitter);
};
