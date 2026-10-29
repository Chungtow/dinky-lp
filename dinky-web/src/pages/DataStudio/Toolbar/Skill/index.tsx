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

import { connect, useModel } from '@@/exports';
import { CenterTab, DataStudioState } from '@/pages/DataStudio/model';
import { mapDispatchToProps } from '@/pages/DataStudio/DvaFunction';
import {
  detailSkill,
  listSkillFiles,
  listSkills,
  mkdirSkill,
  readSkillFile,
  removeSkillFile,
  renameSkillFile
} from '@/pages/RegCenter/Skills/service';
import { SkillFileNode, SkillInfo } from '@/types/RegCenter/skill';
import PushModal, { KbPushPayload } from '@/pages/DataStudio/Toolbar/Kb/PushModal';
import { HasAuthority } from '@/hooks/useAccess';
import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import { ErrorMessage, SuccessMessage, WarningMessage } from '@/utils/messages';
import {
  BookOutlined,
  FileTextOutlined,
  FolderOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons';
import ThemeChip from '@/components/ThemeChip';
import { Button, Dropdown, Empty, Input, Modal, Space, Spin, Tooltip, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { getSkillContextMenu, SkillMenuContext, SkillRightMenuKey } from './constants';

const { Text } = Typography;

/** private → Private（拼可见性词条 key 用） */
const capitalize = (text: string) => (text ? text.charAt(0).toUpperCase() + text.slice(1) : text);

/**
 * 主文件名：skill → {@code SKILL.md}，doc → {@code DOC.md}（与后端 {@code mainFileOf} 同口径）。
 *
 * <p>⚠️ 写接口**不接受空路径**（{@code SkillPathGuard.checkExtension} 会报"不允许的文件类型"），
 * 所以打开主文件时必须带真实文件名，不能像读接口那样用 blank 表示主文件。
 */
const mainNameOf = (assetType?: string) => (assetType === 'doc' ? 'DOC.md' : 'SKILL.md');

/** 树节点上挂的业务信息（antd Tree 只认 key/title，业务字段要自己带） */
type SkillTreeNode = DataNode & {
  skillId: number;
  skillName: string;
  ownerId?: number;
  /** 相对路径；skill 根节点为 undefined，主文件为 '' 或 SKILL.md/DOC.md */
  relativePath?: string;
  directory?: boolean;
  mainFile?: boolean;
};

/** 技能 tab（批次 F）。 */
const Skill = (props: {
  centerContent: DataStudioState['centerContent'];
  addCenterTab: (payload: any) => void;
  activateCenterTab: (id: string) => void;
  /**
   * 批次 F：把「当前选中的技能」写回 DVA model，供 AI Chat 默认绑定。
   *
   * <p>为什么必须上报而不是让 AI Chat 去猜：在技能树上换技能不一定换中间 tab，
   * 只读 tab params 会一直停在上次打开的文件所属技能（与语义项目 UAT 2026-10-22 同一条）。
   */
  saveCurrentSkill?: (payload: { skillId?: number; skillName?: string }) => void;
  /** 关闭中间 tab（重命名后旧路径的 tab 必须关掉，否则变成一个指向已不存在文件的僵尸） */
  removeCenterTab?: (id: string) => void;
}) => {
  const { centerContent, addCenterTab, activateCenterTab, saveCurrentSkill, removeCenterTab } = props;
  const { initialState } = useModel('@@initialState');
  const currentUserId = (initialState?.currentUser as any)?.user?.id;

  const [loading, setLoading] = useState(false);
  const [skills, setSkills] = useState<SkillInfo[]>([]);
  const [keyword, setKeyword] = useState('');
  /** skillId → 文件树（展开时才拉，避免一次点开面板就打 N 个请求） */
  const [filesMap, setFilesMap] = useState<Record<number, SkillFileNode[]>>({});
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number; node: SkillTreeNode } | undefined>();
  const [dirModal, setDirModal] = useState<{ open: boolean; node?: SkillTreeNode; mode: 'file' | 'dir' }>({
    open: false,
    mode: 'file'
  });
  const [dirName, setDirName] = useState('');
  /** 重命名目标（node + 输入值）；undefined = 弹窗关闭 */
  const [renameTarget, setRenameTarget] = useState<{ node: SkillTreeNode; name: string } | undefined>();
  /** 推送到知识库的载荷（与语义树共用 PushModal；undefined = 弹窗关闭） */
  const [pushPayload, setPushPayload] = useState<KbPushPayload | null>(null);
  /** 是否有知识库写权限（推送是"写知识库"动作，权限口径与 skill 结构操作不同） */
  const canPush = HasAuthority(PermissionConstants.KB_WRITE);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const list = await listSkills();
      setSkills(list ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSkills();
  }, [loadSkills]);

  /** 拉某个 skill 的文件树（幂等；force 用于刷新） */
  const loadFiles = useCallback(async (skillId: number, force = false) => {
    if (!force && filesMap[skillId]) {
      return filesMap[skillId];
    }
    const files = await listSkillFiles(skillId);
    setFilesMap((prev) => ({ ...prev, [skillId]: files }));
    return files;
  }, [filesMap]);

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) {
      return skills;
    }
    return skills.filter(
      (item) =>
        (item.name ?? '').toLowerCase().includes(kw) ||
        (item.description ?? '').toLowerCase().includes(kw)
    );
  }, [skills, keyword]);

  /** 扁平文件列表 → 树（目录在前，按名字排序） */
  const toFileNodes = useCallback((skill: SkillInfo, nodes: SkillFileNode[]): SkillTreeNode[] => {
    return (nodes ?? [])
      .slice()
      .sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name))
      .map((node) => ({
        key: `file_${skill.id}_${node.relativePath}`,
        title: (
          <Space size={4}>
            {node.directory ? <FolderOutlined /> : <FileTextOutlined />}
            <span>{node.name}</span>
            {node.mainFile && <ThemeChip tone="info">{l('datastudio.skill.mainFile')}</ThemeChip>}
          </Space>
        ),
        isLeaf: !node.directory,
        children: node.directory ? toFileNodes(skill, node.children ?? []) : undefined,
        skillId: skill.id,
        skillName: skill.name,
        ownerId: skill.ownerId,
        relativePath: node.relativePath,
        directory: node.directory,
        mainFile: node.mainFile
      }));
  }, []);

  const treeData = useMemo<SkillTreeNode[]>(() => {
    return filtered.map((skill) => {
      const files = filesMap[skill.id];
      const owned = currentUserId === undefined || Number(skill.ownerId) === Number(currentUserId);
      return {
        key: `skill_${skill.id}`,
        title: (
          <Space size={4}>
            <BookOutlined />
            <Text strong>{skill.name}</Text>
            {skill.visibility && skill.visibility !== 'private' && (
              <ThemeChip tone={skill.visibility === 'public' ? 'warn' : 'info'}>
                {l(`pages.skill.visibility${capitalize(skill.visibility)}`)}
              </ThemeChip>
            )}
            {!owned && <ThemeChip>{l('datastudio.skill.readOnlyTag')}</ThemeChip>}
          </Space>
        ),
        skillId: skill.id,
        skillName: skill.name,
        ownerId: skill.ownerId,
        directory: true,
        // 未展开时不放 children，交给 loadData（否则一次展开全部 skill 会打出 N 个请求）
        children: files ? toFileNodes(skill, files) : undefined
      };
    });
  }, [filtered, filesMap, currentUserId, toFileNodes]);

  /** 打开某个文件：内容在这里读好放进 tab params，AI Chat 因此能读到"当前编辑内容" */
  const openFile = useCallback(
    async (node: SkillTreeNode) => {
      if (node.directory || node.relativePath === undefined) {
        return;
      }
      const tabId = `skill_${node.skillId}_${node.relativePath}`;
      if (centerContent.tabs.some((tab) => tab.id === tabId)) {
        activateCenterTab(tabId);
        return;
      }
      const skill = skills.find((item) => item.id === node.skillId);
      try {
        // 每次打开都取一次最新元数据（UAT 2026-10-29）：面板里的 skills 列表是挂载时拉的，
        // 别的窗口 / 别人保存过之后这里仍是旧 version → tab 显示"版本 1"，
        // 乐观锁还会拿旧版本当基准，用户只能清缓存才看到新版本。开销只是一次 detail 查询。
        const detail = await detailSkill(node.skillId).catch(() => undefined);
        const freshVersion = detail?.skill?.version ?? skill?.version;
        const canEdit =
          detail?.editable ??
          (currentUserId === undefined || Number(skill?.ownerId) === Number(currentUserId));
        const content = await readSkillFile(node.skillId, node.relativePath);
        // 顺手把列表面里的版本号对齐，避免树上/列表还显示旧值
        if (freshVersion !== undefined && skill && skill.version !== freshVersion) {
          setSkills((prev) =>
            prev.map((item) => (item.id === node.skillId ? { ...item, version: freshVersion } : item))
          );
        }
        const tab: CenterTab = {
          id: tabId,
          title: `${node.skillName} / ${node.relativePath || 'SKILL.md'}`,
          tabType: 'skillFile',
          isUpdate: false,
          params: {
            skillId: node.skillId,
            skillName: node.skillName,
            relativePath: node.relativePath,
            mainFile: Boolean(node.mainFile),
            statement: content,
            // 批次 F 乐观锁：主文件用 skill.version 作编辑起点（附件后端不校验）
            version: freshVersion,
            readOnly: !canEdit
          }
        };
        addCenterTab(tab);
      } catch (e: any) {
        ErrorMessage(e?.message ?? l('datastudio.skill.openFailed'));
      }
    },
    [activateCenterTab, addCenterTab, centerContent.tabs, currentUserId, skills]
  );

  const onSelect = useCallback(
    async (keys: React.Key[], info: any) => {
      const node = info?.node as SkillTreeNode | undefined;
      if (!node) {
        return;
      }
      // 批次 F：点 skill 根 / 目录 / 文件都算「当前关注这个技能」，先上报再打开
      // （AI Chat 侧「打开的文件所属技能」优先级更高，这里只是树选中态的兜底）
      saveCurrentSkill?.({ skillId: node.skillId, skillName: node.skillName });
      if (node.directory) {
        // 点 skill 根 = 打开它的主文件（用户最常改的就是 SKILL.md / DOC.md）
        const files = await loadFiles(node.skillId);
        const main = (files ?? []).find((item) => item.mainFile);
        const skill = skills.find((item) => item.id === node.skillId);
        // 文件树里拿不到时按 assetType 兜底——**必须给真实文件名**，写接口不接受空路径
        const mainPath = main?.relativePath || mainNameOf(skill?.assetType);
        await openFile({ ...node, relativePath: mainPath, mainFile: true } as SkillTreeNode);
        return;
      }
      await openFile(node);
    },
    [loadFiles, openFile, saveCurrentSkill]
  );

  const onExpand = useCallback(
    async (keys: React.Key[], info: any) => {
      setExpandedKeys(keys);
      const node = info?.node as SkillTreeNode | undefined;
      if (node?.directory && node.relativePath === undefined) {
        await loadFiles(node.skillId);
      }
    },
    [loadFiles]
  );

  const onRightClick = useCallback(
    ({ event, node }: any) => {
      event.preventDefault();
      const target = node as SkillTreeNode;
      setCtxMenu({ x: event.clientX, y: event.clientY, node: target });
    },
    []
  );

  const handleMenuClick = useCallback(
    async (info: { key: string }) => {
      const node = ctxMenu?.node;
      setCtxMenu(undefined);
      if (!node) {
        return;
      }
      switch (info.key) {
        case SkillRightMenuKey.REFRESH: {
          await loadSkills();
          if (filesMap[node.skillId]) {
            await loadFiles(node.skillId, true);
          }
          return;
        }
        case SkillRightMenuKey.MANAGE: {
          // 创建 / 可见性 / 删除整库都在注册中心，这里只负责带路。
          // ⚠️ 必须带 `#`：本项目是 **hash 路由**（config/config.ts `history: { type: 'hash' }`），
          // 前端路由整体位于 `#` 之后；写成 `${origin}/registration/skill` 会被当成服务端路径，
          // 静态资源里没有这个目录 → 404（测试槽 8889 实测）。
          // 用 pathname 而非 origin 拼 `/#/`：部署在子路径（如 /dinky/）时不会丢掉前缀。
          window.open(`${window.location.pathname}#/registration/skill`, '_blank');
          return;
        }
        case SkillRightMenuKey.CREATE_FILE:
          setDirName('');
          setDirModal({ open: true, node, mode: 'file' });
          return;
        case SkillRightMenuKey.CREATE_DIR:
          setDirName('');
          setDirModal({ open: true, node, mode: 'dir' });
          return;
        case SkillRightMenuKey.RENAME: {
          if (!node.relativePath) {
            return;
          }
          // 只改文件名：预填原名（含扩展名），用户改前缀即可，避免手滑把后缀删掉
          const current = node.relativePath.split('/').pop() ?? '';
          setRenameTarget({ node, name: current });
          return;
        }
        case SkillRightMenuKey.PUSH: {
          if (!node.relativePath) {
            return;
          }
          // 与语义树同一条口径：推送前**现读一次正文**，不复用编辑区里可能未保存的快照
          try {
            const content = await readSkillFile(node.skillId, node.relativePath);
            setPushPayload({
              filename: node.relativePath.split('/').pop() ?? 'skill.md',
              content: content ?? '',
              source: 'skill',
              // skill id 作为业务主键：后端据此把 kb_doc_id 回写到 dinky_skill
              externalId: String(node.skillId)
            });
          } catch (e: any) {
            ErrorMessage(e?.message ?? l('datastudio.kb.readFailed'));
          }
          return;
        }
        case SkillRightMenuKey.DELETE: {
          if (!node.relativePath) {
            return;
          }
          Modal.confirm({
            title: l('datastudio.skill.ctx.deleteConfirm', undefined, { path: node.relativePath }),
            okType: 'danger',
            onOk: async () => {
              await removeSkillFile({ skillId: node.skillId, relativePath: node.relativePath! });
              // 文件/目录已不存在：把可能开着的 tab 关掉，否则编辑器还拿着已删正文
              removeCenterTab?.(`skill_${node.skillId}_${node.relativePath}`);
              await loadFiles(node.skillId, true);
            }
          });
          return;
        }
        default:
          return;
      }
    },
    [ctxMenu?.node, filesMap, loadFiles, loadSkills, removeCenterTab]
  );

  /**
   * 提交重命名（批次 F）。
   *
   * <p>两条纪律：
   * <ol>
   *   <li>目标 tab 有未保存修改时**先拦住**——改名后旧 tab 必须关掉，未保存的内容会一起丢；</li>
   *   <li>改名成功后关掉旧路径的 tab 并刷新文件树，避免出现"指向已不存在文件"的僵尸编辑器。</li>
   * </ol>
   */
  const submitRename = useCallback(async () => {
    const target = renameTarget;
    if (!target) {
      return;
    }
    const name = target.name.trim();
    if (!name) {
      await WarningMessage(l('datastudio.skill.nameRequired'));
      return;
    }
    const { node } = target;
    const relativePath = node.relativePath ?? '';
    const trimmedPath = relativePath;
    const tabId = `skill_${node.skillId}_${trimmedPath}`;
    const openTab = centerContent.tabs.find((tab) => tab.id === tabId);
    if (openTab?.isUpdate) {
      await WarningMessage(l('datastudio.skill.dirtyBeforeOp'));
      return;
    }
    setRenameTarget(undefined);
    await renameSkillFile({ skillId: node.skillId, relativePath: trimmedPath, newName: name });
    if (openTab) {
      removeCenterTab?.(tabId);
    }
    await loadFiles(node.skillId, true);
    await SuccessMessage(l('datastudio.skill.renamed', undefined, { name }));
  }, [renameTarget, centerContent.tabs, removeCenterTab, loadFiles]);

  const submitDirModal = useCallback(async () => {
    const node = dirModal.node;
    const name = dirName.trim();
    if (!node || !name) {
      await WarningMessage(l('datastudio.skill.nameRequired'));
      return;
    }
    // 相对路径：根上直接给名字；目录内拼到父路径（附件统一进 references/ 是用户自己的选择，不硬编码）
    const base = node.relativePath ? `${node.relativePath}/` : '';
    const relativePath = `${base}${name}`;
    if (dirModal.mode === 'dir') {
      await mkdirSkill({ skillId: node.skillId, relativePath });
      await loadFiles(node.skillId, true);
      await SuccessMessage(l('datastudio.skill.dirCreated', undefined, { path: relativePath }));
    } else {
      await openFile({
        ...node,
        directory: false,
        mainFile: false,
        relativePath
      } as SkillTreeNode);
      // 新建文件走"打开空文档 → 保存时落盘"，避免这里落一个空文件；需要刷新后出现在树里
      await loadFiles(node.skillId, true);
    }
    setDirModal({ open: false, mode: 'file' });
    setDirName('');
  }, [dirModal, dirName, loadFiles, openFile]);

  const ctxMenuItems = useMemo(() => {
    const node = ctxMenu?.node;
    if (!node) {
      return [];
    }
    const owned = currentUserId === undefined || Number(node.ownerId) === Number(currentUserId);
    const ctx: SkillMenuContext = {
      isDir: Boolean(node.directory),
      editable: owned,
      mainFile: node.mainFile,
      canPush
    };
    return getSkillContextMenu(ctx);
  }, [ctxMenu?.node, currentUserId, canPush]);

  return (
    <div className={'dinky-toolbar-panel'} style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Space style={{ padding: '4px 8px' }}>
        <Input
          allowClear
          size={'small'}
          prefix={<SearchOutlined />}
          placeholder={l('datastudio.skill.searchPlaceholder')}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ width: 170 }}
        />
        <Tooltip title={l('datastudio.skill.refresh')}>
          <Button size={'small'} type={'text'} icon={<ReloadOutlined />} onClick={() => loadSkills()} />
        </Tooltip>
      </Space>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '0 8px 8px' }}>
        <Spin spinning={loading}>
          {filtered.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={l('datastudio.skill.empty')}
              style={{ marginTop: 32 }}
            />
          ) : (
            <Tree
              blockNode
              showIcon={false}
              treeData={treeData as DataNode[]}
              expandedKeys={expandedKeys}
              onExpand={onExpand}
              onSelect={onSelect}
              onRightClick={onRightClick}
              loadData={async (node: any) => {
                const target = node as SkillTreeNode;
                if (target.directory && target.relativePath === undefined) {
                  await loadFiles(target.skillId);
                }
              }}
            />
          )}
        </Spin>
      </div>
      {ctxMenu && (
        <Dropdown
          open
          trigger={['click']}
          menu={{ items: ctxMenuItems as any, onClick: handleMenuClick }}
          onOpenChange={(open) => {
            if (!open) {
              setCtxMenu(undefined);
            }
          }}
        >
          <span style={{ position: 'fixed', left: ctxMenu.x, top: ctxMenu.y, width: 1, height: 1 }} />
        </Dropdown>
      )}
      <Modal
        open={dirModal.open}
        title={
          dirModal.mode === 'dir'
            ? l('datastudio.skill.ctx.createDir')
            : l('datastudio.skill.ctx.createFile')
        }
        onCancel={() => setDirModal({ open: false, mode: 'file' })}
        onOk={submitDirModal}
        destroyOnClose
      >
        <Input
          value={dirName}
          onChange={(e) => setDirName(e.target.value)}
          placeholder={l('datastudio.skill.newNamePlaceholder')}
          autoFocus
        />
        <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
          {l('datastudio.skill.newNameTip')}
        </div>
      </Modal>
      <Modal
        open={!!renameTarget}
        title={l('datastudio.skill.ctx.rename')}
        onCancel={() => setRenameTarget(undefined)}
        onOk={submitRename}
        okText={l('button.confirm')}
        destroyOnClose
      >
        <Input
          value={renameTarget?.name}
          onChange={(e) => setRenameTarget((prev) => (prev ? { ...prev, name: e.target.value } : prev))}
          placeholder={l('datastudio.skill.renamePlaceholder')}
          autoFocus
        />
        <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
          {l('datastudio.skill.renameTip')}
        </div>
      </Modal>
      {/* 与语义树共用同一个推送弹窗：契约完全一致（kind 之外只有 filename/content/source/externalId） */}
      <PushModal open={!!pushPayload} payload={pushPayload} onClose={() => setPushPayload(null)} />
    </div>
  );
};

// connect 的 OwnProps 推断对"父组件不传 props"的面板不成立，与 Toolbar/Kb、Toolbar/Semantic 同一类既有告警；
// 运行期注入仍是 mapDispatchToProps 的 addCenterTab / activateCenterTab。
export default connect(
  ({ DataStudio }: { DataStudio: DataStudioState }) => ({
    centerContent: DataStudio.centerContent
  }),
  mapDispatchToProps
)(Skill as any) as React.ComponentType<any>;
