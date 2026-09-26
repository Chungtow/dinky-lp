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

import { getData } from '@/services/api';
import { API_CONSTANTS } from '@/services/endpoints';

export type AiChatRole = 'user' | 'assistant';

/** 页面内的一条消息（reasoning 为模型的思考过程，仅部分模型提供） */
export type AiChatMessage = {
  role: AiChatRole;
  content: string;
  reasoning?: string;
};

/** 前端可用的 AI 配置（不含密钥） */
export type AiChatConfig = {
  enable?: boolean;
  model?: string;
  baseUrl?: string;
  hasApiKey?: boolean;
};

/** 对话请求体：只携带元数据定位信息，绝不携带业务数据 */
export type AiChatRequestBody = {
  action?: 'TEXT_TO_SQL' | 'EXPLAIN';
  message?: string;
  history?: { role: string; content: string }[];
  sessionId?: string;
  databaseId?: number;
  schemaName?: string;
  tableName?: string;
  dialect?: string;
  sql?: string;
};

export const getAiChatConfig = async (): Promise<AiChatConfig> => {
  const res = await getData(API_CONSTANTS.AI_CHAT_CONFIG);
  return (res?.data ?? {}) as AiChatConfig;
};

/**
 * 流式对话：读取 SSE 帧并回调文本片段。
 *
 * <p>后端按 JSON 帧下发（<code>data: {"content":"..."}</code> / <code>{"error":"..."}</code>），
 * 因此不会出现多行文本被 SSE 协议截断的问题。
 */
export const aiChatStream = async (
  body: AiChatRequestBody,
  onFrame: (frame: { content?: string; reasoning?: string }) => void,
  onError?: (message: string) => void,
  signal?: AbortSignal
): Promise<void> => {
  const response = await fetch(API_CONSTANTS.AI_CHAT_CHAT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(body),
    signal
  });

  if (!response.ok || !response.body) {
    throw new Error(`请求失败（HTTP ${response.status}）`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    // 最后一段可能是不完整的一行，留在 buffer 里等下一批数据
    buffer = lines.pop() ?? '';
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line.startsWith('data:')) {
        continue;
      }
      const data = line.slice(5).trim();
      if (!data) {
        continue;
      }
      try {
        const frame = JSON.parse(data);
        if (typeof frame?.content === 'string' && frame.content.length > 0) {
          onFrame({ content: frame.content });
        }
        if (typeof frame?.reasoning === 'string' && frame.reasoning.length > 0) {
          onFrame({ reasoning: frame.reasoning });
        }
        if (typeof frame?.error === 'string' && frame.error.length > 0) {
          onError?.(frame.error);
        }
      } catch (e) {
        // 非 JSON 帧（兼容旧格式）：补回换行，避免多行 SQL 被压成一行
        onFrame({ content: `${data}\n` });
      }
    }
  }
};
