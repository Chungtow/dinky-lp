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

import { Button, Result } from 'antd';
import { Component, ErrorInfo, ReactNode } from 'react';

type Props = { children?: ReactNode };

type State = { error?: Error };

/**
 * 应用级错误边界（2026-10-14 新增）。
 *
 * <p>背景：本项目此前**没有任何 ErrorBoundary**，而 React 18 `createRoot` 下未捕获的渲染 / effect
 * 错误会卸载整棵根树 —— 表现为「某个页面崩溃后，点击其它页面也都报同一个错，必须硬刷新才恢复」
 * （真实案例：`/metrics/job` 因路由未传 props 抛
 * `Cannot read properties of undefined (reading 'isReal')`，连累数据开发、认证中心等所有页面）。
 *
 * <p>用法：在 `app.tsx` 的 `childrenRender` 内包裹页面内容，并以 `key={location.pathname}` 挂载，
 * 使切换路由时边界自动重建 —— 用户从崩溃页切走即可恢复，无需刷新页面。
 */
class ErrorBoundary extends Component<Props, State> {
  public state: State = {};

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 保留控制台可见性，便于定位；后续如需接入前端错误上报可在此扩展
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary] page crashed:', error, info?.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }
    return (
      <Result
        status='error'
        title='页面渲染出错'
        subTitle={error.message}
        extra={
          <Button type='primary' onClick={() => window.location.reload()}>
            刷新本页
          </Button>
        }
      />
    );
  }
}

export default ErrorBoundary;
