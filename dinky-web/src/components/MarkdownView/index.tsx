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
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './index.less';

/**
 * Markdown 渲染视图（知识库文档预览用）。
 *
 * <p><b>为什么不是 Monaco 自带能力</b>：Monaco 只管"编辑/高亮文本"，Markdown 预览是
 * VS Code <b>workbench</b> 层的功能（`markdown-language-features` 扩展），monaco-editor
 * 这个 npm 包本身不提供 preview API。所以预览只能自己渲染 HTML——这里用仓库已有的
 * `react-markdown` + `remark-gfm`（AI Chat 也在用，不新增依赖）。
 *
 * <p><b>安全</b>：`react-markdown` 默认<b>不渲染原始 HTML</b>（不启用 `rehype-raw`），
 * 知识库正文来自外部系统（IMA 等），可能有 `<script>`/`<iframe>`——保持默认即天然免疫。
 * 需要放行 HTML 时必须同时接 `rehype-sanitize`，不要直接开 `rehype-raw`。
 */
const MarkdownView: React.FC<{ content: string }> = ({ content }) => (
  <div className={'dinky-md'} style={{ overflow: 'auto', height: '100%' }}>
    <ReactMarkdown remarkPlugins={[remarkGfm]}>{content ?? ''}</ReactMarkdown>
  </div>
);

export default MarkdownView;
