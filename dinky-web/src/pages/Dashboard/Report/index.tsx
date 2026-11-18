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
import {
  deleteReport,
  getReportDetail,
  getReportList,
  saveOrUpdateReport
} from '@/pages/Dashboard/Report/service';
import ReportModal from '@/pages/Dashboard/Report/components/ReportModal';
import { Authorized } from '@/hooks/useAccess';
import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import { ProList } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Button, Empty, Modal, Tag } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';

/**
 * 报表中心
 *
 * <p>卡片列表：名称 + 备注 + 绑定资源 + 更新时间。列表内容由后端按角色可见性过滤，
 * 前端不做二次过滤（否则「公共报表」口径会与后端不一致）。
 * 「管理」与「编辑/删除」仅 `dashboard:report:manage` 可见。
 */
const ReportCenter: React.FC = () => {
  const [data, setData] = useState<ReportData[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ReportData | undefined>();

  const queryListData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getReportList();
      setData((result?.data as ReportData[]) ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    queryListData();
  }, [queryListData]);

  const handleCreate = () => {
    setEditing(undefined);
    setModalOpen(true);
  };

  const handleEdit = async (row: ReportData) => {
    const result = await getReportDetail(row.id as number);
    setEditing((result?.data as ReportData) ?? row);
    setModalOpen(true);
  };

  const handleDelete = (row: ReportData) => {
    Modal.confirm({
      title: l('dashboard.report.delete'),
      content: l('dashboard.report.deleteConfirm'),
      okText: l('button.delete'),
      cancelText: l('button.cancel'),
      okButtonProps: { danger: true },
      onOk: async () => {
        const success = await deleteReport(row.id as number);
        if (success) {
          await queryListData();
        }
      }
    });
  };

  const handleSubmit = async (values: ReportData) => {
    const success = await saveOrUpdateReport(values);
    if (success) {
      setModalOpen(false);
      setEditing(undefined);
      await queryListData();
    }
  };

  return (
    <>
      <ProList<ReportData>
        loading={loading}
        rowKey='id'
        dataSource={data}
        bordered
        pagination={data.length > 12 ? { pageSize: 12 } : false}
        locale={{ emptyText: <Empty description={l('dashboard.report.empty')} /> }}
        metas={{
          title: {
            dataIndex: 'name',
            render: (_, row) => (
              <a onClick={() => history.push(`/dashboard/report/view?id=${row.id}`)}>{row.name}</a>
            )
          },
          description: {
            dataIndex: 'remark'
          },
          content: {
            render: (_, row) => (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <Tag color='blue'>{row.resourceName ?? row.resourcePath ?? '-'}</Tag>
                {row.updateTime ? (
                  <span style={{ color: 'rgba(0,0,0,0.45)' }}>
                    {l('dashboard.report.updateTime')}: {row.updateTime}
                  </span>
                ) : null}
              </div>
            )
          },
          actions: {
            render: (_, row) => [
              <Authorized key='open' path={PermissionConstants.DASHBOARD_REPORT_OPEN}>
                <a onClick={() => history.push(`/dashboard/report/view?id=${row.id}`)}>
                  {l('dashboard.report.open')}
                </a>
              </Authorized>,
              <Authorized key='edit' path={PermissionConstants.DASHBOARD_REPORT_MANAGE}>
                <a onClick={() => handleEdit(row)}>{l('button.edit')}</a>
              </Authorized>,
              <Authorized key='delete' path={PermissionConstants.DASHBOARD_REPORT_MANAGE}>
                <a onClick={() => handleDelete(row)}>{l('button.delete')}</a>
              </Authorized>
            ]
          }
        }}
        toolbar={{
          actions: [
            <Authorized key='create' path={PermissionConstants.DASHBOARD_REPORT_MANAGE}>
              <Button type='primary' onClick={handleCreate}>
                {l('dashboard.report.create')}
              </Button>
            </Authorized>
          ]
        }}
      />
      <ReportModal
        open={modalOpen}
        values={editing}
        onCancel={() => {
          setModalOpen(false);
          setEditing(undefined);
        }}
        onSubmit={handleSubmit}
      />
    </>
  );
};

export default ReportCenter;
