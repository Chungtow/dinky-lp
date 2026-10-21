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
import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Tooltip,
  Tree,
  Typography,
  message
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { ProCard } from '@ant-design/pro-components';
import RightContextMenu from '@/components/RightContextMenu';
import { InitContextMenuPosition } from '@/pages/DataStudio/function';
import { AuthorizedObject, useAccess } from '@/hooks/useAccess';
import {
  createSemanticDir,
  deleteSemanticEntry,
  listSemanticFiles,
  listSemanticProjects,
  readSemanticFile,
  renameSemanticEntry,
  saveSemanticFile
} from './service';
import { SemanticFileNode, SemanticProject } from './data.d';
import { SemanticRightMenuKey, getSemanticContextMenu } from './constants';
import { l } from '@/utils/intl';

const { Text } = Typography;

type SemanticTreeNode = {
  key: string;
  title: React.ReactNode;
  isLeaf: boolean;
  isDir: boolean;
  readOnly: boolean;
  lastModified?: number;
  children?: SemanticTreeNode[];
};

type EditMode = 'createFile' | 'createDir' | 'rename';

const READ_ONLY_STYLE: React.CSSProperties = { color: 'rgba(0,0,0,0.35)', cursor: 'not-allowed' };

/** 名称合法性：单段、非空、不含路径分隔符、不是 . / .. */
const isValidEntryName = (name: string): boolean => {
  const value = (name ?? '').trim();
  return (
    value.length > 0 &&
    value.length <= 128 &&
    !value.includes('/') &&
    !value.includes('\\') &&
    value !== '.' &&
    value !== '..'
  );
};

/**
 * 把后端返回的相对路径扁平列表转成 antd Tree 需要的嵌套结构。
 *
 * <p>⚠️ P2 版本的坑：`prune` 用 `isLeaf: !children` 覆盖了 `isDir`，导致**空目录被当成文件**，
 * 点击它会走 openFile 直接报"路径非法"。这里改为以 `isDir` 为准（中间合成节点一律视为目录）。
 */
const buildTree = (list: SemanticFileNode[]): SemanticTreeNode[] => {
  const roots: SemanticTreeNode[] = [];
  list.forEach((node) => {
    const segments = node.path.split('/');
    let level: SemanticTreeNode[] = roots;
    let acc = '';
    segments.forEach((segment, index) => {
      acc = acc ? `${acc}/${segment}` : segment;
      const last = index === segments.length - 1;
      let current = level.find((item) => item.key === acc);
      if (!current) {
        const readOnly = last ? Boolean(node.readOnly) : false;
        current = {
          key: acc,
          title: readOnly ? (
            <Tooltip title={l('datastudio.semantic.readOnly')}>
              <span style={READ_ONLY_STYLE}>{segment}</span>
            </Tooltip>
          ) : (
            segment
          ),
          isDir: last ? Boolean(node.isDir) : true,
          readOnly,
          lastModified: last ? node.lastModified : undefined,
          children: []
        };
        level.push(current);
      }
      if (current.children) {
        level = current.children;
      }
    });
  });
  // 收尾：叶子节点不留空 children（否则 antd 会渲染展开箭头），isLeaf 由 isDir 决定
  const prune = (nodes: SemanticTreeNode[]): SemanticTreeNode[] =>
    nodes.map((node) => {
      const children = node.children && node.children.length > 0 ? prune(node.children) : undefined;
      return { ...node, children, isLeaf: !node.isDir };
    });
  return prune(roots);
};

