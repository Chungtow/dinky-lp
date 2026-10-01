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
import { queryDataByParams } from '@/services/BusinessCrud';
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
  /** Craft 模式是否开启（管理员配置）；未开启时前端不渲染模式切换控件 */
  craftModeEnable?: boolean;
};

/**
 * {@code @} 引用项（与后端 {@code AiChatMention} 一一对应）。
 *
 * <p>type 预留 knowledge：后续「语料包」能力可直接复用本结构，前端无需改协议。
 */
export type AiChatMentionItem = {
  type: 'table' | 'job' | 'selection' | 'knowledge' | 'column';
  schemaName?: string;
  name: string;
  /**
   * 字段名（type=column 时有效）。
   *
   * <p>column 类型下 name 存的是<b>表名</b>而非字段名：输入框里写 {@code @表名.字段}，
   * 而「删掉 @ 文本时同步移除 chip」的判定（value.includes('@' + name)）依然成立。
   */
  columnName?: string;
  /** 片段正文（selection / job 携带），仅编辑器文本，不含业务数据行 */
  content?: string;
};

/** 对话请求体：只携带元数据定位信息，绝不携带业务数据 */
export type AiChatRequestBody = {
  /**
   * 动作（阶段 2b 新增 FIX_SQL / REWRITE_SQL）：
   * TEXT_TO_SQL（取数）/ EXPLAIN（解释）/ FIX_SQL（基于最近报错修复选中 SQL）/
   * REWRITE_SQL（综合优化改写选中 SQL）。后两者单轮产出、**不执行**。
   */
  action?: 'TEXT_TO_SQL' | 'EXPLAIN' | 'FIX_SQL' | 'REWRITE_SQL';
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
  /**
   * 对话模式（阶段 2）：{@code ask}（默认，只回答不触碰编辑器）/ {@code craft}（可整块改写编辑器）。
   *
   * <p>Craft 需管理员开启 {@code llm.craftModeEnable} 后才下发；未开启时后端一律按 ask 处理——
   * 即「未授权即无能力」，安全性不依赖前端是否隐藏控件。
   */
  mode?: 'ask' | 'craft';
};

/**
 * 拉取指定表的字段（阶段 2 前置：字段级 {@code @表.} 引用）。
 *
 * <p>复用注册中心现成接口 {@code /api/database/listColumns}——<b>不新增后端接口</b>.
 */
export const listTableColumns = async (
  databaseId: number,
  schemaName: string,
  tableName: string
): Promise<any[]> => {
  const res = await queryDataByParams(API_CONSTANTS.DATASOURCE_GET_COLUMNS_BY_TABLE, {
    id: databaseId,
    schemaName,
    tableName
  });
  return (res?.data ?? res ?? []) as any[];
};

/**
 * 简单字符串 hash（djb2）。
 *
 * <p><b>仅用于审计比对</b>（判断改动前后是否为同一内容），不用于任何安全场景。
 */
export const hashText = (text: string): string => {
  let h = 5381;
  for (let i = 0; i < text.length; i++) {
    h = ((h << 5) + h + text.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(16);
};

/**
 * 上报 Craft 写入审计（阶段 2 T2-5）。
 *
 * <p>只上报 hash 与字符数变化，<b>不上传代码正文</b>：既避免审计表膨胀，
 * 也避免作业代码进入后端存储造成额外泄露面。
 *
 * <p>审计是<b>旁路</b>：上报失败静默吞掉，绝不影响用户已经完成的改写。
 */
export const reportCraftWrite = async (params: {
  taskId?: number;
  sessionId?: string;
  before: string;
  after: string;
}): Promise<void> => {
  try {
    await queryDataByParams(API_CONSTANTS.AI_CHAT_WRITE_AUDIT, {
      taskId: params.taskId,
      sessionId: params.sessionId,
      mode: 'craft',
      beforeHash: hashText(params.before),
      afterHash: hashText(params.after),
      chars: params.after.length - params.before.length
    });
  } catch (e) {
    // 旁路失败：不打扰用户，审计缺失可接受，写能力不可用不可接受
  }
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
