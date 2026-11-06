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

import { CloseOutlined } from '@ant-design/icons';
import React from 'react';

/**
 * 结果标签（结果-N）：关闭图标默认透明，悬停标签时显形。
 * 用自绘关闭图标而不是 `type='editable-card'`，避免整条标签栏变成卡片风格（视觉噪声大）。
 */
const ResultTabLabel: React.FC<{ label: string; onClose: () => void }> = ({ label, onClose }) => (
  <span>
    {label}
    <CloseOutlined
      className={'service-result-tab-close'}
      onClick={(e) => {
        e.stopPropagation();
        e.preventDefault();
        onClose();
      }}
    />
  </span>
);

export default ResultTabLabel;
