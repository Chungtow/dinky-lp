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

import { hashContent } from '@/components/CustomEditor/mentionCompletion';
import { validateSkillContent } from '@/pages/RegCenter/Skills/service';
import { l } from '@/utils/intl';
import { notification } from 'antd';

/** 通知里最多列出的问题句柄数（再多就折叠，避免弹窗高过屏幕） */
const MAX_LISTED = 8;

/**
 * RegCenter 侧的保存后句柄校验（E4）。
 *
 * <p><b>为什么用 notification 而不是 marker</b>：RegCenter 的正文是 `<TextArea>`，
 * 没有 Monaco 的 marker 能力（本批不为此引入 Monaco，计划风险 5）；这里按行号列出问题句柄，
 * 用户能定位到具体行。
 *
 * <p><b>绝不阻断保存</b>：调用方都在保存成功之后才调用本函数；本函数自身失败也不抛错。
 */
export const notifyHandleValidation = async (content: string): Promise<void> => {
  const result = await validateSkillContent(content, hashContent(content));
  if (!result) {
    return;
  }
  const problems = (result.handles ?? []).filter((handle) => handle.status !== 'ok');
  const bareCount = result.summary?.bareOnly ?? 0;
  const bareHint = bareCount > 0 ? l('pages.skill.validate.bareHint', undefined, { count: bareCount }) : '';
  if (problems.length === 0) {
    if (bareHint) {
      notification.info({
        message: l('pages.skill.validate.title'),
        description: bareHint,
        duration: 5
      });
    }
    return;
  }
  notification.warning({
    message: l('pages.skill.validate.title'),
    description: (
      <div>
        {problems.slice(0, MAX_LISTED).map((handle) => (
          <div key={`${handle.line}:${handle.column}`}>
            {l('pages.skill.validate.line', undefined, { line: handle.line })} {handle.raw} —{' '}
            {handle.message || handle.status}
          </div>
        ))}
        {problems.length > MAX_LISTED && <div>… (+{problems.length - MAX_LISTED})</div>}
        {bareHint && <div style={{ marginTop: 6 }}>{bareHint}</div>}
      </div>
    ),
    duration: 8
  });
};
