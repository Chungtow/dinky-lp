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

import { ReportData } from '@/pages/Dashboard/Report/data';
import { getReportDetail, getReportPreviewUrl } from '@/pages/Dashboard/Report/service';
import { l } from '@/utils/intl';
import {
  ArrowLeftOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { getUrlParam } from '@/utils/function';
import { history, useLocation } from '@@/exports';
import { Alert, Button, Result, Space, Spin, Typography } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import './index.less';

/**
 * 报表查看页
 *
 * <p>渲染链路：`/api/report/previewUrl` 换短期签名地址 → iframe 加载 `/api/report/preview`。
 * 之所以不直接 `src=/api/report/preview?id=`：沙箱 iframe 的 Cookie 行为跨浏览器不一致，
 * 签名地址可保证「免登录预览」稳定，同时不把完全免登录的端点暴露出去。
 *
 * <p>全屏是**页内遮盖**（`position: fixed; inset: 0`），不碰浏览器全屏 API，
 * 避免与 DataStudio 结果区全屏语义串扰。
 */
const ReportView: React.FC = () => {
  const location = useLocation();
  const id = Number(getUrlParam(location.search, 'id'));

  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<ReportData>();
  const [previewUrl, setPreviewUrl] = useState<string>();
  const [error, setError] = useState<string>();
  const [immersive, setImmersive] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  const queryDetail = useCallback(async () => {
    if (!id) {
      setError(l('dashboard.report.invalidId'));
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const detail = await getReportDetail(id);
      if (!detail?.data) {
        setError(l('dashboard.report.noPermission'));
        return;
      }
      setReport(detail.data as ReportData);
      const url = await getReportPreviewUrl(id);
      if (!url?.data) {
        setError(l('dashboard.report.previewFailed'));
        return;
      }
      setPreviewUrl(url.data as string);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    queryDetail();
  }, [queryDetail]);

  // 沉浸态：锁 body 滚动 + Esc 退出；组件卸载/切换路由时复原，避免「整站不能滚」残留
  useEffect(() => {
    if (!immersive) {
      return undefined;
    }
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setImmersive(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [immersive]);

  if (loading) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (error) {
    return (
      <Result
        status='warning'
        title={error}
        extra={[
          <Button key='back' onClick={() => history.push('/dashboard/report')}>
            {l('dashboard.report.back')}
          </Button>,
          <Button key='retry' type='primary' onClick={queryDetail}>
            {l('dashboard.report.retry')}
          </Button>
        ]}
      />
    );
  }

  return (
    <div className={immersive ? 'report-immersive' : 'report-normal'}>
      {!immersive && (
        <div className='report-toolbar'>
          <Space>
            <Button
              type='text'
              icon={<ArrowLeftOutlined />}
              onClick={() => history.push('/dashboard/report')}
            >
              {l('dashboard.report.back')}
            </Button>
            <Typography.Text strong>{report?.name}</Typography.Text>
            {report?.remark ? (
              <Typography.Text type='secondary'>{report.remark}</Typography.Text>
            ) : null}
          </Space>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => setReloadKey((key) => key + 1)}>
              {l('dashboard.report.refresh')}
            </Button>
            <Button icon={<FullscreenOutlined />} onClick={() => setImmersive(true)}>
              {l('dashboard.report.fullscreen')}
            </Button>
          </Space>
        </div>
      )}
      {immersive && (
        <Button
          className='report-immersive-exit'
          icon={<FullscreenExitOutlined />}
          onClick={() => setImmersive(false)}
        >
          {l('dashboard.report.exitFullscreen')}
        </Button>
      )}
      {previewUrl ? (
        <iframe
          key={reloadKey}
          title={report?.name ?? 'report'}
          src={previewUrl}
          className='report-iframe'
          // D7：不给 allow-same-origin —— 报表脚本可执行但读不到宿主 cookie/localStorage
          sandbox='allow-scripts'
          referrerPolicy='no-referrer'
        />
      ) : (
        <Alert
          style={{ margin: 16 }}
          type='warning'
          showIcon
          message={l('dashboard.report.previewFailed')}
        />
      )}
    </div>
  );
};

export default ReportView;
