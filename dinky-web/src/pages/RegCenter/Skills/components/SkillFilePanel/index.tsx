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

import { l } from '@/utils/intl';
import type { SkillFileNode } from '@/types/RegCenter/skill';
import { DeleteOutlined, FileAddOutlined, FolderAddOutlined, SaveOutlined } from '@ant-design/icons';
import { Button, Drawer, Empty, Input, Modal, Space, Tooltip, Tree, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { listSkillFiles, mkdirSkill, readSkillFile, removeSkillFile, writeSkillFile } from '../../service';

const { TextArea } = Input;

type Props = {
  skillId?: number;
  skillName?: string;
  /** skill | doc（决定主文件名：SKILL.md / DOC.md） */
  assetType?: string;
  open: boolean;
  /** 是否可编辑：非属主只读查看（后端同样会拒绝写入） */
  editable?: boolean;
  onClose: () => void;
};

/** 后端已按相对路径给出树；这里转成 antd Tree 需要的结构（只补一个「主文件」角标） */
const toTreeData = (nodes?: SkillFileNode[]): any[] =>
  (nodes ?? []).map((node) => ({
    key: node.relativePath,
    title: node.mainFile ? `${node.name}   〔${l('pages.skill.file.mainFile')}〕` : node.name,
    isLeaf: !node.directory,
    children: node.directory ? toTreeData(node.children) : undefined,
  }));

/**
 * Skill 文件面板（阶段 4b）。
 *
 * <p>补上 4a 的缺口：此前只能在弹框里写主文件，`references/` 子目录与子文件**没有任何入口**
 * （资源页被 hideSkillsPrefix 挡住、Skills 页又没有文件树）。这里给出完整目录树 + 编辑 + 新建
 * 文件 / 目录 + 删除，**与 AI 工具共用同一组后端接口**（因此权限与路径校验规则完全一致）。
 */
const SkillFilePanel = ({ skillId, skillName, assetType, open, editable = true, onClose }: Props) => {
  const [tree, setTree] = useState<SkillFileNode[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [content, setContent] = useState<string>('');
  const [dirty, setDirty] = useState(false);

  const mainFile = assetType === 'doc' ? 'DOC.md' : 'SKILL.md';

  const load = async (keepSelection?: string) => {
    if (!skillId) {
      return;
    }
    const nodes = await listSkillFiles(skillId);
    setTree(nodes);
    const target = keepSelection ?? mainFile;
    setSelected(target);
    setContent(await readSkillFile(skillId, target === mainFile ? undefined : target));
    setDirty(false);
  };

  useEffect(() => {
    if (open && skillId) {
      load();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, skillId]);

  const openFile = async (relativePath: string) => {
    if (!skillId || relativePath === selected) {
      return;
    }
    if (dirty && !window.confirm(l('pages.skill.file.unsavedConfirm'))) {
      return;
    }
    setSelected(relativePath);
    setContent(await readSkillFile(skillId, relativePath === mainFile ? undefined : relativePath));
    setDirty(false);
  };

  const handleSave = async () => {
    if (!skillId || !selected) {
      return;
    }
    await writeSkillFile({ skillId, relativePath: selected, content }, () => {
      setDirty(false);
      load(selected);
    });
  };

  const handleNewFile = () => {
    let value = '';
    Modal.confirm({
      title: l('pages.skill.file.newFile'),
      content: (
        <Input
          placeholder={'references/conventions.md'}
          onChange={(e) => {
            value = e.target.value;
          }}
        />
      ),
      okText: l('button.confirm'),
      cancelText: l('button.cancel'),
      onOk: async () => {
        const relativePath = value.trim();
        if (!relativePath) {
          message.warning(l('pages.skill.file.pathRequired'));
          return Promise.reject();
        }
        if (!/\.(md|txt|json|ya?ml)$/i.test(relativePath)) {
          message.error(l('pages.skill.file.extInvalid'));
          return Promise.reject();
        }
        await writeSkillFile({ skillId: skillId as number, relativePath, content: '' }, () => load(relativePath));
      },
    });
  };

  const handleNewDir = () => {
    let value = '';
    Modal.confirm({
      title: l('pages.skill.file.newDir'),
      content: (
        <Input
          placeholder={'references'}
          onChange={(e) => {
            value = e.target.value;
          }}
        />
      ),
      okText: l('button.confirm'),
      cancelText: l('button.cancel'),
      onOk: async () => {
        const relativePath = value.trim();
        if (!relativePath) {
          message.warning(l('pages.skill.file.pathRequired'));
          return Promise.reject();
        }
        await mkdirSkill({ skillId: skillId as number, relativePath }, () => load(selected));
      },
    });
  };

  const handleDelete = () => {
    if (!skillId || !selected || selected === mainFile) {
      return;
    }
    Modal.confirm({
      title: l('pages.skill.file.removeConfirm'),
      content: selected,
      okButtonProps: { danger: true },
      okText: l('button.confirm'),
      cancelText: l('button.cancel'),
      onOk: async () => {
        await removeSkillFile({ skillId, relativePath: selected }, () => load(mainFile));
      },
    });
  };

  return (
    <Drawer
      title={`${skillName ?? ''} — ${l('pages.skill.file.title')}`}
      width={920}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<FileAddOutlined />} onClick={handleNewFile} disabled={!editable}>
          {l('pages.skill.file.newFile')}
        </Button>
        <Button icon={<FolderAddOutlined />} onClick={handleNewDir} disabled={!editable}>
          {l('pages.skill.file.newDir')}
        </Button>
        <Button
          icon={<DeleteOutlined />}
          danger
          onClick={handleDelete}
          disabled={!editable || selected === mainFile}
        >
          {l('button.delete')}
        </Button>
        <Tooltip title={l('pages.skill.file.saveHint')}>
          <Button type={'primary'} icon={<SaveOutlined />} onClick={handleSave} disabled={!editable || !dirty}>
            {l('button.save')}
          </Button>
        </Tooltip>
        <Typography.Text type={'warning'}>{l('pages.skill.file.noSecret')}</Typography.Text>
      </Space>
      <div style={{ display: 'flex', gap: 12, height: 'calc(100vh - 210px)' }}>
        <div style={{ width: 280, overflow: 'auto', borderRight: '1px solid #f0f0f0', paddingRight: 8 }}>
          {tree.length > 0 ? (
            <Tree
              treeData={toTreeData(tree)}
              defaultExpandAll
              selectedKeys={selected ? [selected] : []}
              onSelect={(keys) => keys[0] && openFile(String(keys[0]))}
            />
          ) : (
            <Empty description={l('pages.skill.file.empty')} />
          )}
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <Typography.Text type={'secondary'} style={{ marginBottom: 6 }}>
            {selected}
          </Typography.Text>
          <TextArea
            value={content}
            onChange={(e) => {
              setContent(e.target.value);
              setDirty(true);
            }}
            disabled={!editable}
            style={{ flex: 1, fontFamily: 'monospace' }}
            autoSize={{ minRows: 20 }}
          />
        </div>
      </div>
    </Drawer>
  );
};

export default SkillFilePanel;
