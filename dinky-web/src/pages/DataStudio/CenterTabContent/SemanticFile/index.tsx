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
import { semanticEditorKey } from '@/pages/DataStudio/editorKey';
import { saveSemanticFile } from '@/pages/DataStudio/Toolbar/Semantic/service';
import CodeEdit from '@/components/CustomEditor/CodeEdit';
import { l } from '@/utils/intl';
import { SuccessMessage, WarningMessage } from '@/utils/messages';
import { Space, Tag, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { editor } from 'monaco-editor';
import React, { useContext, useEffect, useRef, useState } from 'react';

const { Text } = Typography;

/** 按扩展名给 monaco 语言模式（.yml 走 yaml，SQL 走 sql，其余 plaintext） */
const languageOf = (path: string): string => {
  const lower = (path ?? '').toLowerCase();
  if (lower.endsWith('.sql')) return 'sql';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.md')) return 'markdown';
  if (lower.endsWith('.json')) return 'json';
  return 'plaintext';
};

/**
 * 语义文件编辑 tab（dbt on HDFS）。
 *
 * <p>内容与「纯文本作业」同构：正文保存在 {@code tabParams.statement}，因此 AI Chat 无需改造
 * 协议即可把它当作「当前编辑内容」；保存时以 {@code mtime} 作乐观锁，冲突提示刷新后重试。
 */
const SemanticFile = (props: {
  tabData: CenterTab;
  updateCenterTab: (payload: any) => void;
}) => {
  const { tabData, updateCenterTab } = props;
  const { project, path, statement, mtime, readOnly } = tabData.params ?? {};
  const { editorRegistry } = useContext(DataStudioContext);
  const [code, setCode] = useState<string>(statement ?? '');
  const [dirty, setDirty] = useState<boolean>(false);
  const [saving, setSaving] = useState<boolean>(false);
  const [baseMtime, setBaseMtime] = useState<number>(mtime ?? 0);
  const editorKey = semanticEditorKey(project, path);

  useEffect(() => {
    setCode(statement ?? '');
    setDirty(false);
    setBaseMtime(mtime ?? 0);
  }, [tabData.id]);

  useEffect(() => {
    return () => {
      if (editorKey) {
        editorRegistry?.unregister(editorKey);
      }
    };
  }, [editorKey]);

  const onChange = (value?: string) => {
    const next = value ?? '';
    setCode(next);
    setDirty(next !== (statement ?? ''));
    updateCenterTab({ ...tabData, isUpdate: true, params: { ...tabData.params, statement: next } });
  };

  const onSave = async () => {
    if (readOnly) {
      await WarningMessage(l('datastudio.semantic.readOnly'));
      return;
    }
    setSaving(true);
    try {
      const res = await saveSemanticFile({ project, path, content: code, baseMtime });
      if (res?.code !== 0) {
        await WarningMessage(res?.msg ?? l('datastudio.semantic.saveFailed'));
        return;
      }
      const newMtime: number = res?.data ?? 0;
      setBaseMtime(newMtime);
      setDirty(false);
      updateCenterTab({
        ...tabData,
        isUpdate: false,
        params: { ...tabData.params, statement: code, mtime: newMtime }
      });
      await SuccessMessage(l('datastudio.semantic.saved'));
    } catch (e: any) {
      await WarningMessage(e?.message ?? l('datastudio.semantic.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  const onMount = (instance: editor.IStandaloneCodeEditor) => {
    if (editorKey) {
      // 与 AiChat 的 resolveEditorKey 必须一致，否则选区读取为空
      editorRegistry?.register(editorKey, instance);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 165px)' }}>
      <Space style={{ padding: '4px 8px', justifyContent: 'space-between', width: '100%' }}>
        <Space size={6}>
          <Text type='secondary' strong>
            {project}/{path}
          </Text>
          {readOnly && <Tag color='orange'>{l('datastudio.semantic.readOnlyTag')}</Tag>}
          {dirty && <Tag color='processing'>{l('datastudio.semantic.unsaved')}</Tag>}
          {!dirty && !readOnly && <Tag color='success'>{l('datastudio.semantic.synced')}</Tag>}
        </Space>
        <Text
          type='success'
          strong
          style={{ cursor: readOnly ? 'not-allowed' : 'pointer' }}
          onClick={onSave}>
          <SaveOutlined /> {saving ? l('datastudio.semantic.saving') : l('datastudio.semantic.save')}
        </Text>
      </Space>
      <div style={{ flex: 1, minHeight: 0 }}>
        <CodeEdit
          code={code}
          language={languageOf(path)}
          readOnly={Boolean(readOnly)}
          onChange={onChange}
          editorDidMount={onMount}
          options={{ wordWrap: 'on', minimap: { enabled: false } }}
        />
      </div>
    </div>
  );
};

// connect 的 OwnProps 推断对本组件（父组件传 tabData）不成立，与 Toolbar/Resource 属同一类既有告警；
// 这里显式放宽组件类型，运行期注入仍是 mapDispatchToProps 的 updateCenterTab。
export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(SemanticFile as any) as React.ComponentType<any>;
