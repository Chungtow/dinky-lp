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

import { handleCopyToClipboard } from '@/utils/function';
import { l } from '@/utils/intl';
import { CopyOutlined } from '@ant-design/icons';
import { Button, Empty, Space } from 'antd';
import React from 'react';

type SourceProps = {
  /** mermaid erDiagram 源码 */
  code: string;
};

/**
 * ER 图源代码视图：展示原始 mermaid 源码，支持一键复制（便于粘贴到文档 / AI 工具）。
 */
const Source: React.FC<SourceProps> = (props) => {
  const { code } = props;

  if (!code) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={l('rc.ds.erdiagram.empty')} />;
  }

  return (
    <Space direction='vertical' style={{ width: '100%' }} size={8}>
      <Button
        size='small'
        icon={<CopyOutlined />}
        disabled={!code}
        onClick={() => handleCopyToClipboard(code)}
      >
        {l('rc.ds.erdiagram.copy')}
      </Button>
      <pre
        style={{
          margin: 0,
          padding: 12,
          background: 'rgba(0, 0, 0, 0.03)',
          borderRadius: 4,
          maxHeight: '62vh',
          overflow: 'auto',
          whiteSpace: 'pre'
        }}
      >
        <code>{code}</code>
      </pre>
    </Space>
  );
};

export default Source;
