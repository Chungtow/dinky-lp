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

export type MentionTokenRange = {
  /** 起点（含）——即 {@code @} 的下标 */
  start: number;
  /** 终点（不含）——token 最后一个字符的下一个位置 */
  end: number;
};

/** 中间层级收尾后的可引用对象（「空格就在这一层收尾」的产物） */
export type FinalizedPathMention = {
  type: 'source' | 'semantic';
  name: string;
};

/**
 * 定位「光标所在的 {@code @} token」区间。
 *
 * <p><b>为什么要按光标而不是按全文末尾</b>（UAT 2026-10-23）：候选选中后会插入
 * {@code @token } （带尾随空格），用户再把光标移回 token 内部补层级时，整串并不在文本末尾；
 * 按末尾解析永远不命中，表现为「补了 {@code /} 也不弹下一级候选」。
 *
 * <p>规则：从光标向左找 {@code @}（且其前必须是行首或空白，避免把邮箱当引用），
 * 一旦先遇到空白即判定光标不在 token 内。
 *
 * @returns 命中返回区间，否则返回 {@code null}
 */
export function findMentionToken(value: string, caret: number): MentionTokenRange | null {
  const limit = Math.max(0, Math.min(caret, value.length));
  let start = -1;
  for (let i = limit - 1; i >= 0; i -= 1) {
    const ch = value[i];
    if (ch === '@') {
      // @ 前必须是行首或空白（与输入框的触发规则一致）
      if (i === 0 || /\s/.test(value[i - 1])) {
        start = i;
      }
      break;
    }
    if (/\s/.test(ch)) {
      return null;
    }
  }
  if (start < 0) {
    return null;
  }
  // 右边界：从光标继续向右到第一个空白或 @——光标停在 token 中间时据此整段替换，
  // 否则会把 token 剩余部分留在后面（如 @source/hive-lpd/ 变成 @source/hive-lpdw/_dev）
  let end = limit;
  while (end < value.length && !/\s/.test(value[end]) && value[end] !== '@') {
    end += 1;
  }
  return { start, end };
}

/**
 * 用 {@code replacement} 替换光标所在的 {@code @} token，并给出替换后的光标位置。
 *
 * <p>光标不在任何 {@code @} token 内时退化为「追加到末尾」——与改造前的
 * {@code value.replace(/@[^\s@]*$/, ...)} 行为一致，保证既有路径零回归。
 */
export function replaceMentionToken(
  value: string,
  caret: number,
  replacement: string
): { value: string; caret: number } {
  const range = findMentionToken(value, caret);
  if (!range) {
    return { value: `${value}${replacement}`, caret: value.length + replacement.length };
  }
  // 替身末尾自带空格时，顺手吃掉 token 后原有的那个空格，避免出现两个连续空格
  // （典型来源：先用空格在某一层收尾，再回到 token 中间继续补层级）
  const end = replacement.endsWith(' ') && value[range.end] === ' ' ? range.end + 1 : range.end;
  return {
    value: value.slice(0, range.start) + replacement + value.slice(end),
    caret: range.start + replacement.length
  };
}

/**
 * 「空格 = 在当前这一层收尾」：把已输入到 {@code 路径/} 的查询收敛成可引用对象。
 *
 * <p>例：{@code source/hive-lpdw/} → {@code {type:'source', name:'hive-lpdw'}}；
 * {@code semantic/traccar_dw/} → {@code {type:'semantic', name:'traccar_dw'}}；
 * {@code source/ds/db/} → {@code {type:'source', name:'ds/db'}}（库级引用，后端本来就支持）。
 *
 * <p>只处理「路径式引用」（source / semantic）——其它前缀（skill/topic/env/table）没有层级，
 * 传进来也一律返回 {@code null}，交由调用方走原有行为。
 */
export function finalizePathMention(query: string): FinalizedPathMention | null {
  const typed = (query ?? '').trim();
  if (!typed.endsWith('/')) {
    return null;
  }
  const path = typed.replace(/\/+$/, '');
  const slashAt = path.indexOf('/');
  if (slashAt <= 0) {
    return null;
  }
  const prefix = path.slice(0, slashAt).toLowerCase();
  const name = path.slice(slashAt + 1);
  if (!name) {
    return null;
  }
  if (prefix !== 'source' && prefix !== 'semantic') {
    return null;
  }
  return { type: prefix, name };
}
