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

import org.dinky.data.dto.KbCollectionSaveDTO;
import org.dinky.data.dto.KbGrantSaveDTO;
import org.dinky.data.model.KbCollection;
import org.dinky.data.model.KbCollectionGrant;
import org.dinky.data.vo.KbOrphanVO;

import java.util.List;

/**
 * 知识库管理（批次 D）：建库、改名、启停、授权、未纳管库发现。
 *
 * <p><b>与 {@link KbCollectionService} 的分工</b>：那边只回答"谁能看见/谁能推"（检索链路要用，
 * 必须极简且无副作用）；这边是<b>管理动作</b>，有校验、有审计、有"属主/超管"判定。
 * 两者共用同一张表，但绝不能让检索链路依赖本接口——否则管理逻辑一改就影响对话。
 *
 * <p><b>管理权的三条线</b>（刻意分开，避免一处放行处处放行）：
 * <ol>
 *   <li><b>看/搜</b>：{@code is_public} 或属主 或 grant(view)；</li>
 *   <li><b>往里推</b>：属主 或 grant(write)；</li>
 *   <li><b>改配置/授权</b>：<b>属主或超管</b>——有 write 授权的人能写内容，但改不了别人的库设置。</li>
 * </ol>
 *
 * @since 2026/10/28
 */
public interface KbAdminService {

    /**
     * 我管理的库（属主库；超管为全部）。<b>不含</b>仅被授权 view/write 的库——
     * 那些库的配置不归被授权者管。
     */
    List<KbCollection> listManageable();

    /** 新建（{@code id} 为空）或编辑：{@code code} 永不参与，名称同租户唯一 */
    KbCollection saveCollection(KbCollectionSaveDTO dto);

    /** kb-service 有、Dinky 未纳管的库（超管专用：它天然有全局视野） */
    List<KbOrphanVO> listOrphans();

    /**
     * 纳管一个既有库：<b>{@code code} 原样沿用</b>（重编码会让检索对不上，D-K12）。
     *
     * <p>默认 {@code is_public=false}：外部系统（如 IMA）的内容进 AI 语料属于跨系统风险，
     * 必须先只授给个别租户验证（立项项 #5）。
     */
    KbCollection adopt(String code, String name, String description, Boolean isPublic);

    /** 某库的授权清单（属主或超管） */
    List<KbCollectionGrant> listGrants(Long collectionId);

    /** 批量新增授权（按租户/角色/用户），重复的跳过而不是报错 */
    int addGrants(Long collectionId, KbGrantSaveDTO dto);

    /** 撤销一条授权 */
    void deleteGrant(Long grantId);
}
