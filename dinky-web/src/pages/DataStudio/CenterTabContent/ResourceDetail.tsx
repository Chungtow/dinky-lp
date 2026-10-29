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

import CodeShow from '@/components/CustomEditor/CodeShow';
import { CenterTab } from '@/pages/DataStudio/model';
import { renderLanguage, unSupportView } from '@/utils/function';
import { l } from '@/utils/intl';
import { Empty, Typography } from 'antd';

const { Text } = Typography;

/**
 * zh: 资源文件只读展示（数据开发中央编辑区 tab）
 * en: Resource file readonly viewer (DataStudio center tab)
 */
const ResourceDetail = (props: CenterTab) => {
  const { params } = props;
  const { name, content } = params;
  const height = 'calc(100vh - 165px)';

  if (name && unSupportView(name)) {
    return (
      <Empty
        style={{ alignItems: 'center', justifyContent: 'center', top: '50%', height: '100%' }}
        imageStyle={{
          marginTop: '20vh'
        }}
        description={l('rc.gp.codeTree.unSupportView')}
      />
    );
  }
  if (content === '' || content === null || content === undefined) {
    return (
      <Empty
        style={{ alignItems: 'center', justifyContent: 'center', top: '50%', height: '100%' }}
        imageStyle={{
          marginTop: '20vh'
        }}
        description={
          <Text type='success' strong>
            {l('rc.resource.click')}
          </Text>
        }
      />
    );
  }
  return (
    <div style={{ height, width: '100%', padding: '4px 8px' }}>
      <CodeShow
        code={content}
        language={renderLanguage(name, '.')}
        height={height}
        width='100%'
        lineNumbers='on'
        showFloatButton={true}
      />
    </div>
  );
};

export default ResourceDetail;
