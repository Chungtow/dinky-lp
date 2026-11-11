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

import { brandAttribution, brandShortName, l } from '@/utils/intl';
import { brandLink } from '@/branding';
import { GithubOutlined } from '@ant-design/icons';
import { DefaultFooter } from '@ant-design/pro-components';
import React from 'react';
const Footer: React.FC = () => {
  const currentYear = new Date().getFullYear();
  // 归属声明：品牌包显式配置优先（空串=不展示），未配置则回退 i18n 默认文案
  const attribution = brandAttribution() ?? l('app.copyright.attribution');
  // 外链按品牌配置动态渲染：brand/<id>.json 的 links 留空即不展示，避免死链
  const links = [
    brandLink('website') && {
      key: 'website',
      title: brandShortName(),
      href: brandLink('website'),
      blankTarget: true
    },
    brandLink('github') && {
      key: 'github',
      title: <GithubOutlined />,
      href: brandLink('github'),
      blankTarget: true
    }
  ].filter(Boolean) as any;

  return (
    <DefaultFooter
      style={{
        background: 'url(./icons/footer-bg.svg) 100% 100% no-repeat',
        backgroundSize: 'cover',
        bottom: 0
      }}
      copyright={
        `${currentYear} ` + l('app.copyright.produced') + (attribution ? ` · ${attribution}` : '')
      }
      links={links}
    />
  );
};

export default Footer;
