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

export interface BrandLinks {
  website?: string;
  docs?: string;
  github?: string;
  gitee?: string;
  bilibili?: string;
  support?: string;
}

export interface BrandTheme {
  colorPrimary?: string;
}

export interface BrandAssets {
  required?: string[];
  recommended?: string[];
}

export interface BrandMeta {
  id: string;
  /** 浏览器标签页标题（构建期写入 index.html） */
  documentTitle?: string;
  /** 产品中文名（页头 / 登录页 H1） */
  name?: string;
  /** 产品英文名（en-US 界面 / 登录页标签页） */
  nameEn?: string;
  /** 版权句式 */
  copyright?: string;
  /** 页头与登录页 logo，相对产物根 */
  logo?: string;
  theme?: BrandTheme;
  links?: BrandLinks;
  assets?: BrandAssets;
}

/** 兜底品牌：与 `brand/dinky.json` 保持一致，仅在 `__BRAND__` 未注入时生效（单测 / 非 umi 环境） */
const FALLBACK_BRAND: BrandMeta = {
  id: 'dinky',
  documentTitle: 'Dinky',
  name: 'Dinky 实时计算平台',
  nameEn: 'Dinky Real-time Platform',
  copyright: 'Produced by Dinky Community',
  logo: 'brand-logo.svg',
  theme: { colorPrimary: '#1890ff' },
  links: {}
};

export const BRAND: BrandMeta =
  typeof __BRAND__ !== 'undefined' && __BRAND__ ? (__BRAND__ as BrandMeta) : FALLBACK_BRAND;

/** 取外链；返回空串表示"该入口不展示"，调用方据此决定是否渲染，避免死链 */
export const brandLink = (key: keyof BrandLinks): string => BRAND.links?.[key] || '';

/** 预留的运行时覆盖入口（当前恒返回构建期品牌；将来若要多品牌免重构建切换，只改这里） */
export const resolveBrand = (): BrandMeta => BRAND;

export default BRAND;
