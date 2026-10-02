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

export type SqlDiffProps = {
  open: boolean;
  /** 用户选中的原文，作为 diff 左侧基准 */
  original: string;
  /** AI 产出的改写结果，作为 diff 右侧 */
  modified: string;
  language?: string;
  /** 采纳：以 modified 替换编辑器的「选中片段」 */
  onAccept: () => void;
  /** 拒绝：丢弃本次改写，编辑器保持原样 */
  onReject: () => void;
};

/**
 * 阶段 2b（局部改写）的改动预览。
 *
 * <p>范式与 {@code CraftDiff} 一致（同一个 {@code DiffEditor} + 自定义语言注册 + 主题转换，
 * <b>零新增依赖</b>），差异在于：
 * <ul>
 *   <li>这里是「选中片段 vs AI 稿」，而非「编辑器全文 vs AI 稿」；</li>
 *   <li>采纳后<b>只替换选中范围</b>（{@code applyToSelection}），选区之外不动；</li>
 *   <li>关闭弹窗（遮罩点击 / 右上角 ×）一律等同<b>拒绝</b>，杜绝静默替换。</li>
 * </ul>
 */
const SqlDiff: React.FC<SqlDiffProps> = (props) => {
  const { open, original, modified, language = 'flinksql', onAccept, onReject } = props;

  return (
    <Modal
      title={l('datastudio.aiChat.p2b.diffTitle')}
      open={open}
      maskClosable={false}
      width={'75%'}
      destroyOnClose
      onCancel={onReject}
      footer={
        <Space>
          <Button onClick={onReject}>{l('datastudio.aiChat.p2b.reject')}</Button>
          <Button type={'primary'} onClick={onAccept}>
            {l('datastudio.aiChat.p2b.accept')}
          </Button>
        </Space>
      }
    >
      <div style={{ margin: '8px 0' }}>
        <Alert type={'info'} showIcon message={l('datastudio.aiChat.p2b.diffTip')} />
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

export default memo(SqlDiff);
