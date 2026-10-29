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

import { LoadCustomEditorLanguage } from '@/components/CustomEditor/languages';
import { DIFF_EDITOR_PARAMS } from '@/pages/DataStudio/CenterTabContent/SqlTask/constants';
import { convertCodeEditTheme } from '@/utils/function';
import { l } from '@/utils/intl';
import { DiffEditor, loader } from '@monaco-editor/react';
import { Alert, Button, Modal, Space } from 'antd';
import * as monaco from 'monaco-editor';
import React, { memo } from 'react';

loader.config({ monaco });

export type CraftDiffProps = {
  open: boolean;
  /** 编辑器当前内容（原文），作为 diff 左侧基准 */
  original: string;
  /** AI 产出的完整目标内容，作为 diff 右侧 */
  modified: string;
  language?: string;
  /** 采纳：以 modified 整块替换编辑器内容 */
  onAccept: () => void;
  /** 拒绝：丢弃本次改动，编辑器保持原样 */
  onReject: () => void;
};

/**
 * Craft 模式的改动预览（阶段 2：T2-3）。
 *
 * <p>范式照抄 {@code DiffModal}：同一个 {@code DiffEditor} + 自定义语言注册 + 主题转换，
 * <b>零新增依赖</b>。差异在于：
 * <ul>
 *   <li>这里是「编辑器当前内容 vs AI 稿」，而非「服务端版本 vs 缓存版本」；</li>
 *   <li>只提供<b>全部采纳 / 全部拒绝</b>两个出口——本轮不做逐 hunk 采纳（计划 U6 已明确）；</li>
 *   <li>关闭弹窗（含遮罩点击 / 右上角 ×）一律等同<b>拒绝</b>，杜绝"关掉就算了"的静默写入。</li>
 * </ul>
 */
const CraftDiff: React.FC<CraftDiffProps> = (props) => {
  const { open, original, modified, language = 'flinksql', onAccept, onReject } = props;

  return (
    <Modal
      title={l('datastudio.aiChat.craft.diffTitle')}
      open={open}
      maskClosable={false}
      width={'75%'}
      destroyOnClose
      onCancel={onReject}
      footer={
        <Space>
          <Button onClick={onReject}>{l('datastudio.aiChat.craft.reject')}</Button>
          <Button type={'primary'} onClick={onAccept}>
            {l('datastudio.aiChat.craft.accept')}
          </Button>
        </Space>
      }
    >
      <div style={{ margin: '8px 0' }}>
        <Alert type={'info'} showIcon message={l('datastudio.aiChat.craft.diffTip')} />
      </div>
      <div style={{ height: '52vh' }}>
        <DiffEditor
          {...DIFF_EDITOR_PARAMS}
          language={language}
          // 挂载前加载语言 | Load language before mounting
          beforeMount={(monacoIns) => LoadCustomEditorLanguage(monacoIns.languages, monacoIns.editor)}
          original={original}
          modified={modified}
          theme={convertCodeEditTheme()}
        />
      </div>
    </Modal>
  );
};

export default memo(CraftDiff);
