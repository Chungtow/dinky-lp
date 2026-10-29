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

import { connect } from '@@/exports';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
import CodeEdit from '@/components/CustomEditor/CodeEdit';
import MarkdownView from '@/components/MarkdownView';
import ThemeChip from '@/components/ThemeChip';
import { l } from '@/utils/intl';
import { Segmented, Space, Typography } from 'antd';
import { editor } from 'monaco-editor';
import React, { useContext, useEffect, useMemo, useRef, useState } from 'react';

const { Text } = Typography;

/** 按扩展名给 monaco 语言模式（与 SemanticFile 同一口径，避免同一后缀两处高亮不同） */
const languageOf = (filename: string): string => {
  const lower = (filename ?? '').toLowerCase();
  if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'markdown';
  if (lower.endsWith('.sql')) return 'sql';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.json')) return 'json';
  if (lower.endsWith('.xml')) return 'xml';
  if (lower.endsWith('.py')) return 'python';
  if (lower.endsWith('.java')) return 'java';
  if (lower.endsWith('.scala')) return 'scala';
  if (lower.endsWith('.sh') || lower.endsWith('.bash')) return 'shell';
  if (lower.endsWith('.js')) return 'javascript';
  if (lower.endsWith('.ts')) return 'typescript';
  return 'plaintext';
};

const isMarkdown = (filename: string): boolean => /\.(md|markdown)$/i.test(filename ?? '');

/**
 * 知识库文档只读查看器（批次 B）。
 *
 * <p><b>为什么单独一个组件而不是复用 `SemanticFile`</b>：`SemanticFile` 带着保存按钮、
 * `mtime` 乐观锁与"反馈此资产"入口，那些对知识库文档全是**错误动作**——知识库正文由
 * kb-service 持有，`doc_id = sha256(正文)`，在 Dinky 里改一个字都会与 id 失配。
 * 复用会让人误以为能存，不如让这个组件在结构上**没有保存能力**（`readOnly` 恒 true）。
 *
 * <p>正文已经在打开时随 tab 参数带进来了（`params.statement`），这里**不再请求**
 * ——切 tab、重启页面都不该产生二次 RAG 请求（原文响应带 `immutable` 缓存头）。
 *
 * <p><b>Markdown 预览</b>（2026-10-28 需求）：Monaco 本身没有预览能力（那是 VS Code
 * workbench 的扩展功能），所以这里用 `MarkdownView`（react-markdown + remark-gfm）自己渲染；
 * 非 Markdown 文件不显示切换器，避免给出"点了没反应"的入口。
 */
const KbFile = (props: { tabData: CenterTab }) => {
  const { tabData } = props;
  const { docId, filename, statement, collectionNames } = tabData.params ?? {};
  const { editorRegistry } = useContext(DataStudioContext);
  const [code, setCode] = useState<string>(statement ?? '');
  const [mode, setMode] = useState<'source' | 'preview'>('source');
  const editorKey = `kb_${docId ?? tabData.id}`;
  const registered = useRef<boolean>(false);

  useEffect(() => {
    setCode(statement ?? '');
  }, [tabData.id]);

  useEffect(() => {
    return () => {
      if (registered.current) {
        editorRegistry?.unregister(editorKey);
      }
    };
  }, [editorKey]);

  const onMount = (instance: editor.IStandaloneCodeEditor) => {
    // 注册进去只为了让 AI Chat 能读到「当前查看的内容」；本组件不提供任何写回路径
    editorRegistry?.register(editorKey, instance);
    registered.current = true;
  };

  const markdown = isMarkdown(String(filename ?? ''));

  /** 归属库显示"名称"而非编码：编码（如 ima_kb_62blyRRR）对用户没有信息量 */
  const collectionLabels = useMemo(() => {
    const list: { code: string; name: string }[] = [];
    if (Array.isArray(collectionNames)) {
      collectionNames.forEach((item: { code: string; name?: string }) => {
        if (item?.code && !list.some((x) => x.code === item.code)) {
          list.push({ code: item.code, name: item.name || item.code });
        }
      });
    }
    return list;
  }, [collectionNames]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <Space
        size={6}
        wrap
        style={{
          padding: '4px 8px',
          borderBottom: '1px solid rgba(128,128,128,0.24)',
          width: '100%',
          justifyContent: 'space-between'
        }}
      >
        <Space size={6} wrap>
          <Text strong>{filename}</Text>
          <ThemeChip tone='warn'>{l('datastudio.kb.readOnlyTag')}</ThemeChip>
          {collectionLabels.map((item) => (
            <ThemeChip key={item.code} tone='info' title={item.code}>
              {item.name}
            </ThemeChip>
          ))}
        </Space>
        {markdown && (
          <Segmented
            size='small'
            value={mode}
            onChange={(value) => setMode(value as 'source' | 'preview')}
            options={[
              { label: l('datastudio.kb.viewSource'), value: 'source' },
              { label: l('datastudio.kb.viewPreview'), value: 'preview' }
            ]}
          />
        )}
      </Space>
      <div style={{ flex: 1, minHeight: 0 }}>
        {markdown && mode === 'preview' ? (
          <MarkdownView content={code} />
        ) : (
          <CodeEdit
            code={code}
            language={languageOf(String(filename ?? ''))}
            readOnly
            onChange={() => undefined}
            editorDidMount={onMount}
            options={{ wordWrap: 'on', minimap: { enabled: false } }}
          />
        )}
      </div>
    </div>
  );
};

// 与 SemanticFile 同一类既有告警（父组件传 tabData，dva 的 OwnProps 推断不成立），运行期注入仍是 updateCenterTab
export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(KbFile as any) as React.ComponentType<any>;
