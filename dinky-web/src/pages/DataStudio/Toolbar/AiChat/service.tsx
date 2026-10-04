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
  /** 阶段 2c-1：写类工具（exec_sql）回报的受影响行数（只读工具无此值） */
  affectedRows?: number;
  /** 阶段 2c-1：写类工具的风险摘要（审计用，可选展示） */
  riskSummary?: string;
  /** 阶段 2c-2：这是第几次自动纠错尝试（0 / 无值表示不适用） */
  attempt?: number;
  /** 阶段 2c-2：是否已达自动纠错上限（到达后已停止重试） */
  repairExhausted?: boolean;
};

/**
 * 变更风险信息（阶段 2c-1）。
 *
 * <p><b>注意</b>：{@code modelEstimate} 是 AI 自报的估计值，<b>不是</b>精确值，展示时必须标注「AI 估计」；
 * 真实受影响行数在工具执行结果（{@code AiChatToolStep.affectedRows}）里回报。
 */
export type AiChatChangeRisk = {
  /** 语句类型：DML / DDL */
  sqlType?: string;
  /** 是否为结构变更（DDL） */
  ddl?: boolean;
  /** 目标对象（表名，后端粗解析；可能为空） */
  target?: string;
  /** AI 自报的影响范围（估计值） */
  modelEstimate?: string;
};

/**
 * 写语句二次确认请求（阶段 2c-0）。
 *
 * <p>后端在 AI 生成 DML / DDL 且管理员已开放该类语句时下发；前端弹确认框，用户拍板后经
 * {@code /api/aiChat/confirm} 回传。**未确认则不执行**。
 */
export type AiChatConfirmFrame = {
  runId: string;
  /** 待执行的写语句原文 */
  sql: string;
  /** 语句类型：DML / DDL */
  sqlType: string;
  /** 确认等待超时（秒），超时按拒绝 */
  timeoutSeconds?: number;
  /** 阶段 2c-1：变更风险信息（语句类型 / 是否 DDL / 目标对象 / AI 估计影响） */
  risk?: AiChatChangeRisk;
  /** 阶段 2c-2：这是第几次尝试（自动纠错重试时 >1） */
  attempt?: number;
  /** 阶段 2c-2：上一次失败的原因（重试时展示，帮助用户判断是否还执行） */
  previousError?: string;
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

/**
 * LLM 实例（阶段 3：多 LLM 实例配置）。
 *
 * <p>后端逐项**脱敏**后下发：只含 {@code hasApiKey} 布尔，**不含 apiKey 明文**。
 */
export type AiChatProfileItem = {
  id: string;
  name?: string;
  model?: string;
  baseUrl?: string;
  hasApiKey?: boolean;
  /** 该实例是否支持工具调用；false 时提示「将退化为纯问答」 */
  supportsTools?: boolean;
};

/** 前端可用的 AI 配置（不含密钥） */
export type AiChatConfig = {
  enable?: boolean;
  model?: string;
  baseUrl?: string;
  hasApiKey?: boolean;
  /** Craft 模式是否开启（管理员配置）；未开启时前端不渲染模式切换控件 */
  craftModeEnable?: boolean;
  /** 默认 LLM 实例 id（阶段 3）：未选择时使用它 */
  defaultProfileId?: string;
  /** 可用的 LLM 实例列表（阶段 3，不含密钥） */
  profiles?: AiChatProfileItem[];
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
  /**
   * LLM 实例 id（阶段 3）：缺省 / 传不存在的值 → 后端回落默认实例。
   *
   * <p>不传时行为与改造前完全一致（评测脚本即不传）。
   */
  profileId?: string;
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
 * 回传写语句二次确认结果（阶段 2c-0）。
 *
 * <p>与对话同为 POST + JSON；返回 HTTP 是否成功即可——runId 失效等业务失败由后端按「拒绝/超时」处理。
 */
export const confirmAiChat = async (runId: string, approve: boolean): Promise<boolean> => {
  try {
    const res = await fetch(API_CONSTANTS.AI_CHAT_CONFIRM, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ runId, approve })
    });
    return res.ok;
  } catch (e) {
    return false;
  }
};

/** 请求中断本次运行（阶段 2c-0）：不再只断开 SSE HTTP 流，而是让服务端循环真正收到取消信号 */
export const cancelAiChat = async (runId: string): Promise<boolean> => {
  try {
    const res = await fetch(API_CONSTANTS.AI_CHAT_CANCEL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ runId })
    });
    return res.ok;
  } catch (e) {
    return false;
  }
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
    /** 阶段 2c-0：本次运行的 id（对话开始时下发） */
    runId?: string;
    /** 阶段 2c-0：写语句执行前的二次确认请求 */
    confirmRequest?: AiChatConfirmFrame;
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
        // 阶段 2c-0：运行 id 与写语句二次确认请求
        if (typeof frame?.runId === 'string' && frame.runId.length > 0) {
          onFrame({ runId: frame.runId });
        }
        if (frame?.confirmRequest && typeof frame.confirmRequest === 'object') {
          onFrame({ confirmRequest: frame.confirmRequest as AiChatConfirmFrame });
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
