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

import React from 'react';

/** 语义色调。neutral 之外的颜色在明暗两种主题下都够对比度（都取中间调，不依赖主题 token） */
export type ChipTone = 'neutral' | 'info' | 'warn' | 'danger';

/**
 * 主题安全的小标签（替代 antd `Tag` 在 DataStudio 里的用法）。
 *
 * <p><b>为什么不用 antd Tag（根因已定位并复现，2026-10-28）</b>：
 * DataStudio 的 `ConfigProvider` 把 `token.colorBgContainer` 设成了 **CSS 变量字符串**
 * `var(--primary-color)`（`pages/DataStudio/index.tsx`）。而 antd 5 的 Tag 默认底色是这样算的
 * （`antd/es/tag/style/index.js:112`）：
 *
 * <pre>
 * defaultBg = new TinyColor(colorFillQuaternary).onBackground(colorBgContainer).toHexString()
 * </pre>
 *
 * TinyColor 解析不了 `var(...)`，把它当黑色合成。实测（项目内同版本 @ctrl/tinycolor）：
 *
 * <pre>
 * rgba(255,255,255,0.08) on "var(--primary-color)" → #141414   （正确值应为 #3c3e41 / #f5f5f5）
 * "var(--primary-color)"  on "var(--primary-color)" → #000000
 * </pre>
 *
 * 于是 Tag 底色恒为近黑，而文字色取 `colorText`：**浅色主题下就是黑底黑字**（用户 2026-10-28
 * 反馈的"一团黑"：知识库「不支持预览」、只读查看器标题栏的库编码、语义资产反馈抽屉的
 * `待处理 0 / 已处理 0 / 已忽略 0` 都是这一条）。深色主题下则是近黑底 + 白字，能看见但发闷。
 *
 * <p>本组件的取舍：<b>前景色一律 `inherit`</b>（跟随所在容器，容器已经是主题正确色），
 * 背景与描边用中灰半透明（`rgba(128,128,128,*)`），任何底色上都有层次。代价是不支持
 * 任意外部色号——需要的场景请直接用 antd Tag 并自己保证对比度。
 *
 * <p>⚠️ 这是**局部绕过**，不是根治：只要 `colorBgContainer` 还是 CSS 变量串，所有由它派生的
 * antd 颜色（Tag 默认底色、`colorBgElevated` 系列）都会偏黑。根治需把该 token 换成真实色值
 * （按 `theme` 取 `#ffffff` / `#2b2d30`），影响面是整个 DataStudio，需单独评估。
 */
const TONE_STYLE: Record<ChipTone, React.CSSProperties> = {
  // 中性：只靠一层淡灰底把文字圈出来，文字色完全继承
  neutral: {
    background: 'rgba(128, 128, 128, 0.16)',
    border: '1px solid rgba(128, 128, 128, 0.28)',
    color: 'inherit'
  },
  info: {
    background: 'rgba(22, 119, 255, 0.14)',
    border: '1px solid rgba(22, 119, 255, 0.35)',
    color: '#4096ff'
  },
  warn: {
    background: 'rgba(230, 162, 60, 0.16)',
    border: '1px solid rgba(230, 162, 60, 0.38)',
    color: '#d89614'
  },
  danger: {
    background: 'rgba(255, 77, 79, 0.14)',
    border: '1px solid rgba(255, 77, 79, 0.36)',
    color: '#ff7875'
  }
};

const ThemeChip: React.FC<{
  tone?: ChipTone;
  title?: string;
  style?: React.CSSProperties;
  onClick?: () => void;
  children: React.ReactNode;
}> = ({ tone = 'neutral', title, style, onClick, children }) => (
  <span
    title={title}
    onClick={onClick}
    style={{
      display: 'inline-block',
      padding: '0 6px',
      margin: '0 2px',
      fontSize: 12,
      lineHeight: '18px',
      borderRadius: 4,
      whiteSpace: 'nowrap',
      cursor: onClick ? 'pointer' : undefined,
      ...TONE_STYLE[tone],
      ...style
    }}
  >
    {children}
  </span>
);

export default ThemeChip;
