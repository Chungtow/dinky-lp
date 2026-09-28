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

/** 单条消息的 SQL 校验状态（阶段 0：正确性闭环） */
export type AiChatVerify = {
  status?: 'verifying' | 'verified' | 'failed' | 'rejected' | 'retrying';
  sql?: string;
  success?: boolean;
  executed?: boolean;
  rejected?: boolean;
  rowCount?: number;
  costMs?: number;
  error?: string;
};

/**
 * 一次工具调用的展示状态（阶段 1b：只读工具）。
 *
 * <p>后端按 toolCallId 先后下发 toolCall（已发起）与 toolResult（已结束）两帧，前端据此把同一条
 * 记录从"执行中"更新为"成功/失败"。
 */
export type AiChatToolStep = {
  toolCallId: string;
  name: string;
  argsSummary?: string;
  status: 'running' | 'success' | 'failed';
  costMs?: number;
  error?: string;
};

/** 页面内的一条消息（reasoning 为模型的思考过程，仅部分模型提供） */
export type AiChatMessage = {
  role: AiChatRole;
  content: string;
  reasoning?: string;
  verify?: AiChatVerify;
  /** 工具调用过程；只能按 toolCallId 追加或就地更新，不做重排 */
  tools?: AiChatToolStep[];
};

/** 前端可用的 AI 配置（不含密钥） */
export type AiChatConfig = {
  enable?: boolean;
  model?: string;
  baseUrl?: string;
  hasApiKey?: boolean;
};

/**
 * {@code @} 引用项（与后端 {@code AiChatMention} 一一对应）。
 *
 * <p>type 预留 knowledge：后续「语料包」能力可直接复用本结构，前端无需改协议。
 */
export type AiChatMentionItem = {
  type: 'table' | 'job' | 'selection' | 'knowledge';
  schemaName?: string;
  name: string;
  /** 片段正文（selection / job 携带），仅编辑器文本，不含业务数据行 */
  content?: string;
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
  /** 当前作业 id（阶段 1.0 已在使用，此前类型漏声明） */
  taskId?: number;
  /** 编辑区选中片段：非空时后端优先采用它，而非 sql 全文（阶段 1a：1.0.4） */
  selectedSql?: string;
  /** 上下文范围档位（阶段 1a：1.1） */
  contextScope?: 'current' | 'all' | 'custom';
  /** custom 档位下勾选的表名 */
  customTables?: string[];
  /** {@code @} 显式引用项：最高优先级，后端不做预算裁剪 */
  mentions?: AiChatMentionItem[];
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
  onFrame: (frame: {
    content?: string;
    reasoning?: string;
    sql?: string;
    status?: string;
    execResult?: AiChatVerify;
    /** 工具已发起（可能尚在执行） */
    toolCall?: AiChatToolStep;
    /** 工具执行结束（成功或失败） */
    toolResult?: AiChatToolStep;
  }) => void,
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
        if (typeof frame?.sql === 'string' && frame.sql.length > 0) {
          onFrame({ sql: frame.sql });
        }
        if (typeof frame?.status === 'string' && frame.status.length > 0) {
          onFrame({ status: frame.status });
        }
        if (frame?.execResult && typeof frame.execResult === 'object') {
          onFrame({ execResult: frame.execResult });
        }
        if (frame?.toolCall && typeof frame.toolCall === 'object') {
          onFrame({ toolCall: frame.toolCall });
        }
        if (frame?.toolResult && typeof frame.toolResult === 'object') {
          onFrame({ toolResult: frame.toolResult });
        }
        // 其余帧（如 heartbeat）无需处理：此处刻意保持「未知帧静默丢弃」，
        // 避免把结构化数据误当成正文拼接出来。
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
