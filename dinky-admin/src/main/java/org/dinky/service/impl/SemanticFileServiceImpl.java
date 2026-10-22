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

package org.dinky.service.impl;

import org.dinky.ai.semantic.SemanticAssetProvider;
import org.dinky.ai.semantic.SemanticAssetRef;
import org.dinky.data.dto.SemanticEntryOpDTO;
import org.dinky.data.dto.SemanticFileSaveDTO;
import org.dinky.data.dto.SemanticRenameDTO;
import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.vo.SemanticFileContentVO;
import org.dinky.data.vo.SemanticFileNode;
import org.dinky.data.vo.SemanticProjectVO;
import org.dinky.data.vo.SemanticPublishedAssetVO;
import org.dinky.data.vo.SemanticPublishedProjectVO;
import org.dinky.resource.semantic.SemanticFileEntry;
import org.dinky.resource.semantic.SemanticHdfsClient;
import org.dinky.service.SemanticFileService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** {@link SemanticFileService} 实现：薄封装 {@link SemanticHdfsClient}，只做 VO 转换 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticFileServiceImpl implements SemanticFileService {

    private final SemanticHdfsClient client = SemanticHdfsClient.getInstance();

    /** P3b：已发布资产的只读来源（候选接口与 AI 注入共用同一实现，避免两套口径） */
    private final SemanticAssetProvider semanticAssetProvider;

    @Override
    public List<SemanticProjectVO> listProjects() {
        List<SemanticProjectVO> projects = new ArrayList<>();
        for (String name : client.listProjects()) {
            SemanticProjectVO vo = new SemanticProjectVO();
            vo.setName(name);
            try {
                vo.setFileCount(client.listFiles(name).size());
            } catch (RuntimeException e) {
                // 单个项目读失败（权限/超限）不应让整个项目列表 500
                log.warn("list semantic project files failed, project={}", name, e);
                vo.setFileCount(0);
            }
            vo.setLastModified(client.projectLastModified(name));
            projects.add(vo);
        }
        return projects;
    }

    @Override
    public List<SemanticFileNode> listFiles(String project) {
        List<SemanticFileNode> nodes = new ArrayList<>();
        for (SemanticFileEntry entry : client.listFiles(project)) {
            SemanticFileNode node = new SemanticFileNode();
            node.setPath(entry.getPath());
            node.setIsDir(entry.isDirectory());
            node.setSize(entry.getSize());
            node.setReadOnly(entry.isReadOnly());
            node.setLastModified(entry.getModifiedAt());
            nodes.add(node);
        }
        return nodes;
    }

    @Override
    public SemanticFileContentVO readFile(String project, String path) {
        SemanticHdfsClient.SemanticFile file = client.read(project, path);
        SemanticFileContentVO vo = new SemanticFileContentVO();
        vo.setPath(file.getPath());
        vo.setContent(file.getContent());
        vo.setSize(file.getSize());
        vo.setModifiedAt(file.getModifiedAt());
        return vo;
    }

    @Override
    public long saveFile(SemanticFileSaveDTO dto) {
        long mtime = client.write(dto.getProject(), dto.getPath(), dto.getContent(), dto.getBaseMtime());
        log.info(
                "semantic file saved, project={}, path={}, baseMtime={}, newMtime={}",
                dto.getProject(),
                dto.getPath(),
                dto.getBaseMtime(),
                mtime);
        return mtime;
    }

    @Override
    public String createDir(SemanticEntryOpDTO dto) {
        String rel = client.normalizeRel(dto.getPath());
        client.mkdirs(dto.getProject(), rel);
        log.info("semantic dir created, project={}, path={}", dto.getProject(), rel);
        return rel;
    }

    @Override
    public void rename(SemanticRenameDTO dto) {
        String from = client.normalizeRel(dto.getPath());
        String newName = dto.getNewName() == null ? "" : dto.getNewName().trim();
        if (!isSingleSegment(newName)) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        int idx = from.lastIndexOf('/');
        // 只改最后一段（同目录改名）；父目录保持原样
        String to = idx < 0 ? newName : from.substring(0, idx + 1) + newName;
        client.renameInProject(dto.getProject(), from, to, dto.getBaseMtime());
        log.info(
                "semantic entry renamed, project={}, from={}, to={}, isDir={}",
                dto.getProject(),
                from,
                to,
                dto.getIsDir());
    }

    @Override
    public String deleteEntry(SemanticEntryOpDTO dto) {
        String rel = client.normalizeRel(dto.getPath());
        String trashRel =
                client.delete(dto.getProject(), rel, Boolean.TRUE.equals(dto.getRecursive()), dto.getBaseMtime());
        log.info(
                "semantic entry moved to trash, project={}, path={}, trash={}, keepDays={}",
                dto.getProject(),
                rel,
                trashRel,
                SemanticHdfsClient.TRASH_KEEP_DAYS);
        return trashRel;
    }

    // ------------------------------------------------------------------ P3b：AI 候选（只读）

    @Override
    public List<SemanticPublishedProjectVO> listPublishedProjects() {
        List<SemanticPublishedProjectVO> projects = new ArrayList<>();
        for (String name : client.listProjects()) {
            SemanticAssetProvider.Meta meta;
            try {
                meta = semanticAssetProvider.meta(name);
            } catch (RuntimeException e) {
                // 单项目读失败不应让整个候选列表 500（与 listProjects 的既有策略一致）
                log.warn("read published meta failed, project={}", name, e);
                continue;
            }
            if (meta == null) {
                // 从未发布：不作为候选（AI 只能引用不可变快照）
                continue;
            }
            SemanticPublishedProjectVO vo = new SemanticPublishedProjectVO();
            vo.setName(name);
            vo.setVersion(meta.getVersion());
            vo.setIndexState(meta.getIndexState());
            vo.setPublishedAt(meta.getPublishedAt());
            try {
                int tables = 0;
                int metrics = 0;
                for (SemanticAssetRef ref : semanticAssetProvider.enumerate(name)) {
                    if (SemanticAssetRef.TYPE_METRIC.equals(ref.getType())) {
                        metrics++;
                    } else {
                        tables++;
                    }
                }
                vo.setTableCount(tables);
                vo.setMetricCount(metrics);
            } catch (RuntimeException e) {
                log.warn("enumerate published assets failed, project={}", name, e);
            }
            projects.add(vo);
        }
        return projects;
    }

    @Override
    public List<SemanticPublishedAssetVO> listPublishedAssets(String project) {
        List<SemanticPublishedAssetVO> assets = new ArrayList<>();
        for (SemanticAssetRef ref : semanticAssetProvider.enumerate(project)) {
            SemanticPublishedAssetVO vo = new SemanticPublishedAssetVO();
            vo.setName(ref.getName());
            vo.setType(ref.getType());
            vo.setOneLiner(ref.getOneLiner());
            assets.add(vo);
        }
        return assets;
    }

    /** 名称必须是单段：非空、不含路径分隔符、不是 {@code .}/{@code ..}、不超长 */
    private boolean isSingleSegment(String name) {
        return !name.isEmpty()
                && !name.contains("/")
                && !name.contains("\\")
                && !".".equals(name)
                && !"..".equals(name)
                && name.length() <= 128;
    }
}
