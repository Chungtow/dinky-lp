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

import { theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';

/**
 * DataStudio 主题 token 层（2026-11-05，第三批）。
 *
 * <p><b>为什么要有这个文件</b>：改造前 `DataStudio/index.tsx` 把 antd token 直接喂了 CSS 变量字符串
 * ——`colorBgContainer: 'var(--primary-color)'`，Table 的 10 个组件 token 同样是 `var(...)`。
 * antd 计算派生色用的是 `new TinyColor(...).onBackground(token.colorBgContainer)`（见
 * `antd/es/tag/style/index.js` 的 `defaultBg`），TinyColor **解析不了 `var()`**，会按黑色合成，
 * 于是所有「由底色派生」的颜色都算错：Tag 默认底色恒为近黑（浅色主题下黑底黑字，2026-10-28 反馈的
 * "一团黑"），Table 的选中态 / 排序高亮混色同样失真。`components/ThemeChip` 只是绕开了 Tag 这一处。
 *
 * <p><b>本文件的职责</b>（palette 单一真源）：
 * <ol>
 *   <li>`DS_PALETTE` 持有 light / dark 两套色值（**唯一真源**）；</li>
 *   <li>由它生成 antd `ThemeConfig`——全部是**真实 hex**，不再出现 `var()`；</li>
 *   <li>由它生成 CSS 变量文本，运行期注入覆盖 `.page-container.light-theme` / `.dark-theme`。
 *       变量名与作用域**冻结不变**：`@` 句柄面板（`mentionController.ts`）与 AI Chat 的 `@` 引用浮层
 *       都硬编码 `var(--primary-color, #fff)`，名字或作用域一改，它们会回落成白底（深色主题下不可读）。</li>
 * </ol>
 *
 * <p><b>为什么不用 antd 的 token 反推 CSS 变量</b>：深色 `darkAlgorithm` 的 `colorBgContainer` 是
 * `#141414`，而 DataStudio 的深色底是 `#2b2d30`；`colorFillAlter` 还是 `rgba(255,255,255,0.04)` 半透明。
 * 直接反推会让整片背景肉眼变黑、并与 dock 面板底色割裂，故色值必须以本文件的 palette 为准。
 *
 * <p><b>兜底</b>：`pages/DataStudio/css/index.less` 里 `.light-theme` / `.dark-theme` 仍保留同名变量的
 * 静态值，作为**首帧兜底**（本文件的值在 `useEffect` 里注入）。两处必须逐项一致——改一处要同步另一处。
 */

/** 主题模式（与 `useTheme()` 的返回值口径一致：`realDark` 也按深色处理） */
export type DsThemeMode = 'light' | 'dark';

export interface DsPalette {
  /** `--primary-color`：面板/容器底色（**注意语义是背景色，不是 antd 的 `colorPrimary` 主色蓝**） */
  bgContainer: string;
  /** `--second-color`：次级底色（表头、hover 行、选中行） */
  fillAlter: string;
  /** `--tree-color`：左侧树底 */
  treeBg: string;
  /** `--main-background-color`：中区内容底 */
  mainBg: string;
  /** `--footer-bg-color` */
  footerBg: string;
  /** `--btn-color`：按钮文字 */
  btnText: string;
  /** `--btn-active-color`：按钮激活文字色（主色语义） */
  btnActiveText: string;
  /** `--btn-background-color`：按钮激活底 */
  btnActiveBg: string;
  /** `--border-color` */
  border: string;
  /** `--text-color` */
  text: string;
  /** `--panel-extra-btn-color`：面板右上角按钮 */
  panelExtraBtn: string;
  /** `--scroll-bg-color` */
  scrollBg: string;
  /** `--center-content-bg-color` */
  centerBg: string;
  /** `--center-content-active-bg-color` */
  centerActiveBg: string;
  /** `--ds-highlight-color`：搜索结果高亮文字 */
  highlight: string;
  /** `--ds-highlight-bg`：搜索结果高亮底 */
  highlightBg: string;
}

/**
 * 变量名映射。**名字从改造前原样继承，禁止重命名**：`mentionController.ts`、`AiChat/index.tsx`、
 * `Toolbar/Service/Lineage/index.tsx` 都直接引用了这些名字。
 */
export const DS_CSS_VAR_OF: Record<keyof DsPalette, string> = {
  bgContainer: '--primary-color',
  fillAlter: '--second-color',
  treeBg: '--tree-color',
  mainBg: '--main-background-color',
  footerBg: '--footer-bg-color',
  btnText: '--btn-color',
  btnActiveText: '--btn-active-color',
  btnActiveBg: '--btn-background-color',
  border: '--border-color',
  text: '--text-color',
  panelExtraBtn: '--panel-extra-btn-color',
  scrollBg: '--scroll-bg-color',
  centerBg: '--center-content-bg-color',
  centerActiveBg: '--center-content-active-bg-color',
  highlight: '--ds-highlight-color',
  highlightBg: '--ds-highlight-bg'
};

/**
 * 两套调色板。**值取自改造前的 `css/index.less`**（即线上既有观感），唯一有意调整的是
 * `btnActiveText`：主色统一到 antd 5 默认 `#1677ff`（见计划 D12）。
 */
export const DS_PALETTE: Record<DsThemeMode, DsPalette> = {
  light: {
    bgContainer: '#ffffff',
    fillAlter: '#f9f9f9',
    treeBg: '#fafafa',
    mainBg: '#ffffff',
    footerBg: '#f4f4f4',
    btnText: '#000000e0',
    btnActiveText: '#1677ff',
    btnActiveBg: '#e6f7ff',
    border: '#dfe1e4',
    text: '#424242',
    panelExtraBtn: '#000000',
    scrollBg: '#daecff',
    centerBg: '#f2f2f2',
    centerActiveBg: '#ffffff',
    highlight: '#1677ff',
    highlightBg: 'rgba(22, 119, 255, 0.14)'
  },
  dark: {
    bgContainer: '#2b2d30',
    fillAlter: '#3c3f41',
    treeBg: '#2f3133',
    mainBg: '#2b2d30',
    footerBg: '#2b2d30',
    btnText: '#ced0d6',
    btnActiveText: '#fafafa',
    btnActiveBg: '#43454a',
    border: '#1e1f22',
    text: '#fafafa',
    panelExtraBtn: '#fafafa',
    scrollBg: '#4d4e51',
    centerBg: '#2b2d30',
    centerActiveBg: '#434343',
    highlight: '#4096ff',
    highlightBg: 'rgba(64, 150, 255, 0.22)'
  }
};

/** 注入用的 `<style>` 标签 id（避免重复注入） */
export const DS_PALETTE_STYLE_ID = 'ds-palette-vars';

/**
 * 生成 CSS 变量文本。选择器与 `PageContainer` 上的 className 对齐（`.page-container` + `.light-theme`/`.dark-theme`）。
 *
 * <p>历史遗留：深色块里把 `--center-content-bg-color` 写成了驼峰 `--centerContent-bg-color`，
 * 而引用方用的是中划线写法，深色下其实一直取不到值。这里两种写法都给，兼容旧引用，也修掉这个隐形缺失。
 */
export function paletteCssText(mode: DsThemeMode): string {
  const palette = DS_PALETTE[mode];
  const body = (Object.keys(DS_CSS_VAR_OF) as (keyof DsPalette)[])
    .map((key) => `${DS_CSS_VAR_OF[key]}:${palette[key]};`)
    .join('');
  const legacy = `--centerContent-bg-color:${palette.centerBg};`;
  return `.page-container.${mode}-theme{${body}${legacy}}`;
}

/**
 * 由 palette 生成 antd 主题配置。
 *
 * <p>Table 的 9 个组件 token 与改造前的 `var(--second-color)` / `var(--primary-color)` **取同一个值**，
 * 只是换成真实 hex——观感不变，但 antd 的颜色计算（选中态混色、排序高亮、Tag 派生色）从此算得对。
 */
export function buildDataStudioTheme(mode: DsThemeMode, compact: boolean): ThemeConfig {
  const palette = DS_PALETTE[mode];
  const algorithms = [mode === 'light' ? antdTheme.defaultAlgorithm : antdTheme.darkAlgorithm];
  if (compact) {
    algorithms.push(antdTheme.compactAlgorithm);
  }
  return {
    token: {
      colorBgContainer: palette.bgContainer
    },
    components: {
      Table: {
        headerBg: palette.fillAlter,
        rowHoverBg: palette.fillAlter,
        rowSelectedBg: palette.fillAlter,
        rowSelectedHoverBg: palette.fillAlter,
        headerFilterHoverBg: palette.bgContainer,
        headerSortActiveBg: palette.bgContainer,
        headerSortHoverBg: palette.bgContainer,
        fixedHeaderSortActiveBg: palette.bgContainer,
        bodySortBg: palette.fillAlter
      }
    },
    algorithm: algorithms
  };
}
