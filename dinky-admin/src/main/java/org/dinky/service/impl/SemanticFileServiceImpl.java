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

import org.dinky.data.dto.SemanticFileSaveDTO;
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
}
