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
import { CenterTab } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
import { semanticEditorKey } from '@/pages/DataStudio/editorKey';
import { saveSemanticFile } from '@/pages/DataStudio/Toolbar/Semantic/service';
import {
  SemanticFeedbackModal,
  semanticAssetOf
} from '@/pages/DataStudio/Toolbar/Semantic/FeedbackModal';
import CodeEdit from '@/components/CustomEditor/CodeEdit';
import { HasAuthority } from '@/hooks/useAccess';
import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import { SuccessMessage, WarningMessage } from '@/utils/messages';
import { Space, Tag, Tooltip, Typography } from 'antd';
import { CommentOutlined, SaveOutlined } from '@ant-design/icons';
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
 * 语义文件编辑器的 options 覆盖（计划 §3.5 R7 / D8）。
 *
 * <p>这三项对 yaml 零收益，却要按行 / 按键计算：`formatOnType` 每次回车跑格式化、
 * `bracketPairColorization` 维护配色池、`inlineSuggest.showOnAllSymbols` 对每个符号都求一次内联建议。
 * **只在这里覆盖**，不动 `CodeEdit` 默认值（SqlTask 等使用方共用默认）。
 *
 * <p>提为模块常量：`CodeEdit` 内部按 options 引用做 `useMemo`，每次渲染新建对象会让 memo 失效。
 */
const SEMANTIC_EDITOR_OPTIONS = {
  wordWrap: 'on' as const,
  minimap: { enabled: false },
  inlineSuggest: { enabled: false },
  formatOnType: false,
  bracketPairColorization: { enabled: false }
};

/**
 * 语义文件编辑 tab（dbt on HDFS）。
 *
 * <p>内容与「纯文本作业」同构：正文保存在 {@code tabParams.statement}，因此 AI Chat 无需改造
 * 协议即可把它当作「当前编辑内容」；保存时以 {@code mtime} 作乐观锁，冲突提示刷新后重试。
 */
