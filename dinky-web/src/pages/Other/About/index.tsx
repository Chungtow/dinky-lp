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

import { BRAND, brandCommunity, brandLink, brandTheme, withAlpha } from '@/branding';
import { API_CONSTANTS } from '@/services/endpoints';
import { SERVER_VERSION } from '@/services/constants';
import { getValueFromLocalStorage } from '@/utils/function';
import { brandAttribution, currentLocale, l } from '@/utils/intl';
import {
  ArrowLeftOutlined,
  CustomerServiceOutlined,
  GithubOutlined,
  GlobalOutlined,
  ReadOutlined
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { SelectLang, history } from '@umijs/max';
import { Button, Descriptions, Empty, Image, Space, Tag, Typography, theme } from 'antd';
import React from 'react';

const { Text, Title } = Typography;

/**
 * 技术支持二维码渠道 -> 文案 key。
 * 按文件名前缀匹配（品牌包 `community/` 下的文件），未列出的渠道用文件名兜底展示；
 * 品牌包没有提供的渠道不会出现在清单里（见 config.ts 的 __BRAND_COMMUNITY__），因此不会出现空图。
 */
const COMMUNITY_CHANNELS: Record<string, string> = {
  wechat: 'about.techSupport.wechat',
  dingtalk: 'about.techSupport.dingtalk',
  qq: 'about.techSupport.qq'
};

const channelLabel = (file: string): string => {
  const lower = file.toLowerCase();
  const hit = Object.keys(COMMUNITY_CHANNELS).find((key) => lower.startsWith(key));
  return hit ? l(COMMUNITY_CHANNELS[hit]) : file.replace(/\.[^.]+$/, '');
};

const isZh = (): boolean => currentLocale().toLowerCase().startsWith('zh');

/**
 * 产品信息页（白标）。
 *
 * 只展示品牌自述信息：产品名与版本、开源许可、版权与归属声明、外链、技术支持二维码。
 * 所有内容均取自品牌包（`brand/<id>.json` 与 `public/brand/<id>/community/`），
 * 不含任何上游社区导流内容；未配置的项一律不渲染。
 *
 * 该页免登录可见（路由 `layout: false`，且在 app.tsx 免登录白名单内）——
 * 主入口是登录页页脚，未登录访问者拿不到当前用户，故版本号取不到时整行不渲染。
 */
const About: React.FC = () => {
  const { token } = theme.useToken();
  const zh = isZh();
  const displayName = zh ? BRAND.name : BRAND.nameEn || BRAND.name;
  const copyright = zh ? BRAND.copyright : BRAND.copyrightEn || BRAND.copyright;
  const attribution = brandAttribution() ?? l('app.copyright.attribution');
  const storedVersion = getValueFromLocalStorage(SERVER_VERSION);
  const version = storedVersion && storedVersion !== '-' ? storedVersion : '';
  const community = brandCommunity();

  const links = [
    {
      key: 'website',
      icon: <GlobalOutlined />,
      label: l('about.link.website'),
      href: brandLink('website')
    },
    { key: 'docs', icon: <ReadOutlined />, label: l('about.link.docs'), href: brandLink('docs') },
    { key: 'github', icon: <GithubOutlined />, label: 'GitHub', href: brandLink('github') }
  ].filter((item) => !!item.href);

  /** 返回：优先回上一页（登录页页脚进来就是登录页），无历史时回登录页，避免退无可退 */
  const goBack = () => {
    if (window.history.length > 1) {
      history.back();
    } else {
      history.push(API_CONSTANTS.LOGIN_PATH);
    }
  };

  return (
    <div style={{ minHeight: '100vh', background: token.colorBgLayout, padding: '16px 16px 40px' }}>
      <div style={{ maxWidth: 1040, margin: '0 auto' }}>
        {/* 顶栏：品牌标识 + 返回 / 语言切换（本页不挂 ProLayout，故自带） */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            flexWrap: 'wrap',
            padding: '4px 0 16px'
          }}
        >
          <Space align='center' size={14} wrap>
            {BRAND.logo && <img src={`./${BRAND.logo}`} alt={displayName} style={{ height: 40 }} />}
            <Title level={4} style={{ margin: 0 }}>
              {displayName}
              {attribution && (
                // 归属声明用品牌浅底而不是 antd 的蓝色 Tag：蓝标签配绿品牌是最典型的"拼色"来源
                <Tag
                  style={{
                    marginLeft: 12,
                    verticalAlign: 'middle',
                    color: brandTheme.primary,
                    background: brandTheme.tint || undefined,
                    borderColor: brandTheme.tint ? withAlpha(brandTheme.primary, 0.25) : undefined
                  }}
                >
                  {attribution}
                </Tag>
              )}
            </Title>
          </Space>
          <Space size={4}>
            <SelectLang icon={<GlobalOutlined />} />
            <Button type='text' icon={<ArrowLeftOutlined />} onClick={goBack}>
              {l('about.back')}
            </Button>
          </Space>
        </div>

        <PageContainer title={false} style={{ padding: 0 }}>
          <ProCard gutter={[16, 16]} wrap ghost>
            {/* 产品与许可 */}
            <ProCard colSpan={{ xs: 24, md: 12 }} title={l('about.product')}>
              <Descriptions column={1} size='small' colon={false}>
                {version && (
                  <Descriptions.Item label={l('about.version')}>{version}</Descriptions.Item>
                )}
                <Descriptions.Item label={l('about.license')}>
                  <a
                    href='https://www.apache.org/licenses/LICENSE-2.0'
                    target='_blank'
                    rel='noreferrer'
                  >
                    Apache License 2.0
                  </a>
                </Descriptions.Item>
                <Descriptions.Item label={l('about.copyright')}>{copyright}</Descriptions.Item>
              </Descriptions>

              {links.length > 0 && (
                <>
                  <Title level={5} style={{ marginTop: 20 }}>
                    {l('about.links')}
                  </Title>
                  <Space direction='vertical' size={6}>
                    {links.map((item) => (
                      <a key={item.key} href={item.href} target='_blank' rel='noreferrer'>
                        {item.icon} {item.label}
                      </a>
                    ))}
                  </Space>
                </>
              )}
            </ProCard>

            {/* 技术支持 */}
            <ProCard
              colSpan={{ xs: 24, md: 12 }}
              title={
                <Space size={6}>
                  <CustomerServiceOutlined />
                  {l('about.techSupport')}
                </Space>
              }
            >
              {community.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={l('about.techSupport.empty')}
                />
              ) : (
                <Space size={28} wrap align='start'>
                  {community.map((file) => (
                    <div key={file} style={{ textAlign: 'center' }}>
                      <Image src={`./community/${file}`} width={160} alt={channelLabel(file)} />
                      <div style={{ marginTop: 8 }}>
                        <Text strong>{channelLabel(file)}</Text>
                      </div>
                    </div>
                  ))}
                </Space>
              )}
            </ProCard>
          </ProCard>
        </PageContainer>
      </div>
    </div>
  );
};

export default About;
