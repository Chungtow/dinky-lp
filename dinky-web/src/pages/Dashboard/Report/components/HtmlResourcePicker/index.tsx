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

import { MAX_REPORT_SIZE, HtmlResourceData } from '@/pages/Dashboard/Report/data';
import { getHtmlResources } from '@/pages/Dashboard/Report/service';
import { l } from '@/utils/intl';
import { Empty, Input, Modal, Spin, Tag, Tree } from 'antd';
import type { DataNode } from 'antd/es/tree';
import React, { useCallback, useEffect, useMemo, useState } from 'react';

type HtmlResourcePickerProps = {
  open: boolean;
  onCancel: () => void;
  onSelected: (resource: HtmlResourceData) => void;
};

/** 递归拍平，便于按 id 反查选中项 */
const flatten = (nodes: HtmlResourceData[] | undefined, acc: HtmlResourceData[] = []) => {
  (nodes ?? []).forEach((node) => {
    acc.push(node);
    flatten(node.children, acc);
  });
  return acc;
};

/** 按关键字过滤：命中叶子则保留其祖先，命中目录则整枝保留 */
const filterTree = (nodes: HtmlResourceData[], keyword: string): HtmlResourceData[] => {
  const kw = keyword.trim().toLowerCase();
  if (!kw) {
    return nodes;
  }
  const walk = (list: HtmlResourceData[]): HtmlResourceData[] =>
    list.reduce<HtmlResourceData[]>((result, node) => {
      const hit =
        (node.fileName ?? '').toLowerCase().includes(kw) ||
        (node.fullName ?? '').toLowerCase().includes(kw);
      const children = walk(node.children ?? []);
      if (hit || children.length) {
        result.push({ ...node, children: hit && !children.length ? node.children : children });
      }
      return result;
    }, []);
  return walk(nodes);
};

const isHtml = (resource: HtmlResourceData) => {
  const fullName = (resource.fullName ?? '').toLowerCase();
  return fullName.endsWith('.html') || fullName.endsWith('.htm');
};

/**
 * HTML 资源选择器
 *
 * <p>只允许选文件（目录仅用于展开）：后端 `/api/report/htmlResources` 已过滤为「目录 + .html/.htm」，
 * 这里再兜一层，避免把非 HTML 文件绑成报表。
 */
const HtmlResourcePicker: React.FC<HtmlResourcePickerProps> = ({ open, onCancel, onSelected }) => {
  const [loading, setLoading] = useState(false);
  const [resources, setResources] = useState<HtmlResourceData[]>([]);
  const [keyword, setKeyword] = useState('');
  const [selectedKey, setSelectedKey] = useState<number | undefined>();

  const queryResources = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getHtmlResources();
      setResources((result?.data as HtmlResourceData[]) ?? []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      setKeyword('');
      setSelectedKey(undefined);
      queryResources();
    }
  }, [open, queryResources]);

  const dict = useMemo(() => {
    const map = new Map<number, HtmlResourceData>();
    flatten(resources).forEach((node) => map.set(node.id, node));
    return map;
  }, [resources]);

  const treeData = useMemo(() => {
    const toNode = (node: HtmlResourceData): DataNode => ({
      key: node.id,
      title: (
        <span>
          {node.fileName ?? node.fullName}
          {node.isDirectory ? null : (
            <Tag style={{ marginLeft: 8 }} color={isHtml(node) ? 'blue' : 'default'}>
              {node.size != null ? `${(node.size / 1024).toFixed(1)} KB` : '-'}
            </Tag>
          )}
        </span>
      ),
      // 目录可展开但不可选；非 HTML 文件同样不可选（后端已过滤，这里兜底）
      selectable: !node.isDirectory && isHtml(node),
      isLeaf: !node.isDirectory,
      children: node.children?.length ? node.children.map(toNode) : undefined
    });
    return filterTree(resources, keyword).map(toNode);
  }, [resources, keyword]);

  const handleOk = () => {
    const resource = selectedKey != null ? dict.get(selectedKey) : undefined;
    if (!resource) {
      return;
    }
    if (resource.size != null && resource.size > MAX_REPORT_SIZE) {
      Modal.warning({ title: l('dashboard.report.resourceTooLarge') });
      return;
    }
    onSelected(resource);
  };

  return (
    <Modal
      open={open}
      title={l('dashboard.report.selectResource')}
      onCancel={onCancel}
      onOk={handleOk}
      okButtonProps={{ disabled: selectedKey == null }}
      width={560}
      destroyOnClose
    >
      <Input.Search
        allowClear
        placeholder={l('dashboard.report.searchResource')}
        style={{ marginBottom: 8 }}
        onChange={(e) => setKeyword(e.target.value)}
      />
      <Spin spinning={loading}>
        <div style={{ maxHeight: 360, overflow: 'auto', minHeight: 160 }}>
          {treeData.length ? (
            <Tree
              treeData={treeData}
              selectedKeys={selectedKey != null ? [selectedKey] : []}
              onSelect={(keys) => setSelectedKey(keys.length ? Number(keys[0]) : undefined)}
              defaultExpandAll
            />
          ) : (
            <Empty description={l('dashboard.report.noHtmlResource')} />
          )}
        </div>
      </Spin>
    </Modal>
  );
};

export default HtmlResourcePicker;
