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

/**
 * 品牌主题色板。
 * - `colorPrimary` 是唯一必填项（缺省沿用 defaultSettings 的 antd 默认蓝）；
 * - 其余字段缺省时按下面 `brandTheme` 的回退链取值，因此老品牌包只写 colorPrimary 不会坏。
 * - 色板设计约定：主色走"深/中/浅"三段（primary / accent / tint），**accent 与木色类低对比色不得用于按钮底或文字**。
 */
export interface BrandTheme {
  /** 主色：按钮、链接、复选框、选中菜单文字、焦点环 */
  colorPrimary?: string;
  /** 强调色：选中指示条、Tab 下划线、图表主序列；缺省回退 colorPrimary */
  colorAccent?: string;
  /** 浅底：侧边菜单选中底、表头、浅色标签底；缺省为空（沿用布局默认） */
  colorBgTint?: string;
  /** 深字：大标题、深色文字强调、登录页标题；缺省回退 colorPrimary */
  colorInk?: string;
  /** 顶部导航底色；缺省沿用 defaultSettings 的炭灰 */
  colorHeaderBg?: string;
  /**
   * 登录页右侧插画区的背景（完整 CSS 渐变字符串）。
   * 缺省为空 = 保留官方模板原来的 `linear-gradient(135deg,#1fa2ff,#12d8fa,#a6ffcb)`，
   * 因此只有显式配置的品牌才会改变登录页右侧观感。
   */
  loginGradient?: string;
}

export interface BrandAssets {
  required?: string[];
  recommended?: string[];
}

/** 品牌级功能开关（缺省一律关闭，需要时在 brand/<id>.json 显式打开） */
export interface BrandFeatures {
  /** 顶部菜单栏右侧是否显示版本号；关闭后版本号移到头像下拉菜单（只读项） */
  showVersion?: boolean;
}

export interface BrandMeta {
  id: string;
  /** 浏览器标签页标题（构建期写入 index.html） */
  documentTitle?: string;
  /** 产品中文名（页头 / 登录页 H1） */
  name?: string;
  /** 产品英文名（en-US 界面 / 登录页标签页） */
  nameEn?: string;
  /** 产品短名（用于“……环境配置”这类拼接文案，避免整句过长） */
  shortName?: string;
  /** 产品英文短名（英文界面用；缺省回退 nameEn，避免英文界面露出中文短名） */
  shortNameEn?: string;
  /** 版权句式（中文界面） */
  copyright?: string;
  /** 版权句式（英文界面）；缺省回退 copyright */
  copyrightEn?: string;
  /**
   * 归属声明（“基于 Dinky 二次开发”这类），按界面语言分别取值，随语言切换自动变化。
   * 缺省 = 用 locales 里的默认文案；显式给空串 = 该品牌不展示归属声明。
   */
  attribution?: { zh?: string; en?: string };
  /** 页头与登录页 logo，相对产物根 */
  logo?: string;
  theme?: BrandTheme;
  links?: BrandLinks;
  assets?: BrandAssets;
  features?: BrandFeatures;
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

/** 与 `config/defaultSettings.ts` 的 colorPrimary 保持一致，作为所有回退链的终点 */
const DEFAULT_PRIMARY = '#1677ff';

/**
 * 解析后的品牌主题：每个字段都有确定值，供组件与全局样式使用。
 * 回退链保证"只写了 colorPrimary 的老品牌包"仍得到一套自洽的配色，
 * 且 `tint` / `headerBg` 缺省为空串——调用方据此判断"不覆盖布局默认"，避免改动其他品牌的观感。
 */
export const brandTheme = {
  primary: BRAND.theme?.colorPrimary || DEFAULT_PRIMARY,
  accent: BRAND.theme?.colorAccent || BRAND.theme?.colorPrimary || DEFAULT_PRIMARY,
  tint: BRAND.theme?.colorBgTint || '',
  ink: BRAND.theme?.colorInk || BRAND.theme?.colorPrimary || '#1f1f1f',
  headerBg: BRAND.theme?.colorHeaderBg || '',
  /** 空串 = 用官方模板原值（见 LoginForm 的兜底） */
  loginGradient: BRAND.theme?.loginGradient || ''
};

/**
 * 品牌色板对应的 CSS 变量（`app.tsx` 的 rootContainer 在运行时写到 :root）。
 * less 无法读到构建期注入的 `__BRAND__`，样式层统一走这些变量，避免二次硬编码色值。
 */
export const brandCssVars = (): Record<string, string> => ({
  '--brand-primary': brandTheme.primary,
  '--brand-accent': brandTheme.accent,
  '--brand-tint': brandTheme.tint || 'transparent',
  '--brand-ink': brandTheme.ink
});

/**
 * hex 转 rgba，用于阴影/遮罩这类需要透明度的场景。
 * 非 6 位 hex 输入原样返回，避免生成非法 CSS 值。
 */
export const withAlpha = (color: string, alpha: number): string => {
  const matched = /^#?([0-9a-f]{6})$/i.exec((color || '').trim());
  if (!matched) {
    return color;
  }
  const value = parseInt(matched[1], 16);
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${alpha})`;
};

/** 取品牌功能开关；未配置视为关闭，保证白标默认收敛 UI */
export const brandFeature = (key: keyof BrandFeatures): boolean => BRAND.features?.[key] === true;

/**
 * 品牌包内提供的技术支持二维码文件名清单（构建期由 config.ts 扫描 `public/brand/<id>/community/` 注入）。
 * 调用方按此清单决定渲染哪些渠道的二维码，缺失的渠道不渲染，避免 404 空图。
 * 非 umi 环境（单测）无注入时返回空数组。
 */
export const brandCommunity = (): string[] =>
  typeof __BRAND_COMMUNITY__ !== 'undefined' && Array.isArray(__BRAND_COMMUNITY__)
    ? __BRAND_COMMUNITY__
    : [];

/** 判断品牌包是否提供了某个渠道的二维码（按文件名前缀匹配，如 'wechat'） */
export const hasCommunityAsset = (channel: string): boolean =>
  brandCommunity().some((f) => f.toLowerCase().startsWith(channel.toLowerCase()));

/** 预留的运行时覆盖入口（当前恒返回构建期品牌；将来若要多品牌免重构建切换，只改这里） */
export const resolveBrand = (): BrandMeta => BRAND;

export default BRAND;
