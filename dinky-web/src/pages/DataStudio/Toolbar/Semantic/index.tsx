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

import { connect } from '@@/exports';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Select, Space, Spin, Tooltip, Tree, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import { listSemanticFiles, listSemanticProjects, readSemanticFile } from './service';
import { SemanticFileNode, SemanticProject } from './data.d';
import { l } from '@/utils/intl';

const { Text } = Typography;

type TreeNode = {
  key: string;
  title: string;
  isLeaf: boolean;
  children?: TreeNode[];
};

/** 把后端返回的相对路径扁平列表转成 antd Tree 需要的嵌套结构 */
const buildTree = (list: SemanticFileNode[]): TreeNode[] => {
  const roots: TreeNode[] = [];
  list.forEach((node) => {
    const segments = node.path.split('/');
    let level: TreeNode[] = roots;
    let acc = '';
    segments.forEach((segment, index) => {
      acc = acc ? `${acc}/${segment}` : segment;
      const last = index === segments.length - 1;
      let current = level.find((item) => item.key === acc);
      if (!current) {
        current = { key: acc, title: segment, isLeaf: last && !node.isDir, children: [] };
        level.push(current);
      }
      if (current.children) {
        level = current.children;
      }
    });
  });
  // 收尾：叶子节点不留空 children（否则 antd 会渲染展开箭头）
  const prune = (nodes: TreeNode[]): TreeNode[] =>
    nodes.map((node) => {
      const children = node.children && node.children.length > 0 ? prune(node.children) : undefined;
      return { ...node, children, isLeaf: !children };
    });
  return prune(roots);
};

const Semantic = (props: {
  centerContent: DataStudioState['centerContent'];
  /** dispatch 由 mapDispatchToProps 注入；这里放宽入参类型，避免与 dva 的 CenterTabDTO 冲突 */
  addCenterTab: (payload: any) => void;
  updateCenterTab: (payload: any) => void;
}) => {
  const { centerContent, addCenterTab, updateCenterTab } = props;
  const [projects, setProjects] = useState<SemanticProject[]>([]);
  const [project, setProject] = useState<string | undefined>();
  const [nodes, setNodes] = useState<SemanticFileNode[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | undefined>();

  const loadProjects = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const res = await listSemanticProjects();
      if (res?.code !== 0) {
        setProjects([]);
        setError(res?.msg ?? l('datastudio.semantic.loadFailed'));
        return;
      }
      const list: SemanticProject[] = res?.data ?? [];
      setProjects(list);
      setProject((prev) => prev ?? list[0]?.name);
    } catch (e: any) {
      setProjects([]);
      setError(e?.message ?? l('datastudio.semantic.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadFiles = useCallback(async (name?: string) => {
    if (!name) {
      setNodes([]);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const res = await listSemanticFiles(name);
      if (res?.code !== 0) {
        setNodes([]);
        setError(res?.msg ?? l('datastudio.semantic.loadFailed'));
        return;
      }
      setNodes(res?.data ?? []);
    } catch (e: any) {
      setNodes([]);
      setError(e?.message ?? l('datastudio.semantic.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadProjects();
  }, []);

  useEffect(() => {
    loadFiles(project);
  }, [project]);

  const treeData = useMemo(() => buildTree(nodes), [nodes]);

  /** 打开文件：先读正文，再按「项目+路径」去重开/激活中心 tab */
  const openFile = async (target: SemanticFileNode) => {
    if (!project) {
      return;
    }
    setLoading(true);
    try {
      const res = await readSemanticFile(project, target.path);
      if (res?.code !== 0) {
        setError(res?.msg ?? l('datastudio.semantic.loadFailed'));
        return;
      }
      const data = res.data ?? {};
      const tabId = `semantic_${project}_${target.path}`;
      const tabParams = {
        project,
        path: target.path,
        // AI Chat 直接复用 statement 作为「当前编辑内容」，无需改协议
        statement: data.content ?? '',
        mtime: data.modifiedAt ?? 0,
        readOnly: Boolean(target.readOnly)
      };
      const existed = (centerContent?.tabs ?? []).some((tab) => tab.id === tabId);
      const payload: CenterTab = {
        id: tabId,
        tabType: 'semanticFile',
        title: `${project}/${target.path}`,
        isUpdate: false,
        params: tabParams
      };
      existed ? updateCenterTab(payload) : addCenterTab(payload);
    } catch (e: any) {
      setError(e?.message ?? l('datastudio.semantic.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  const onSelect = (keys: React.Key[], info: any) => {
    const node = info?.node as TreeNode;
    if (!node || node.isLeaf === false) {
      return;
    }
    const target = nodes.find((item) => item.path === node.key);
    if (target && !target.isDir) {
      openFile(target);
    }
  };

  return (
    <ProCard
      style={{ height: '100%' }}
      className={'datastudio-theme'}
      bodyStyle={{ paddingTop: 8, paddingInline: 6, display: 'flex', flexDirection: 'column' }}>
      <Space.Compact style={{ width: '100%', marginBottom: 8 }}>
        <Select
          style={{ width: '100%' }}
          placeholder={l('datastudio.semantic.selectProject')}
          value={project}
          options={projects.map((item) => ({
            value: item.name,
            label: item.fileCount ? `${item.name} (${item.fileCount})` : item.name
          }))}
          onChange={(value) => setProject(value)}
          notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={l('datastudio.semantic.empty')} />}
        />
        <Tooltip title={l('datastudio.semantic.refresh')}>
          <Button
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => {
              loadProjects();
              loadFiles(project);
            }}
          />
        </Tooltip>
      </Space.Compact>

      {error ? (
        <Alert
          type='warning'
          showIcon
          message={error}
          action={
            <Button
              size='small'
              onClick={() => {
                loadProjects();
                loadFiles(project);
              }}>
              {l('datastudio.semantic.retry')}
            </Button>
          }
        />
      ) : (
        <Spin spinning={loading}>
          {projects.length === 0 && !loading ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={<Text type='secondary'>{l('datastudio.semantic.empty')}</Text>}
            />
          ) : (
            <Tree
              blockNode
              showLine
              treeData={treeData}
              onSelect={onSelect}
              style={{ background: 'transparent' }}
            />
          )}
        </Spin>
      )}
    </ProCard>
  );
};

export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(Semantic);
