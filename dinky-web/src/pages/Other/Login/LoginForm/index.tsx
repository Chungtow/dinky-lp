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

import { API_CONSTANTS } from '@/services/endpoints';
import { brandAttribution, brandShortName, l } from '@/utils/intl';
import { GithubOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import { DefaultFooter, ProForm, ProFormCheckbox, ProFormText } from '@ant-design/pro-components';
import { SubmitterProps } from '@ant-design/pro-form/es/components';
import { Col, Flex, Row } from 'antd';
import React, { useState } from 'react';
import style from '../../../../global.less';
import Lottie from 'react-lottie';
// 登录页动画：构建期由 config/config.ts 的 alias 解析为"品牌包优先、否则公共层"
import DataPlatform from '@brand-login-animation';
import { useRequest } from '@@/exports';
import { history } from '@umijs/max';
import { GLOBAL_SETTING_KEYS } from '@/types/SettingCenter/data.d';
import { BRAND, brandLink } from '@/branding';

type LoginFormProps = {
  onSubmit: (values: any) => Promise<void>;
};

const LoginForm: React.FC<LoginFormProps> = (props) => {
  const { onSubmit } = props;

  const [form] = ProForm.useForm();

  // 归属声明：品牌包显式配置优先（空串=不展示），未配置则回退 i18n 默认文案
  const attribution = brandAttribution() ?? l('app.copyright.attribution');

  const [submitting, setSubmitting] = useState(false);
  const [ldapEnabled, setLdapEnabled] = useState(false);

  useRequest(API_CONSTANTS.GET_NEEDED_CFG, {
    onSuccess: (res) => {
      if (res[GLOBAL_SETTING_KEYS.SYS_GLOBAL_ISFIRST]) {
        history.push('/welcome');
      }
      setLdapEnabled(res[GLOBAL_SETTING_KEYS.SYS_LDAP_SETTINGS_ENABLE]);
      form.setFieldValue('ldapLogin', res[GLOBAL_SETTING_KEYS.SYS_LDAP_SETTINGS_ENABLE]);
    }
  });

  const handleClickLogin = async () => {
    setSubmitting(true);
    await onSubmit({ ...form.getFieldsValue() });
    setSubmitting(false);
  };

  const renderLoginForm = () => {
    return (
      <>
        <ProFormText
          name='username'
          fieldProps={{
            size: 'large',
            prefix: <UserOutlined />
          }}
          required
          placeholder={l('login.username.placeholder')}
          rules={[
            {
              required: true,
              message: l('login.username.required')
            }
          ]}
        />
        <ProFormText.Password
          name='password'
          fieldProps={{
            size: 'large',
            prefix: <LockOutlined />
          }}
          placeholder={l('login.password.placeholder')}
          rules={[
            {
              required: true,
              message: l('login.password.required')
            }
          ]}
        />
        <Row>
          <Col span={12}>
            <ProFormCheckbox name='autoLogin'>{l('login.rememberMe')}</ProFormCheckbox>
          </Col>
          <Col span={12} style={{ textAlign: 'right' }}>
            <ProFormCheckbox name='ldapLogin' hidden={!ldapEnabled}>
              {l('login.ldapLogin')}
            </ProFormCheckbox>
          </Col>
        </Row>
      </>
    );
  };

  const proFormSubmitter: SubmitterProps = {
    searchConfig: { submitText: l('menu.login') },
    resetButtonProps: false,
    submitButtonProps: {
      loading: submitting,
      autoFocus: true,
      htmlType: 'submit',
      size: 'large',
      shape: 'round',
      style: { width: '100%' }
    }
  };

  return (
    <>
      <Flex
        gap='middle'
        align='center'
        justify={'center'}
        style={{
          width: '100%',
          height: '100%',
          backgroundImage: 'url(./imgs/login_background.jpg)'
        }}
      >
        <Row
          style={{
            width: '90%',
            height: '80%',
            borderRadius: 15,
            overflow: 'hidden',
            boxShadow: '0px 0px 100px  #78909c'
          }}
        >
          <Col
            style={{
              padding: '5%',
              backgroundColor: '#fff',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'center'
            }}
            xs={24}
            sm={24}
            md={24}
            lg={24}
            xl={14}
            xxl={8}
          >
            <Row>
              <Col xl={12} xxl={24}>
                <Row
                  style={{ color: '#00b0ff', marginBottom: 60, justifyContent: 'center' }}
                  align={'middle'}
                >
                  {/* logo 走品牌元数据（支持 .svg/.png），避免与页头不一致 */}
                  <img src={BRAND.logo || './brand-logo.svg'} width={150} alt={''} />
                  <h1 style={{ margin: '0' }}>{l('layouts.userLayout.title')}</h1>
                </Row>
              </Col>

              <Col xl={12} xxl={24}>
                <ProForm
                  className={style.loginform}
                  form={form}
                  onFinish={handleClickLogin}
                  initialValues={{ autoLogin: true }}
                  submitter={{ ...proFormSubmitter }}
                >
                  {renderLoginForm()}
                </ProForm>
              </Col>
            </Row>
            <DefaultFooter
              copyright={
                `${new Date().getFullYear()} ` +
                l('app.copyright.produced') +
                (attribution ? ` · ${attribution}` : '')
              }
              style={{ backgroundColor: '#fff' }}
              links={
                [
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
                ].filter(Boolean) as any
              }
            />
          </Col>
          <Col
            xs={0}
            sm={0}
            md={0}
            lg={0}
            xl={10}
            xxl={16}
            style={{
              backgroundImage: 'linear-gradient(135deg,#1fa2ff,#12d8fa,#a6ffcb)',
              width: '100%',
              height: '100%'
            }}
          >
            <Flex align={'center'} style={{ width: '100%', height: '100%' }}>
              <Lottie
                options={{
                  loop: true,
                  autoplay: true,
                  animationData: DataPlatform,
                  rendererSettings: {
                    preserveAspectRatio: 'xMidYMid slice'
                  }
                }}
                height={'60%'}
                width={'70%'}
                speed={0.5}
                isClickToPauseDisabled
              />
            </Flex>
          </Col>
        </Row>
      </Flex>
      <img
        src={'./icons/footer-bg.svg'}
        width={'100%'}
        alt={''}
        style={{ position: 'absolute', bottom: 0 }}
      />
    </>
  );
};

export default LoginForm;
