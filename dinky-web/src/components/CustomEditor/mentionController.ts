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

import type * as Monaco from 'monaco-editor';
import { findMentionToken } from '@/pages/DataStudio/Toolbar/AiChat/mentionPath';
import {
  collectMentionItems,
  insideFencedCode,
  lastMentionDiagnostic,
  primeMentionCandidates
} from '@/components/CustomEditor/mentionCompletion';
import type { MentionItem } from '@/components/CustomEditor/mentionCompletion';

/**
 * Skill 编辑器里的 `@` 候选面板——**照搬 AI Chat 的做法，不用 Monaco 的 suggest widget**。
 *
 * <h3>为什么不复用 Monaco 的补全组件（UAT 2026-10-30 连续四轮的结论）</h3>
 * <p>AI Chat 之所以"丝滑"，是因为浮层的**开合、定位、键盘、插入全由它自己管**
 * （`AiChat/index.tsx`：`handleInputChange` 里判 `findMentionToken` → `setMentionOpen(true)`；
 * `handleInputKeyDown` 接管 ↑↓/Enter/Tab/Esc；`pickMention` 用 `replaceMentionToken` 回写）。
 * 而 Monaco 的 `registerCompletionItemProvider` 把这四件事全交给了它的 suggest widget：
 * <ul>
 *   <li>开合受 `quickSuggestions` / `suggestOnTriggerCharacters` 两个编辑器选项钳制
 *       （本项目的 `CodeEdit` 默认把前者关掉 → 敲 `@` 根本没反应）；</li>
 *   <li>列表受 Monaco 的"当前单词"过滤，`@` 不是单词字符，行为随版本漂移；</li>
 *   <li>只要 provider 的 Promise 不 settle，浮层就永远停在 "Loading…"（无超时无兜底）；</li>
 *   <li>空列表直接显示 "No suggestions."，业务侧既看不到原因也插不了提示。</li>
 * </ul>
 * <p>结论：**能复用的是 AI Chat 那套状态机，不是 Monaco 的 widget**。这里用 Monaco 的
 * `addContentWidget` 把面板锚在光标下方，开合/键盘/插入自己实现，数据仍复用
 * `mentionCompletion.collectMentionItems`（与 AI Chat 同源）。
 */

export type MentionControllerOptions = {
  /** 只有该语言的模型才启用（skill 正文 = markdown） */
  languageId: string;
};

const PANEL_MAX_HEIGHT = 220;

const PANEL_ATTR = 'data-skill-mention-panel';

/** 面板条目：候选 或 "无匹配/说明" 占位行 */
type Row = { item: MentionItem } | { placeholder: string };

/**
 * 已挂过面板的编辑器实例。
 *
 * <p><b>为什么用 WeakSet 且 dispose 是空操作</b>：中区 tab 用 react-activation 的 KeepAlive，
 * 关/切 tab 只是让组件失活或卸载，编辑器实例与 DOM 仍在缓存里；如果这里真 dispose，
 * 下次回到该 tab 时 `onMount` 不一定重新触发 → 面板永久消失（UAT 2026-10-30 的教训）。
 * 面板本身无副作用（不开就不监听键盘），所以让它随编辑器实例存续即可。
 */
const attachedEditors = new WeakSet<object>();

/**
 * 给编辑器挂上 `@` 候选面板。
 *
 * @returns disposable：调用方在卸载时 dispose（内部会移除 content widget 与所有监听）
 */
