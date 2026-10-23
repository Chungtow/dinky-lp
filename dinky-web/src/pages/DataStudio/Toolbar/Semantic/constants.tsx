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

import { PermissionConstants } from '@/types/Public/constants';
import { l } from '@/utils/intl';
import {
  CommentOutlined,
  DeleteOutlined,
  EditOutlined,
  FileAddOutlined,
  FolderAddOutlined,
  ReloadOutlined
} from '@ant-design/icons';

/** 语义树右键菜单项 key（与 handleMenuClick 的 switch 一一对应） */
export enum SemanticRightMenuKey {
  CREATE_FILE = 'createFile',
  CREATE_DIR = 'createDir',
  RENAME = 'rename',
  DELETE = 'delete',
  FEEDBACK = 'feedback',
  REFRESH = 'refresh'
}

export type SemanticMenuContext = {
  /** 是否目录（"新建"只在目录上可用） */
  isDir: boolean;
  /** 是否只读（meta.json / target / _versions / .trash）：只读项一律禁用改结构 */
  readOnly: boolean;
};

/**
 * 语义树右键菜单。
 *
 * <p>与 Resource tab 同构：每项带 {@code path} 权限码，由调用方用
 * {@code AuthorizedObject} 过滤；{@code disabled} 只表达"当前上下文不可用"，
 * 不表达权限。**项目根节点不挂菜单**（避免"删整个项目"），由调用方保证。
 */
export const getSemanticContextMenu = ({ isDir, readOnly }: SemanticMenuContext) => {
  const createDisabled = !isDir || readOnly;
  return [
    {
      key: SemanticRightMenuKey.CREATE_FILE,
      icon: <FileAddOutlined />,
      label: l('datastudio.semantic.ctx.createFile'),
      disabled: createDisabled,
      path: PermissionConstants.DATASTUDIO_SEMANTIC_EDIT
    },
    {
      key: SemanticRightMenuKey.CREATE_DIR,
      icon: <FolderAddOutlined />,
      label: l('datastudio.semantic.ctx.createDir'),
      disabled: createDisabled,
      path: PermissionConstants.DATASTUDIO_SEMANTIC_EDIT
    },
    { type: 'divider' as const },
    {
      key: SemanticRightMenuKey.RENAME,
      icon: <EditOutlined />,
      label: l('datastudio.semantic.ctx.rename'),
      disabled: readOnly,
      path: PermissionConstants.DATASTUDIO_SEMANTIC_EDIT
    },
    {
      key: SemanticRightMenuKey.DELETE,
      icon: <DeleteOutlined />,
      label: l('datastudio.semantic.ctx.delete'),
      disabled: readOnly,
      path: PermissionConstants.DATASTUDIO_SEMANTIC_EDIT
    },
    { type: 'divider' as const },
    {
      // P4 一期：「这条口径不对」的出口。只对**文件**开放（目录没有口径可评），
      // 只读条目（meta.json / target）也不让提——那不是语义资产。
      // 权限沿用 view：能看语义项目的人都能提，否则反馈收不上来。
      key: SemanticRightMenuKey.FEEDBACK,
      icon: <CommentOutlined />,
      label: l('datastudio.semantic.ctx.feedback'),
      disabled: isDir || readOnly,
      path: PermissionConstants.DATASTUDIO_SEMANTIC_VIEW
    },
    { type: 'divider' as const },
    {
      key: SemanticRightMenuKey.REFRESH,
      icon: <ReloadOutlined />,
      label: l('datastudio.semantic.refresh'),
      disabled: false
    }
  ];
};
