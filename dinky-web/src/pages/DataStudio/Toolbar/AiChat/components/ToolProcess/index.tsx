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

import { CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined } from '@ant-design/icons';
import { l } from '@/utils/intl';
import React from 'react';
import { AiChatToolStep } from '../../service';

/**
 * 工具调用过程（阶段 1b：只读工具）。
 *
 * <p>模型决定调工具的那一轮<b>正文为空</b>（2026-09-28 探针实测：content 帧数为 0），
 * 若不把过程显示出来，用户看到的就是"点了提问之后黑屏几秒"——这是过程可见性的全部来源。
 *
 * <p><b>三态由后端驱动的 status 决定</b>：toolCall 帧下发 running，toolResult 帧覆盖为
 * success / failed。字段名必须与后端 {@code AiToolRunResult} 侧的 SSE 帧严格一致
 * （曾因后端发 success 布尔、前端读 status 字符串，导致终态永远停在"进行中"）。
 */
const toolLabel = (name: string): string => {
  switch (name) {
    case 'list_tables':
      return l('datastudio.aiChat.tool.listTables');
    case 'describe_table':
      return l('datastudio.aiChat.tool.describeTable');
    case 'sample_rows':
      return l('datastudio.aiChat.tool.sampleRows');
    default:
      return name;
  }
};

const ToolProcess = ({ steps }: { steps?: AiChatToolStep[] }) => {
  if (!steps || steps.length === 0) {
    return null;
  }
  return (
    <div style={{ marginBottom: 6 }}>
      {steps.map((step, index) => {
        const key = step.toolCallId || `${step.name}-${index}`;
        const label = toolLabel(step.name);
        const target = step.argsSummary ? `: ${step.argsSummary}` : '';

        let icon: React.ReactNode = (
          <LoadingOutlined style={{ color: 'rgba(22,119,255,0.75)', fontSize: 12 }} />
        );
        let text = `${l('datastudio.aiChat.tool.running')} ${label}${target}…`;

        if (step.status === 'success') {
          icon = <CheckCircleOutlined style={{ color: 'rgba(82,196,26,0.85)', fontSize: 12 }} />;
          text = `${label}${target} · ${l('datastudio.aiChat.tool.done')} · ${step.costMs ?? 0}ms`;
        } else if (step.status === 'failed') {
          icon = <CloseCircleOutlined style={{ color: 'rgba(255,77,79,0.85)', fontSize: 12 }} />;
          // 失败文案已在服务端脱敏（不含连接串 / 账号 / 内网地址），可直接展示
          text = `${label}${target} · ${l('datastudio.aiChat.tool.failed')}${
            step.error ? ` · ${step.error}` : ''
          }`;
        }

        return (
          <div
            key={key}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 6,
              fontSize: 12,
              lineHeight: '18px',
              color: 'rgba(0,0,0,0.55)',
              marginBottom: 2
            }}
          >
            {icon}
            <span style={{ whiteSpace: 'pre-wrap' }}>{text}</span>
          </div>
        );
      })}
    </div>
  );
};

export default ToolProcess;
