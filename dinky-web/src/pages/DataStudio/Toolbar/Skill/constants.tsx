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
import {
  CloudUploadOutlined,
  DeleteOutlined,
  EditOutlined,
  FileAddOutlined,
  FolderAddOutlined,
  ReloadOutlined,
  SettingOutlined
} from '@ant-design/icons';
import React from 'react';

/** 技能树右键菜单项（批次 F） */
export enum SkillRightMenuKey {
  CREATE_FILE = 'createFile',
  CREATE_DIR = 'createDir',
  RENAME = 'rename',
  DELETE = 'delete',
  /** 把该文件正文推进知识库（与语义树同一套推送弹窗） */
  PUSH = 'push',
  MANAGE = 'manage',
  REFRESH = 'refresh'
}

export type SkillMenuContext = {
  /** 是否目录（"新建/重命名文件"只在文件上可用，"新建"只在目录/skill 根上可用） */
  isDir: boolean;
  /** 当前用户是否为该 skill 的属主（skill 是**属主独占写**，不看权限点） */
  editable: boolean;
  /** 是否主文件（SKILL.md / DOC.md）：主文件不可删除、不可重命名（资产本体） */
  mainFile?: boolean;
  /** 是否有知识库写权限（`registration:kb:write`）：决定"推送到知识库"是否可点 */
  canPush?: boolean;
};

/**
 * 技能树右键菜单（批次 F）。
 *
 * <p><b>与语义树的本质差异</b>：语义层的写权限是权限点（`datastudio:semantic:edit`），
 * 而 skill 是**属主独占写**（后端 `checkOwner`）。所以结构类操作（新建/重命名/删除）的
 * {@code disabled} 判的是 {@code editable}，不是权限点——否则会出现"菜单可点、后端必拒"的假可用。
 * 唯一例外是"推送到知识库"：它写的是<b>知识库</b>而不是 skill，权限口径是 `kb:write`。
 *
 * <p><b>主文件的边界</b>：{@code SKILL.md} / {@code DOC.md} 是资产本体（元数据、版本号、
 * frontmatter 校验都挂在它上面），所以**不可删除、不可重命名**；要删整个资产去注册中心。
 *
 * <p><b>刻意不提供的项</b>：新建 skill、删除 skill、改可见性。这些基础操作留在注册中心
 * （创建表单、可见性三档、`skill:share` 额外校验），这里只做"编辑既有内容"，
 * 需要管理时给一个跳转（{@link SkillRightMenuKey.MANAGE}）。
 */
export const getSkillContextMenu = ({
  isDir,
  editable,
  mainFile,
  canPush = false
}: SkillMenuContext) => {
  const createDisabled = !editable || !isDir;
  const isMain = Boolean(mainFile);
  return [
    {
      key: SkillRightMenuKey.CREATE_FILE,
      icon: <FileAddOutlined />,
      label: l('datastudio.skill.ctx.createFile'),
      disabled: createDisabled
    },
    {
      key: SkillRightMenuKey.CREATE_DIR,
      icon: <FolderAddOutlined />,
      label: l('datastudio.skill.ctx.createDir'),
      disabled: createDisabled
    },
    { type: 'divider' as const },
    {
      key: SkillRightMenuKey.RENAME,
      icon: <EditOutlined />,
      // 只改文件名不换目录（后端同样拒绝含 / 的新名）；主文件名固定，不允许改
      label: l('datastudio.skill.ctx.rename'),
      disabled: !editable || isDir || isMain
    },
    {
      key: SkillRightMenuKey.DELETE,
      icon: <DeleteOutlined />,
      // 目录可以删（后端递归删除）；主文件是资产本体，删它等于绕过注册中心的受控删除
      label: l('datastudio.skill.ctx.delete'),
      disabled: !editable || isMain
    },
    { type: 'divider' as const },
    {
      key: SkillRightMenuKey.PUSH,
      icon: <CloudUploadOutlined />,
      label: l('datastudio.skill.ctx.push'),
      // 目录没有正文；推送写的是知识库，故按 kb:write 判定（与语义树同口径）
      disabled: isDir || !canPush
    },
    { type: 'divider' as const },
    {
      key: SkillRightMenuKey.MANAGE,
      icon: <SettingOutlined />,
      label: l('datastudio.skill.ctx.manage'),
      // 创建 / 可见性 / 删除整库都在注册中心，这里只负责带路
      disabled: false
    },
    {
      key: SkillRightMenuKey.REFRESH,
      icon: <ReloadOutlined />,
      label: l('datastudio.skill.refresh'),
      disabled: false
    }
  ];
};
