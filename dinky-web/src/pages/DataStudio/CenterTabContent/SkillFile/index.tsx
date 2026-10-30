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
import CodeEdit from '@/components/CustomEditor/CodeEdit';
import {
  hashContent,
  registerMentionHover,
  setMentionMarkers
} from '@/components/CustomEditor/mentionCompletion';
import { attachMentionController } from '@/components/CustomEditor/mentionController';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import { DataStudioContext } from '@/pages/DataStudio/DataStudioContext';
import { skillEditorKey } from '@/pages/DataStudio/editorKey';
import { detailSkill, validateSkillContent, writeSkillFile } from '@/pages/RegCenter/Skills/service';
import { l } from '@/utils/intl';
import { InfoMessage, SuccessMessage, WarningMessage } from '@/utils/messages';
import { SaveOutlined } from '@ant-design/icons';
import ThemeChip from '@/components/ThemeChip';
import { Space, Tooltip, Typography } from 'antd';
import { editor } from 'monaco-editor';
import React, { useContext, useEffect, useRef, useState } from 'react';

const { Text } = Typography;

/** skill / doc 正文的语言模式（`@` 补全与句柄校验只在这个模式下生效） */
const MARKDOWN = 'markdown';

/** 按扩展名给 monaco 语言模式（SKILL.md 走 markdown，yaml/json 照旧） */
const languageOf = (path?: string): string => {
  const lower = (path ?? '').toLowerCase();
  if (lower.endsWith('.md')) return 'markdown';
  if (lower.endsWith('.yml') || lower.endsWith('.yaml')) return 'yaml';
  if (lower.endsWith('.json')) return 'json';
  if (lower.endsWith('.sql')) return 'sql';
  if (lower.endsWith('.py')) return 'python';
  return 'plaintext';
};

/**
 * skill 文件编辑 tab（批次 F）。
 *
 * <p><b>与语义文件 tab 的同构点</b>：正文都放在 {@code tabParams.statement}，因此 AI Chat
 * 无需改协议就能把"当前正在编辑的 skill"当作上下文；保存都是"带编辑起点标记的乐观锁"。
 *
 * <p><b>两点差异</b>：
 * <ol>
 *   <li>skill 的写权限是<b>属主独占</b>（后端 {@code checkOwner}），不是权限点，所以
 *       {@code readOnly} 由打开时判定并写进 params，而不是这里查 {@code HasAuthority}；</li>
 *   <li>skill 没有 mtime，只有单调递增的 {@code version}，且<b>仅主文件</b>递增——
 *       所以附件的 {@code baseVersion} 后端会忽略（见 {@code SkillService#writeFile}）。</li>
 * </ol>
 */
