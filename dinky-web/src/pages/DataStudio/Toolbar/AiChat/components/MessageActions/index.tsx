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

import { Button, Space, Tooltip } from 'antd';
import React, { ReactNode } from 'react';

/** 一条消息操作（复制 / 编辑 / 删除 / 沉淀…） */
export type MessageActionItem = {
  /** 唯一键（React key 用） */
  key: string;
  icon: ReactNode;
  /** 悬停提示 + 无障碍标签（不只靠图标表意） */
  label: string;
  /** 危险操作（删除）用红色 */
  danger?: boolean;
  disabled?: boolean;
  onClick: () => void;
};

/**
 * 消息操作条（2026-11-04「消息操作」批次）。
 *
 * <p><b>位置</b>：渲染在气泡<b>下方</b>而不是气泡内部右侧。气泡 `maxWidth:92%`，内部又是
 * markdown 表格与代码块，横向塞按钮会挤压正文；下方一行则完全不影响阅读排版。
 *
 * <p><b>显现</b>：默认 `opacity:0`，由 `index.less` 的 `.ai-chat-msg:hover` 控制。
 * 触屏等无 hover 环境在同一个 less 文件里用 `@media (hover: none)` 兜底常显——
 * 只做 hover 的话，平板/远程桌面用户会"点了才知道按钮不存在"。
 *
 * <p><b>为什么不做 Popconfirm</b>：它要求单个可承接事件的子节点，套在 Tooltip 外面时
 * 交互层级容易错乱；需要二次确认的操作用 `Modal.confirm` 在调用方处理，本组件只负责渲染。
 */
const MessageActions: React.FC<{ items: MessageActionItem[] }> = ({ items }) => {
  if (!items || items.length === 0) {
    return null;
  }
  return (
    <div className={'ai-chat-msg-actions'}>
      <Space size={2}>
        {items.map((item) => (
          <Tooltip key={item.key} title={item.label}>
            <Button
              type={'text'}
              size={'small'}
              danger={item.danger}
              disabled={item.disabled}
              icon={item.icon}
              aria-label={item.label}
              onClick={item.onClick}
            />
          </Tooltip>
        ))}
      </Space>
    </div>
  );
};

export default MessageActions;