export function attachMentionController(
  monaco: typeof Monaco,
  editor: Monaco.editor.IStandaloneCodeEditor,
  options: MentionControllerOptions
): Monaco.IDisposable {
  if (attachedEditors.has(editor)) {
    return { dispose: () => undefined };
  }
  attachedEditors.add(editor);
  const dom = document.createElement('div');
  dom.setAttribute(PANEL_ATTR, '1');
  Object.assign(dom.style, {
    display: 'none',
    maxHeight: `${PANEL_MAX_HEIGHT}px`,
    minWidth: '260px',
    overflowY: 'auto',
    background: 'var(--primary-color, #fff)',
    border: '1px solid rgba(0,0,0,0.12)',
    borderRadius: '4px',
    boxShadow: '0 4px 12px rgba(0,0,0,0.16)',
    padding: '2px 0',
    fontFamily: 'inherit',
    zIndex: '30',
    // Monaco 的 content widget 容器默认不吃鼠标事件，这里显式声明可交互
    pointerEvents: 'auto'
  } satisfies Partial<CSSStyleDeclaration>);

  /**
   * 面板内的鼠标处理（容器级，捕获阶段）。
   *
   * <ul>
   *   <li>{@code mousedown} 必须 preventDefault：否则焦点离开编辑器会触发 blur → 面板被收起，
   *       点击就"点不中"（UAT 2026-10-30 反馈②）；</li>
   *   <li>{@code wheel} 必须 stopPropagation：否则滚轮会被 Monaco 拿去滚编辑器，
   *       候选多时面板自己滚不动（反馈③）。</li>
   * </ul>
   */
  dom.addEventListener(
    'mousedown',
    (event) => {
      // 点行才阻止默认行为（防失焦）；点在面板空白/滚动条上必须放行，
      // 否则拖动滚动条会被 preventDefault 吃掉（反馈③的另一半）
      if (event.target !== dom) {
        event.preventDefault();
      }
    },
    true
  );
  dom.addEventListener(
    'wheel',
    (event) => {
      event.stopPropagation();
    },
    { capture: true, passive: true }
  );

  const widget: Monaco.editor.IContentWidget = {
    getId: () => 'skill-mention-widget',
    getDomNode: () => dom,
    getPosition: () => ({
      position: anchorRef,
      preference: [
        monaco.editor.ContentWidgetPositionPreference.BELOW,
        monaco.editor.ContentWidgetPositionPreference.ABOVE
      ]
    })
  };

  /** 面板锚点（光标所在 token 的起始位置） */
  let anchorRef: Monaco.IPosition = { lineNumber: 1, column: 1 };

  /** 当前 token 的替换区间（1-based，覆盖 `@` 到 token 末尾） */
  let replaceRange: Monaco.Range | null = null;

  let rows: Row[] = [];

  let activeIndex = 0;

  let isOpen = false;

  /** 请求序号：异步结果回来时用它丢弃过期结果（用户已经接着敲了） */
  let seq = 0;

  /** 程序化插入期间屏蔽"内容变化→重新解析"的回环 */
  let suppress = false;

  /** 行元素缓存：高亮切换/滚动定位都靠它，避免重建 DOM（重建会让鼠标点击落空） */
  let rowEls: HTMLDivElement[] = [];

  /** 只更新高亮 + 把选中行滚进可视区，不重建 DOM */
  const updateActive = () => {
    rowEls.forEach((el, index) => {
      el.style.background = index === activeIndex ? 'rgba(22,119,255,0.10)' : 'transparent';
    });
    const active = rowEls[activeIndex];
    if (active) {
      // 键盘移出可视区时把面板自己滚过去。
      // 用 rect 差值而不是 offsetTop：content widget 的定位由 Monaco 接管，offsetParent 不可靠；
      // 也不用 scrollIntoView，避免连带滚动编辑器或整页。
      const domRect = dom.getBoundingClientRect();
      const rect = active.getBoundingClientRect();
      if (rect.top < domRect.top) {
        dom.scrollTop -= domRect.top - rect.top;
      } else if (rect.bottom > domRect.bottom) {
        dom.scrollTop += rect.bottom - domRect.bottom;
      }
    }
  };

  const render = () => {
    dom.innerHTML = '';
    rowEls = rows.map((row, index) => {
      const line = document.createElement('div');
      Object.assign(line.style, {
        padding: '5px 8px',
        fontSize: '12px',
        display: 'flex',
        justifyContent: 'space-between',
        gap: '8px',
        cursor: 'pointer',
        background: index === activeIndex ? 'rgba(22,119,255,0.10)' : 'transparent'
      } satisfies Partial<CSSStyleDeclaration>);
      if ('placeholder' in row) {
        line.style.cursor = 'default';
        line.style.color = 'rgba(0,0,0,0.45)';
        line.textContent = row.placeholder;
      } else {
        const left = document.createElement('span');
        left.textContent = row.item.title;
        Object.assign(left.style, {
          maxWidth: '48%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap'
        } satisfies Partial<CSSStyleDeclaration>);
        const right = document.createElement('span');
        right.textContent = row.item.right ?? row.item.type;
        Object.assign(right.style, {
          color: 'rgba(0,0,0,0.45)',
          fontSize: '11px',
          flexShrink: '0',
          maxWidth: '50%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap'
        } satisfies Partial<CSSStyleDeclaration>);
        line.appendChild(left);
        line.appendChild(right);
        // 用 mousedown 而不是 click：先于编辑器失焦触发，面板不会被提前关掉
        line.addEventListener('mousedown', (event) => {
          event.preventDefault();
          pick(row.item);
        });
        line.addEventListener('mouseenter', () => {
          activeIndex = index;
          updateActive();
        });
      }
      dom.appendChild(line);
      return line;
    });
  };

  const show = () => {
    if (!isOpen) {
      isOpen = true;
      editor.addContentWidget(widget);
    }
    dom.style.display = 'block';
    editor.layoutContentWidget(widget);
  };

  const hide = () => {
    // 作废在途请求：用户已经关掉面板（Esc / 失焦 / 选完叶子），迟到的结果不该再把面板弹回来
    seq += 1;
    if (!isOpen) {
      return;
    }
    isOpen = false;
    dom.style.display = 'none';
    editor.removeContentWidget(widget);
    rows = [];
    activeIndex = 0;
    replaceRange = null;
  };

  /** 取当前光标所在的 `@` token（含"紧跟在字母后面"的手打场景） */
  const currentToken = (model: Monaco.editor.ITextModel, position: Monaco.IPosition) => {
    const line = model.getLineContent(position.lineNumber);
    const caret = Math.min(position.column - 1, line.length);
    let range = findMentionToken(line, caret);
    if (!range && line[caret - 1] === '@') {
      range = { start: caret - 1, end: caret };
    }
    if (!range) {
      return null;
    }
    const raw = line.slice(range.start, range.end).replace(/`+$/, '');
    if (!raw.startsWith('@')) {
      return null;
    }
    return {
      line,
      query: raw.slice(1),
      // 替换区间不含末尾反引号（已剥掉），避免把闭合反引号一起吃掉
      start: range.start,
      end: range.start + raw.length
    };
  };

  /** 重新解析当前 token 并刷新候选 */
  const refresh = async () => {
    const model = editor.getModel();
    const position = editor.getPosition();
    if (!model || !position) {
      hide();
      return;
    }
    if (options.languageId && model.getLanguageId() !== options.languageId && model.getLanguageId() !== 'plaintext') {
      hide();
      return;
    }
    if (insideFencedCode(model, position)) {
      hide();
      return;
    }
    const token = currentToken(model, position);
    if (!token) {
      hide();
      return;
    }
    const mySeq = (seq += 1);
    const items = await collectMentionItems(token.query);
    if (mySeq !== seq || !editor.getModel()) {
      return; // 过期结果
    }
    replaceRange = new monaco.Range(
      position.lineNumber,
      token.start + 1,
      position.lineNumber,
      token.end + 1
    );
    anchorRef = { lineNumber: position.lineNumber, column: token.start + 1 };
    rows =
      items.length > 0
        ? items.map((item) => ({ item }))
        : [{ placeholder: `无匹配：${lastMentionDiagnostic || `「${token.query}」没有命中的资源`}` }];
    activeIndex = items.length > 0 ? 0 : -1;
    render();
    // 新一轮候选从顶部开始看（否则沿用上一轮滚动位置，用户会以为列表"卡住了"）
    dom.scrollTop = 0;
    show();
  };

  /** 选中一条候选：中间层级补 `/` 并继续，叶子整体插入（含反引号） */
  const pick = (item: MentionItem) => {
    const model = editor.getModel();
    if (!model || !replaceRange) {
      return;
    }
    const lineNumber = replaceRange.startLineNumber;
    const line = model.getLineContent(lineNumber);
    const before = line[replaceRange.startColumn - 2];
    const after = line[replaceRange.startColumn - 1 + (replaceRange.endColumn - replaceRange.startColumn)];

    // prefix 项（`@source/` 这类类型入口）的 code 就是类型名，不能再拼一次，
    // 否则会出现 `@source/source/`（UAT 2026-10-30 反馈①）
    const body = item.kind === 'prefix' ? `@${item.type}/` : `@${item.type}/${item.code}`;
    let text: string;
    if (item.kind === 'prefix') {
      text = body;
    } else if (item.chainable) {
      // 中间层级：补斜杠继续下钻，不加反引号（否则闭合反引号会打断后续输入）
      text = `${body}/`;
    } else {
      text = `${before === '`' ? '' : '`'}${body}${after === '`' ? '' : '`'}`;
    }

    suppress = true;
    editor.executeEdits('skill-mention', [
      {
        range: replaceRange,
        text,
        forceMoveMarkers: true
      }
    ]);
    const column = replaceRange.startColumn + text.length;
    editor.setPosition({ lineNumber, column });
    editor.revealPositionInCenterIfOutsideViewport({ lineNumber, column });
    suppress = false;

    if (item.chainable || item.kind === 'prefix') {
      // 选中即进下一级：光标已在 `路径/` 之后，直接刷新候选
      void refresh();
    } else {
      hide();
      editor.focus();
    }
  };

  primeMentionCandidates();

  const disposables: Monaco.IDisposable[] = [
    editor.onDidChangeModelContent(() => {
      if (suppress) {
        return;
      }
      void refresh();
    }),
    editor.onDidChangeCursorPosition(() => {
      // suppress 期间是程序化插入后的定位，不要触发重新解析（否则刚选完叶子又会被弹开）
      if (isOpen && !suppress) {
        void refresh();
      }
    }),
    editor.onKeyDown((event) => {
      if (!isOpen) {
        return;
      }
      const { keyCode } = event;
      if (keyCode === monaco.KeyCode.DownArrow) {
        if (rows.length > 0) {
          activeIndex = (activeIndex + 1) % rows.length;
          updateActive();
        }
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      if (keyCode === monaco.KeyCode.UpArrow) {
        if (rows.length > 0) {
          activeIndex = (activeIndex - 1 + rows.length) % rows.length;
          updateActive();
        }
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      if (keyCode === monaco.KeyCode.Enter || keyCode === monaco.KeyCode.Tab) {
        const row = rows[activeIndex];
        if (row && 'item' in row) {
          event.preventDefault();
          event.stopPropagation();
          pick(row.item);
        }
        return;
      }
      if (keyCode === monaco.KeyCode.Escape) {
        event.preventDefault();
        event.stopPropagation();
        hide();
      }
    }),
    editor.onDidBlurEditorWidget(() => {
      // 失焦即收起：面板是辅助输入，不跟随用户跑
      hide();
    })
  ];

  return {
    // 见 attachedEditors 的注释：这里刻意不做清理，面板随编辑器实例存续
    dispose: () => {
      hide();
      void disposables;
    }
  };
}
