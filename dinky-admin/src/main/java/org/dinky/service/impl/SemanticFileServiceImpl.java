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

import org.dinky.data.dto.SemanticEntryOpDTO;
import org.dinky.data.dto.SemanticFileSaveDTO;
import org.dinky.data.dto.SemanticRenameDTO;
import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.vo.SemanticFileContentVO;
import org.dinky.data.vo.SemanticFileNode;
import org.dinky.data.vo.SemanticProjectVO;
import org.dinky.resource.semantic.SemanticFileEntry;
import org.dinky.resource.semantic.SemanticHdfsClient;
import org.dinky.service.SemanticFileService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** {@link SemanticFileService} 实现：薄封装 {@link SemanticHdfsClient}，只做 VO 转换 */
@Slf4j
@Service
public class SemanticFileServiceImpl implements SemanticFileService {

    private final SemanticHdfsClient client = SemanticHdfsClient.getInstance();

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
