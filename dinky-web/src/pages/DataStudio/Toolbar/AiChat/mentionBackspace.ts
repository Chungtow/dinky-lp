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

export type MentionBackspaceArm = {
  /** 紧贴分隔符左侧的 @串（含 {@code @}） */
  token: string;
  /** 删掉分隔符后光标应处的下标（= 本次光标 - 1） */
  end: number;
  /** 埋点时间（毫秒），用于"两下"的时间窗 */
  at: number;
};

/** 判决结果：交回默认逐字符删 / 删分隔符并埋点 / 整块删 */
export type MentionBackspaceDecision =
  | { action: 'plain' }
  | { action: 'separator'; arm: MentionBackspaceArm }
  | { action: 'atomic'; removeStart: number; removeEnd: number };

/** 「两下」的时间窗（毫秒）：超过则视为两次独立退格，不再整块删 */
export const ATOMIC_DELETE_WINDOW_MS = 2000;

/** @串允许的字符（须与后端 {@code PATH_MENTION_PATTERN} / 既有单段 token 保持一致） */
const TOKEN_CHARS = '[A-Za-z0-9_./-]';

/**
 * 分隔符：空白 + 常见中英文标点。
 *
 * <p><b>刻意排除</b> {@code /} {@code .} {@code -}（token 内部字符，见文件头说明），
 * 也不含 {@code _}。
 */
const SEPARATOR_CHARS = '[\\s，。、；：！？,;:!?)\\]}】》]';

/** 光标前是「@串 + 一个分隔符」 */
const TOKEN_BEFORE_SEPARATOR = new RegExp(`(@${TOKEN_CHARS}+)(${SEPARATOR_CHARS})$`);

/**
 * 判决一次 Backspace（纯函数，便于单测与复用）。
 *
 * @param value 输入框当前文本
 * @param start 选区起点（= 终点时即光标位；非折叠选区直接返回 plain）
 * @param end 选区终点
 * @param arm 上一次退格埋下的判据（可能为 null）
 * @param now 当前时间戳（毫秒），便于测试注入
 */
export function decideMentionBackspace(
  value: string,
  start: number,
  end: number,
  arm: MentionBackspaceArm | null,
  now: number = Date.now(),
  windowMs: number = ATOMIC_DELETE_WINDOW_MS
): MentionBackspaceDecision {
  // 有选中内容 / 光标在行首：交给浏览器默认行为（删选区或什么都不做）
  if (start !== end || start <= 0) {
    return { action: 'plain' };
  }
  const before = value.slice(0, start);

  // ① 第二下：紧接第一下、光标仍贴在同一 @串尾部 → 整块删
  if (
    arm &&
    now - arm.at <= windowMs &&
    start === arm.end &&
    before.endsWith(arm.token) &&
    before.length >= arm.token.length
  ) {
    const removeStart = start - arm.token.length;
    return { action: 'atomic', removeStart, removeEnd: end };
  }

  // ② 第一下：光标前是「@串 + 分隔符」→ 只删分隔符，并埋下第二下的判据
  const matched = TOKEN_BEFORE_SEPARATOR.exec(before);
  if (matched) {
    return {
      action: 'separator',
      arm: { token: matched[1], end: start - 1, at: now }
    };
  }

  return { action: 'plain' };
}