const SemanticFile = (props: { tabData: CenterTab; updateCenterTab: (payload: any) => void }) => {
  const { tabData, updateCenterTab } = props;
  const { project, path, statement, mtime, readOnly } = tabData.params ?? {};
  const { editorRegistry } = useContext(DataStudioContext);
  /**
   * 交互性能（2026-11-06）：编辑器改为**非受控**，内容以编辑器实例为唯一真相源。
   *
   * <p>改造前：`code` 受控 + 每次 onChange `setCode` + `updateCenterTab` 整篇回写 dva model，
   * 形成「按键 → setState → 组件重渲染 → props 落后于 model → `@monaco-editor/react`
   * 整篇 `executeEdits(getFullModelRange())`」环路，中文拼音组合文本被反复抹掉（用户反馈
   * 「拼音停留好一会儿才转中文」）。与 SqlTask 2026-11-05 的修法同构（D2/D3）。
   *
   * <p>改造后：内容只进 {@link latestCodeRef}；**保存 / AI Chat 取文一律现取**
   * （`editorRegistry.getContent(editorKey)`），不再每键写 model；`dirty` 只在
   * 「干净 ↔ 脏」两个翻转点 setState（D7）。
   */
  const latestCodeRef = useRef<string>(statement ?? '');
  const dirtyRef = useRef<boolean>(false);
  const [dirty, setDirty] = useState<boolean>(false);
  const [saving, setSaving] = useState<boolean>(false);
  const [baseMtime, setBaseMtime] = useState<number>(mtime ?? 0);
  const editorKey = semanticEditorKey(project, path);
  /** 无 datastudio:semantic:edit 时不允许保存（前端先拦一次，避免把预期内的权限拒绝打成"服务器错误"） */
  const canEdit = HasAuthority(PermissionConstants.DATASTUDIO_SEMANTIC_EDIT);
  /**
   * P4 一期：「反馈此资产」——**看**语义项目的人都能提（沿用 view 权限），
   * 不要求 edit：发现口径有误的往往正是只读使用方。
   */
  const canFeedback = HasAuthority(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW);
  const [feedbackOpen, setFeedbackOpen] = useState<boolean>(false);
  /** 资产名 / 类型由文件路径推断（models/ → TABLE，metrics/ → METRIC） */
  const feedbackAsset = semanticAssetOf(path);

  useEffect(() => {
    latestCodeRef.current = statement ?? '';
    dirtyRef.current = false;
    setDirty(false);
    setBaseMtime(mtime ?? 0);
    // 非受控模式下 defaultValue 只认首次挂载：同一实例被复用于另一份文件时，必须显式换内容
    if (editorKey && editorRegistry?.getContent(editorKey) !== (statement ?? '')) {
      editorRegistry?.applyFullContent(editorKey, statement ?? '');
    }
  }, [tabData.id]);

  useEffect(() => {
    return () => {
      if (editorKey) {
        editorRegistry?.unregister(editorKey);
      }
    };
  }, [editorKey]);

  /** 现取编辑器内容——非受控模式下的唯一取值出口（编辑器未挂载时回落到 ref） */
  const getContent = (): string => editorRegistry?.getContent(editorKey) ?? latestCodeRef.current ?? '';

  const onChange = (value?: string) => {
    const next = value ?? '';
    latestCodeRef.current = next;
    // 未保存标记只在翻转点 setState + 写一次 model（关闭提示依赖 tab 的 isUpdate）
    const nextDirty = next !== (statement ?? '');
    if (nextDirty !== dirtyRef.current) {
      dirtyRef.current = nextDirty;
      setDirty(nextDirty);
      updateCenterTab({ ...tabData, isUpdate: nextDirty });
    }
  };

  const onSave = async () => {
    if (!canEdit) {
      await WarningMessage(l('datastudio.semantic.noEditPermission'));
      return;
    }
    if (readOnly) {
      await WarningMessage(l('datastudio.semantic.readOnly'));
      return;
    }
    setSaving(true);
    try {
      const content = getContent();
      const res = await saveSemanticFile({ project, path, content, baseMtime });
      if (res?.code !== 0) {
        await WarningMessage(res?.msg ?? l('datastudio.semantic.saveFailed'));
        return;
      }
      const newMtime: number = res?.data ?? 0;
      setBaseMtime(newMtime);
      dirtyRef.current = false;
      setDirty(false);
      // 保存成功后仍要同步一次 model（AI Chat 回落、关闭提示、mtime 乐观锁）
      updateCenterTab({
        ...tabData,
        isUpdate: false,
        params: { ...tabData.params, statement: content, mtime: newMtime }
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
        <Space size={10}>
          {/* P4：口径有误的出口——就在正在看的文件上提，不用另找入口 */}
          {canFeedback && (
            <Tooltip title={l('datastudio.semantic.feedback.entryTip')}>
              <Text
                type={'secondary'}
                strong
                style={{ cursor: 'pointer' }}
                onClick={() => setFeedbackOpen(true)}
              >
                <CommentOutlined /> {l('datastudio.semantic.feedback.entry')}
              </Text>
            </Tooltip>
          )}
          <Tooltip
            title={
              !canEdit
                ? l('datastudio.semantic.noEditPermission')
                : readOnly
                  ? l('datastudio.semantic.readOnly')
                  : l('datastudio.semantic.save')
            }
          >
            <Text
              type={canEdit && !readOnly ? 'success' : 'secondary'}
              strong
              style={{ cursor: canEdit && !readOnly ? 'pointer' : 'not-allowed' }}
              onClick={onSave}
            >
              <SaveOutlined />{' '}
              {saving ? l('datastudio.semantic.saving') : l('datastudio.semantic.save')}
            </Text>
          </Tooltip>
        </Space>
      </Space>
      <div style={{ flex: 1, minHeight: 0 }}>
        <CodeEdit
          defaultValue={statement ?? ''}
          language={languageOf(path)}
          readOnly={Boolean(readOnly)}
          onChange={onChange}
          editorDidMount={onMount}
          // R4：关闭「按文档分词」的补全（默认 true）——它每键把全文词表塞进 CompletionItem，
          // 并每 3 秒对全文跑一次 `Intl.Segmenter`，是 YAML 独有的周期性停顿来源（SkillFile 早已关闭）
          wordSuggestionsEnabled={false}
          options={SEMANTIC_EDITOR_OPTIONS}
        />
      </div>

      {/* P4 一期：针对当前文件（资产）提交反馈；版本与 YAML 指纹由后端记录 */}
      <SemanticFeedbackModal
        open={feedbackOpen}
        onClose={() => setFeedbackOpen(false)}
        project={project}
        assetName={feedbackAsset.assetName}
        assetType={feedbackAsset.assetType}
      />
    </div>
  );
};

// connect 的 OwnProps 推断对本组件（父组件传 tabData）不成立，与 Toolbar/Resource 属同一类既有告警；
// 这里显式放宽组件类型，运行期注入仍是 mapDispatchToProps 的 updateCenterTab。
//
// 2026-11-06（R2）：不再订阅 `centerContent`——本组件从未使用该字段，订阅它会让每次 tab 变动都
// 重渲染一次编辑器（改造前是「每键一次」），是无谓开销。
export default connect(
  null,
  mapDispatchToProps
)(SemanticFile as any) as React.ComponentType<any>;