const SkillFile = (props: { tabData: CenterTab; updateCenterTab: (payload: any) => void }) => {
  const { tabData, updateCenterTab } = props;
  const { skillId, skillName, relativePath, mainFile, statement, version, readOnly } =
    tabData.params ?? {};
  const [code, setCode] = useState<string>(statement ?? '');
  const [dirty, setDirty] = useState<boolean>(false);
  const [saving, setSaving] = useState<boolean>(false);
  /** 编辑起点的 skill 版本（主文件保存成功后刷新） */
  const [baseVersion, setBaseVersion] = useState<number | undefined>(version);
  const displayPath = relativePath || (mainFile ? 'SKILL.md' : '');
  const { editorRegistry } = useContext(DataStudioContext);
  /**
   * 编辑器键（与 AiChat 的 {@code resolveEditorKey} 必须完全一致）。
   *
   * <p>AI Chat 用这个键读「原文」做 diff 左栏、并在用户采纳后回写编辑器。此前 skill tab
   * 没有键 → 左侧恒空、采纳写不进去（同 2026-10-21 语义文件 tab 的 U9 缺陷）。
   */
  const editorKey = skillEditorKey(skillId, relativePath || (mainFile ? 'SKILL.md' : undefined));

  /**
   * 「最后一次已知的 props 正文」——用来区分"外部换了内容"和"用户自己在敲键盘"。
   */
  const incomingRef = useRef<string>(statement ?? '');

  /** 只有 markdown 正文才注册 `@` 补全 / 悬停 / 校验标记（yaml/json/sql 附件不涉及句柄） */
  const isMarkdown = languageOf(relativePath || 'SKILL.md') === MARKDOWN;

  const monacoRef = useRef<any>(null);

  const modelRef = useRef<editor.ITextModel | null>(null);

  /** provider 必须在卸载时 dispose，否则重开同一个 tab 会叠出多份候选 */
  const disposablesRef = useRef<{ dispose: () => void }[]>([]);

  /** 最近一次成功校验的正文指纹（同一份正文不重复打接口，计划风险 12） */
  const validatedHashRef = useRef<string>('');

  /** 最近一次正文（校验返回时判断结果是否已过期，计划风险 8） */
  const latestCodeRef = useRef<string>(statement ?? '');

  const validateTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  /**
   * 外部正文 / 版本同步（UAT 2026-10-29：关掉再打开还是旧版本、旧正文）。
   *
   * <p>中间区用 react-activation 的 {@code KeepAlive} 按 tab id 缓存组件：关掉 tab 只是让组件
   * 失活，内部 state 仍留在缓存里；再次打开<b>同一个路径</b>时组件被复用，{@code useState} 的初值
   * 不会再执行 → 编辑器拿着的还是上次那份正文，而 tab params 里其实已经是新读到的内容。
   *
   * <p>所以必须监听 props 而不是只依赖 {@code tabData.id}（id 没变，依赖不会触发）。用 ref 记住
   * 已知正文、并在 {@code onChange} 里同步更新它，这样用户自己的输入不会被误判成"外部变更"
   * 而清掉 dirty 标记。
   */
  useEffect(() => {
    const incoming = statement ?? '';
    if (incoming !== incomingRef.current) {
      incomingRef.current = incoming;
      latestCodeRef.current = incoming;
      setCode(incoming);
      setDirty(false);
    }
    setBaseVersion(version);
  }, [statement, version]);

  useEffect(() => {
    return () => {
      if (editorKey) {
        editorRegistry?.unregister(editorKey);
      }
    };
  }, [editorKey]);

  /**
   * 卸载清理：provider 不 dispose 会一直挂在 monaco 的 markdown 语言上
   * （tab 每开一次就多一份候选，最终表现为"同一个表出现 5 次"）。
   */
  useEffect(() => {
    return () => {
      disposablesRef.current.forEach((item) => item.dispose());
      disposablesRef.current = [];
      if (validateTimerRef.current) {
        clearTimeout(validateTimerRef.current);
        validateTimerRef.current = null;
      }
    };
  }, []);

  const onMount = (instance: editor.IStandaloneCodeEditor, monacoIns: any) => {
    if (editorKey) {
      editorRegistry?.register(editorKey, instance);
    }
    if (!isMarkdown || !monacoIns) {
      return;
    }
    // 只在 markdown 的 skill 正文上注册：既有 SQL / FlinkSQL 补全按 language 隔离，不受影响
    monacoRef.current = monacoIns;
    modelRef.current = instance.getModel();
    // `@` 候选面板：照搬 AI Chat 的做法（自管开合/键盘/插入），不用 Monaco 的 suggest widget。
    // 详见 components/CustomEditor/mentionController.ts 顶部说明。
    attachMentionController(monacoIns, instance, { languageId: MARKDOWN });
    disposablesRef.current.push(registerMentionHover(monacoIns, { languageId: MARKDOWN }));
  };

  /**
   * 保存后 / 停手 800ms 后的句柄校验。
   *
   * <p><b>为什么要比对指纹</b>：校验结果是"提交那一刻的行列"，用户在校验返回前继续敲字就会标错位置
   * （计划风险 8）。这里用 hash 双向去重：同一份正文不重复请求，回来的结果与当前正文不一致就丢弃。
   */
  const runValidate = async (value: string) => {
    if (!isMarkdown) {
      return;
    }
    const hash = hashContent(value);
    if (hash === validatedHashRef.current) {
      return;
    }
    validatedHashRef.current = hash;
    const result = await validateSkillContent(value, hash);
    if (!result) {
      return;
    }
    if (result.contentHash && result.contentHash !== hashContent(latestCodeRef.current)) {
      // 结果已过期（正文又变了）：不打 marker，等下一轮
      validatedHashRef.current = '';
      return;
    }
    setMentionMarkers(monacoRef.current, modelRef.current, result);
    if (result.summary.bareOnly > 0) {
      // 裸写只提示一次汇总，不逐个画波浪线（否则存量 skill 一打开满屏黄线）
      await InfoMessage(l('datastudio.skill.validate.bareHint', undefined, { count: result.summary.bareOnly }));
    }
  };

  const onChange = (value?: string) => {
    const next = value ?? '';
    setCode(next);
    latestCodeRef.current = next;
    // 自己敲的键也要记进 ref，否则同步 effect 会把这次改动当成"外部换内容"而清掉 dirty
    incomingRef.current = next;
    setDirty(next !== (statement ?? ''));
    // 与语义文件同款：正文实时写回 tab params，AI Chat 读到的就是"用户正在编辑的内容"
    updateCenterTab({ ...tabData, isUpdate: true, params: { ...tabData.params, statement: next } });
    // 停手 800ms 后静默校验一次（与保存后那次共用指纹去重，不会打两次接口）
    if (isMarkdown && !readOnly) {
      if (validateTimerRef.current) {
        clearTimeout(validateTimerRef.current);
      }
      validateTimerRef.current = setTimeout(() => {
        validateTimerRef.current = null;
        runValidate(next);
      }, 800);
    }
  };

  const onSave = async () => {
    if (readOnly) {
      await WarningMessage(l('datastudio.skill.readOnly'));
      return;
    }
    if (!relativePath) {
      // 写接口不接受空路径（SkillPathGuard 会判"不允许的文件类型"），这里先拦一次给出可读提示
      await WarningMessage(l('datastudio.skill.pathMissing'));
      return;
    }
    setSaving(true);
    try {
      // handleOption 内部已提示成功/失败（冲突时后端返回的是"该 skill 已被更新…请刷新后重试"）
      const res = await writeSkillFile({
        skillId,
        relativePath,
        content: code,
        baseVersion: mainFile ? baseVersion : undefined
      });
      if (!res) {
        return;
      }
      setDirty(false);
      // 版本号以后端为准：主文件保存后 version+1，继续用旧值编辑会让下一次保存被误判为冲突
      const detail = await detailSkill(skillId);
      const nextVersion = detail?.skill?.version;
      setBaseVersion(nextVersion);
      updateCenterTab({
        ...tabData,
        isUpdate: false,
        params: { ...tabData.params, statement: code, version: nextVersion }
      });
      await SuccessMessage(l('datastudio.skill.saved'));
      // 保存已成功 → 再跑一次校验打 marker（警告不阻断，失败也不打扰用户）
      if (isMarkdown) {
        validatedHashRef.current = '';
        await runValidate(code);
      }
    } catch (e: any) {
      await WarningMessage(e?.message ?? l('datastudio.skill.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 165px)' }}>
      <Space style={{ padding: '4px 8px', justifyContent: 'space-between', width: '100%' }}>
        <Space size={6}>
          <Text type={'secondary'} strong>
            {skillName} / {displayPath}
          </Text>
          {mainFile && <ThemeChip tone="info">{l('datastudio.skill.mainFile')}</ThemeChip>}
          {readOnly && <ThemeChip tone="warn">{l('datastudio.skill.readOnlyTag')}</ThemeChip>}
          {dirty && <ThemeChip tone="warn">{l('datastudio.skill.unsaved')}</ThemeChip>}
          {!dirty && !readOnly && <ThemeChip>{l('datastudio.skill.synced')}</ThemeChip>}
          {baseVersion !== undefined && (
            <ThemeChip>{l('datastudio.skill.version', undefined, { version: baseVersion })}</ThemeChip>
          )}
        </Space>
        <Space size={10}>
          <Tooltip title={readOnly ? l('datastudio.skill.readOnly') : l('datastudio.skill.save')}>
            <Text
              type={readOnly ? 'secondary' : 'success'}
              strong
              style={{ cursor: readOnly ? 'not-allowed' : 'pointer' }}
              onClick={onSave}
            >
              <SaveOutlined /> {saving ? l('datastudio.skill.saving') : l('datastudio.skill.save')}
            </Text>
          </Tooltip>
        </Space>
      </Space>
      <div style={{ flex: 1, minHeight: 0 }}>
        <CodeEdit
          code={code}
          language={languageOf(relativePath || 'SKILL.md')}
          readOnly={Boolean(readOnly)}
          onChange={onChange}
          editorDidMount={onMount}
          // 关掉编辑器自带的"按文档分词"补全：它会和 `@` 句柄候选混在一起，
          // 且在 skill 场景下会一直卡在 Loading（详见 CodeEdit 的 wordSuggestionsEnabled 注释）
          wordSuggestionsEnabled={false}
          options={{
            wordWrap: 'on',
            minimap: { enabled: false },
            // CodeEdit 默认把 quickSuggestions 关掉（enableSuggestions=false），而这会连带
            // 关掉「输入触发字符自动弹候选」——`@` 敲下去毫无反应就是这个原因（UAT 2026-10-30）。
            // 这里只给 skill 正文打开自动候选，且关掉词补全，避免文档里的词混进 @ 候选列表。
            quickSuggestions: { other: true, comments: false, strings: false },
            suggestOnTriggerCharacters: true,
            wordBasedSuggestions: 'off',
            suggest: { quickSuggestions: { other: true, comments: false, strings: false } },
            // 补全 widget 默认被编辑器容器裁剪；技能 tab 的父容器有 overflow，需固定定位才不被截断
            fixedOverflowWidgets: true
          }}
        />
      </div>
    </div>
  );
};

// 与 SemanticFile 同款：父组件传 tabData，OwnProps 推断不成立，这里显式放宽类型
export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(SkillFile as any) as React.ComponentType<any>;
