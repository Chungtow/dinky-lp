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

package org.dinky.service;

import org.dinky.data.dto.SemanticEntryOpDTO;
import org.dinky.data.dto.SemanticFileSaveDTO;
import org.dinky.data.dto.SemanticRenameDTO;
import org.dinky.data.vo.SemanticFileContentVO;
import org.dinky.data.vo.SemanticFileNode;
import org.dinky.data.vo.SemanticProjectVO;

import java.util.List;

/**
 * dbt 语义项目在线读写（P2）。
 *
 * <p>只做「看 + 改 + 存」：建项目/骨架/发布/回滚都不在这里（仍由 JupyterLab 侧负责），
 * 因此本服务不新增任何数据库表。
 */
public interface SemanticFileService {

    /** 语义根下的一级项目（跳过 _published/_templates） */
    List<SemanticProjectVO> listProjects();

    /** 项目内文件树（meta.json / target 标记只读） */
    List<SemanticFileNode> listFiles(String project);

    /** 读文件（含 mtime，供乐观锁） */
    SemanticFileContentVO readFile(String project, String path);

    /** 保存文件，返回写入后的新 mtime；mtime 冲突抛 {@code semantic.write.conflict} */
    long saveFile(SemanticFileSaveDTO dto);

    /** 新建目录（父目录必须已存在），返回规范化后的相对路径 */
    String createDir(SemanticEntryOpDTO dto);

    /** 改名（同目录内），目标已存在则拒绝，不覆盖 */
    void rename(SemanticRenameDTO dto);

    /** 软删除到项目内回收站，返回回收站内的相对路径（供恢复使用） */
    String deleteEntry(SemanticEntryOpDTO dto);
}
