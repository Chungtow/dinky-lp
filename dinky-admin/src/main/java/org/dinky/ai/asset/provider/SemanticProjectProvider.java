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

package org.dinky.ai.asset.provider;

import org.dinky.ai.asset.AssetRef;
import org.dinky.ai.asset.AssetType;
import org.dinky.ai.asset.MentionCandidateProvider;
import org.dinky.data.constant.PermissionConstants;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.vo.SemanticPublishedProjectVO;
import org.dinky.service.SemanticFileService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 语义项目候选（{@code @semantic/<project>}，C1 只预取<b>项目级</b>）。
 *
 * <p><b>为什么不预取项目内资产</b>：项目内是 {@code table}/{@code metric} 明细，需按项目逐个调用
 * {@code listPublishedAssets(project)}（N 个项目 = N 次 HDFS 读），且总量随项目数线性增长。
 * 因此保持既有交互：候选只到项目层，用户敲 {@code @semantic/<project>/} 后再懒加载该项目的资产
 * （前端 {@code semanticAssets} 已有这套逻辑，本批不动）。
 *
 * <p><b>可见性</b>：开关 {@code sys.semantic.settings.enable} + 权限
 * {@code datastudio:semantic:view} 双门控——与 {@code AiChatServiceImpl} 注入语义清单时同源，
 * 避免"候选能选、注入被拒"的错位（这正是本批缺陷 D1 的修法）。
 *
 * @since 2026/10/30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticProjectProvider implements MentionCandidateProvider {

    private final SemanticFileService semanticFileService;

    @Override
    public AssetType type() {
        return AssetType.SEMANTIC;
    }

    @Override
    public List<AssetRef> load() {
        SystemConfiguration config = SystemConfiguration.getInstances();
        if (!Boolean.TRUE.equals(config.getSemanticEnable().getValue())) {
            return new ArrayList<>();
        }
        if (!hasSemanticPermission()) {
            return new ArrayList<>();
        }
        List<AssetRef> refs = new ArrayList<>();
        for (SemanticPublishedProjectVO project : semanticFileService.listPublishedProjects()) {
            if (project == null || StrUtil.isBlank(project.getName())) {
                continue;
            }
            AssetRef ref = AssetRef.of(AssetType.SEMANTIC, project.getName(), project.getName());
            refs.add(ref);
        }
        return refs;
    }

    @Override
    public AssetRef detail(String code) {
        if (!hasSemanticPermission()) {
            return null;
        }
        for (SemanticPublishedProjectVO project : semanticFileService.listPublishedProjects()) {
            if (project == null || !StrUtil.equals(project.getName(), code)) {
                continue;
            }
            AssetRef ref = AssetRef.of(AssetType.SEMANTIC, project.getName(), project.getName());
            ref.setSubtitle(StrUtil.format(
                    "v{} · {} table / {} metric",
                    StrUtil.nullToEmpty(project.getVersion()),
                    project.getTableCount(),
                    project.getMetricCount()));
            return ref;
        }
        return null;
    }

    private boolean hasSemanticPermission() {
        try {
            return StpUtil.hasPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW);
        } catch (Exception e) {
            // 未登录 / 上下文丢失：按"无权限"处理（不降级、不放行）
            log.debug("@semantic 候选权限判定失败，按无权限处理: {}", e.getMessage());
            return false;
        }
    }
}