const Semantic = (props: {
  centerContent: DataStudioState['centerContent'];
  /** dispatch 由 mapDispatchToProps 注入；这里放宽入参类型，避免与 dva 的 CenterTabDTO 冲突 */
  addCenterTab: (payload: any) => void;
  updateCenterTab: (payload: any) => void;
  removeCenterTab: (id: string) => void;
}) => {
  const { centerContent, addCenterTab, updateCenterTab, removeCenterTab } = props;
  const access = useAccess();
  const [projects, setProjects] = useState<SemanticProject[]>([]);
  const [project, setProject] = useState<string | undefined>();
  const [nodes, setNodes] = useState<SemanticFileNode[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState<boolean>(false);

  // 右键菜单
  const [contextMenuOpen, setContextMenuOpen] = useState<boolean>(false);
  const [contextMenuPosition, setContextMenuPosition] = useState<any>(InitContextMenuPosition);
  const [rightClicked, setRightClicked] = useState<SemanticTreeNode | undefined>();

  // 新建 / 重命名弹窗
  const [editMode, setEditMode] = useState<EditMode | undefined>();
  const [editTarget, setEditTarget] = useState<SemanticTreeNode | undefined>();
  const [editValue, setEditValue] = useState<string>('');

  // 删除确认弹窗
  const [deleteTarget, setDeleteTarget] = useState<SemanticTreeNode | undefined>();
  const [deleteRecursive, setDeleteRecursive] = useState<boolean>(true);

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

  const loadFiles = useCallback(async (name?: string, silent = false) => {
    if (!name) {
      setNodes([]);
      return;
    }
    if (!silent) {
      setLoading(true);
    }
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
      if (!silent) {
        setLoading(false);
      }
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
  const openFile = async (path: string, readOnly = false) => {
    if (!project) {
      return;
    }
    setLoading(true);
    try {
      const res = await readSemanticFile(project, path);
      if (res?.code !== 0) {
        setError(res?.msg ?? l('datastudio.semantic.loadFailed'));
        return;
      }
      const data = res.data ?? {};
      const tabId = `semantic_${project}_${path}`;
      const tabParams = {
        project,
        path,
        // AI Chat 直接复用 statement 作为「当前编辑内容」，无需改协议
        statement: data.content ?? '',
        mtime: data.modifiedAt ?? 0,
        readOnly: Boolean(readOnly)
      };
      const existed = (centerContent?.tabs ?? []).some((tab) => tab.id === tabId);
      const payload: CenterTab = {
        id: tabId,
        tabType: 'semanticFile',
        title: `${project}/${path}`,
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
    const node = info?.node as SemanticTreeNode;
    if (!node || node.isDir) {
      return;
    }
    openFile(node.key, node.readOnly);
  };

  /** 命中被改名的路径（或目录下所有文件）的 tab 一律关闭：不做自动改 key（D13） */
  const closeAffectedTabs = (path: string, isDir: boolean) => {
    const tabs = centerContent?.tabs ?? [];
    const hit = tabs.filter((tab) => {
      if (tab.tabType !== 'semanticFile' || tab.params?.project !== project) {
        return false;
      }
      const tabPath = String(tab.params?.path ?? '');
      return isDir ? tabPath === path || tabPath.startsWith(`${path}/`) : tabPath === path;
    });
    if (hit.length === 0) {
      return;
    }
    hit.forEach((tab) => removeCenterTab(tab.id));
    message.info(l('datastudio.semantic.tabsClosed', undefined, { n: hit.length }));
  };

  /** 该路径下是否有未保存的 tab（改名/删除前必须先落盘） */
  const hasDirtyTabs = (path: string, isDir: boolean): boolean =>
    (centerContent?.tabs ?? []).some((tab) => {
      if (tab.tabType !== 'semanticFile' || tab.params?.project !== project || !tab.isUpdate) {
        return false;
      }
      const tabPath = String(tab.params?.path ?? '');
      return isDir ? tabPath === path || tabPath.startsWith(`${path}/`) : tabPath === path;
    });

  const refresh = (name?: string) => {
    loadProjects();
    loadFiles(name ?? project);
  };

  const resetMenu = () => {
    setContextMenuOpen(false);
  };

  const onRightClick = (info: any) => {
    const { event, node } = info;
    if (!node) {
      return;
    }
    event.preventDefault();
    setRightClicked(node as SemanticTreeNode);
    setEditTarget(node as SemanticTreeNode);
    // 静默刷新一次树：改名/删除要用「当前」mtime 做乐观锁，不能用几分钟前的快照
    loadFiles(project, true);
    // 与 Project tab 同一套越界修正（function.tsx: handleRightClick）
    let x = event.clientX;
    let y = event.clientY;
    if (x + 180 > window.innerWidth) {
      x = window.innerWidth - 190;
    }
    if (y + 200 > window.innerHeight) {
      y = window.innerHeight - 210;
    }
    setContextMenuPosition({ ...InitContextMenuPosition, top: y + 5, left: x + 10 });
    setContextMenuOpen(true);
  };

  const menuItems = useMemo(() => {
    if (!rightClicked) {
      return [];
    }
    return getSemanticContextMenu({ isDir: rightClicked.isDir, readOnly: rightClicked.readOnly }).filter(
      (menu: any) => !menu.path || !!AuthorizedObject({ path: menu.path, children: menu, access })
    );
  }, [rightClicked, access]);

  /** 目录下的条目数（删除确认里显示"含 N 项"） */
  const countChildren = (path: string): number =>
    nodes.filter((item) => item.path !== path && item.path.startsWith(`${path}/`)).length;

  /**
   * 取「当前最新」的 mtime：优先用树里最近一次加载的值，退回右键时刻的快照。
   *
   * <p>否则「刚保存完文件 → 立刻改名/删除」会拿旧 mtime 撞乐观锁、误报
   * `semantic.write.conflict`（保存只改了 HDFS，面板的树并没有重新加载）。
   */
  const freshMtime = (target: SemanticTreeNode): number | undefined =>
    nodes.find((item) => item.path === target.key)?.lastModified ?? target.lastModified;

  const openCreateDialog = (mode: EditMode) => {
    setEditMode(mode);
    setEditValue('');
    resetMenu();
  };

  const submitEdit = async () => {
    if (!project || !editMode) {
      return;
    }
    const value = editValue.trim();
    if (!isValidEntryName(value)) {
      message.warning(l('datastudio.semantic.nameInvalid'));
      return;
    }
    const parent = editMode === 'rename' ? undefined : editTarget?.key;
    setSubmitting(true);
    try {
      if (editMode === 'createDir') {
        const path = parent ? `${parent}/${value}` : value;
        const res = await createSemanticDir({ project, path });
        if (res?.code !== 0) {
          message.error(res?.msg ?? l('datastudio.semantic.opFailed'));
          return;
        }
        message.success(l('datastudio.semantic.created'));
        setEditMode(undefined);
        refresh();
      } else if (editMode === 'createFile') {
        const path = parent ? `${parent}/${value}` : value;
        const res = await saveSemanticFile({ project, path, content: '' });
        if (res?.code !== 0) {
          message.error(res?.msg ?? l('datastudio.semantic.opFailed'));
          return;
        }
        message.success(l('datastudio.semantic.created'));
        setEditMode(undefined);
        await loadFiles(project);
        await openFile(path);
      } else if (editMode === 'rename') {
        if (!editTarget) {
          return;
        }
        if (hasDirtyTabs(editTarget.key, editTarget.isDir)) {
          message.warning(l('datastudio.semantic.dirtyBeforeOp'));
          return;
        }
        const res = await renameSemanticEntry({
          project,
          path: editTarget.key,
          newName: value,
          isDir: editTarget.isDir,
          baseMtime: freshMtime(editTarget)
        });
        if (res?.code !== 0) {
          message.error(res?.msg ?? l('datastudio.semantic.opFailed'));
          return;
        }
        message.success(l('datastudio.semantic.renamed'));
        setEditMode(undefined);
        closeAffectedTabs(editTarget.key, editTarget.isDir);
        refresh();
      }
    } catch (e: any) {
      message.error(e?.message ?? l('datastudio.semantic.opFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const submitDelete = async () => {
    if (!project || !deleteTarget) {
      return;
    }
    setSubmitting(true);
    try {
      const res = await deleteSemanticEntry({
        project,
        path: deleteTarget.key,
        recursive: deleteTarget.isDir ? deleteRecursive : undefined,
        baseMtime: deleteTarget.isDir ? undefined : freshMtime(deleteTarget)
      });
      if (res?.code !== 0) {
        message.error(res?.msg ?? l('datastudio.semantic.opFailed'));
        return;
      }
      message.success(l('datastudio.semantic.deleted'));
      closeAffectedTabs(deleteTarget.key, deleteTarget.isDir);
      setDeleteTarget(undefined);
      refresh();
    } catch (e: any) {
      message.error(e?.message ?? l('datastudio.semantic.opFailed'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleMenuClick = (info: any) => {
    resetMenu();
    if (!rightClicked) {
      return;
    }
    switch (info.key) {
      case SemanticRightMenuKey.CREATE_FILE:
        openCreateDialog('createFile');
        break;
      case SemanticRightMenuKey.CREATE_DIR:
        openCreateDialog('createDir');
        break;
      case SemanticRightMenuKey.RENAME:
        if (hasDirtyTabs(rightClicked.key, rightClicked.isDir)) {
          message.warning(l('datastudio.semantic.dirtyBeforeOp'));
          break;
        }
        setEditTarget(rightClicked);
        setEditValue(rightClicked.key.split('/').pop() ?? '');
        setEditMode('rename');
        break;
      case SemanticRightMenuKey.DELETE:
        if (hasDirtyTabs(rightClicked.key, rightClicked.isDir)) {
          message.warning(l('datastudio.semantic.dirtyBeforeOp'));
          break;
        }
        setDeleteTarget(rightClicked);
        setDeleteRecursive(true);
        break;
      case SemanticRightMenuKey.REFRESH:
        refresh();
        break;
      default:
        break;
    }
  };

  const editTitle = () => {
    switch (editMode) {
      case 'createFile':
        return l('datastudio.semantic.ctx.createFile');
      case 'createDir':
        return l('datastudio.semantic.ctx.createDir');
      case 'rename':
        return l('datastudio.semantic.ctx.rename');
      default:
        return '';
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
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => refresh()} />
        </Tooltip>
      </Space.Compact>

      {error ? (
        <Alert
          type='warning'
          showIcon
          message={error}
          action={
            <Button size='small' onClick={() => refresh()}>
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
              treeData={treeData as any}
              onSelect={onSelect}
              onRightClick={onRightClick}
              style={{ background: 'transparent' }}
            />
          )}
        </Spin>
      )}

      <RightContextMenu
        contextMenuPosition={contextMenuPosition}
        open={contextMenuOpen}
        openChange={resetMenu}
        items={menuItems as any}
        onClick={handleMenuClick}
      />

      <Modal
        open={Boolean(editMode)}
        title={editTitle()}
        onCancel={() => setEditMode(undefined)}
        onOk={submitEdit}
        confirmLoading={submitting}
        destroyOnClose>
        <Input
          autoFocus
          value={editValue}
          placeholder={l('datastudio.semantic.namePlaceholder')}
          onChange={(e) => setEditValue(e.target.value)}
          onPressEnter={submitEdit}
        />
        {editMode !== 'rename' && editTarget && (
          <Text type='secondary' style={{ display: 'block', marginTop: 8 }}>
            {l('datastudio.semantic.createUnder', undefined, { path: editTarget.key })}
          </Text>
        )}
      </Modal>

      <Modal
        open={Boolean(deleteTarget)}
        title={l('datastudio.semantic.ctx.delete')}
        okButtonProps={{ danger: true }}
        okText={l('datastudio.semantic.ctx.delete')}
        onCancel={() => setDeleteTarget(undefined)}
        onOk={submitDelete}
        confirmLoading={submitting}
        destroyOnClose>
        {deleteTarget && (
          <Space direction='vertical' style={{ width: '100%' }}>
            <Text>
              {deleteTarget.isDir
                ? l('datastudio.semantic.deleteDirConfirm', undefined, {
                    path: deleteTarget.key,
                    n: countChildren(deleteTarget.key)
                  })
                : l('datastudio.semantic.deleteFileConfirm', undefined, { path: deleteTarget.key })}
            </Text>
            {deleteTarget.isDir && (
              <Checkbox checked={deleteRecursive} onChange={(e) => setDeleteRecursive(e.target.checked)}>
                {l('datastudio.semantic.deleteRecursive')}
              </Checkbox>
            )}
            <Alert type='warning' showIcon message={l('datastudio.semantic.deleteTrashHint')} />
            <Alert type='info' showIcon message={l('datastudio.semantic.deletePullHint')} />
          </Space>
        )}
      </Modal>
    </ProCard>
  );
};

export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(Semantic);
