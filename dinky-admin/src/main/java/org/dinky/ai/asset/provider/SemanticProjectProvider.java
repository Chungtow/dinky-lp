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
import org.dinky.data.vo.SemanticPublishedAssetVO;
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
            // 副标题 = 版本 + 资产规模：项目名往往很像（dw / dw_dev / dw_uat），
            // 版本与表/指标数是最直接的区分依据
            ref.setSubtitle(StrUtil.format(
                    "v{} · {} table / {} metric",
                    StrUtil.nullToEmpty(project.getVersion()),
                    project.getTableCount(),
                    project.getMetricCount()));
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 单条详情：<b>项目级与资产级都支持</b>（2026-10-31 修复）。
     *
     * <p><b>修的是什么</b>：此前只拿 {@code code} 与<b>项目名</b>全等比较，于是
     * {@code @semantic/<project>/<asset>} 这类<b>合法</b>的资产级句柄一律返回 {@code null}，
     * 编辑器悬停就显示"资源不存在，或当前用户不可见"——假阳性。编辑器补上资产级下钻后，
     * 这个假阳性会直接落在用户刚插入的句柄上，比不给候选更糟。
     *
     * <p><b>支持的三种形态</b>（与 {@code SemanticAssetProvider#resolve} 同源）：
     *
     * <ul>
     *   <li>{@code <project>} —— 项目级（原逻辑）
     *   <li>{@code <project>/<asset>} —— 资产级（表与指标同名时无法区分，属历史短式）
     *   <li>{@code <project>/table|metric/<asset>} —— 长式（AI Chat 在同名时插入的形态）
     * </ul>
     */
    @Override
    public AssetRef detail(String code) {
        if (!hasSemanticPermission()) {
            return null;
        }
        String path = StrUtil.trimToEmpty(code);
        if (StrUtil.isBlank(path)) {
            return null;
        }
        String projectName = StrUtil.subBefore(path, "/", false);
        String rest = StrUtil.subAfter(path, "/", false);
        SemanticPublishedProjectVO project = null;
        for (SemanticPublishedProjectVO item : semanticFileService.listPublishedProjects()) {
            if (item != null && StrUtil.equals(item.getName(), projectName)) {
                project = item;
                break;
            }
        }
        if (project == null) {
            return null;
        }
        if (StrUtil.isBlank(rest)) {
            AssetRef ref = AssetRef.of(AssetType.SEMANTIC, project.getName(), project.getName());
            ref.setSubtitle(StrUtil.format(
                    "v{} · {} table / {} metric",
                    StrUtil.nullToEmpty(project.getVersion()),
                    project.getTableCount(),
                    project.getMetricCount()));
            return ref;
        }
        // 长式去掉中间的 table|metric 段（该段只用于消歧，不进资产名）
        String kind = null;
        String asset = rest;
        String firstSegment = StrUtil.subBefore(rest, "/", false);
        if (StrUtil.equalsAnyIgnoreCase(firstSegment, "table", "metric")) {
            kind = firstSegment.toLowerCase();
            asset = StrUtil.subAfter(rest, "/", false);
        }
        if (StrUtil.isBlank(asset)) {
            return null;
        }
        for (SemanticPublishedAssetVO item : semanticFileService.listPublishedAssets(projectName)) {
            if (item == null || !StrUtil.equals(item.getName(), asset)) {
                continue;
            }
            String assetType = StrUtil.nullToEmpty(item.getType());
            if (kind != null && !assetType.toLowerCase().startsWith(kind)) {
                continue;
            }
            AssetRef ref = AssetRef.of(AssetType.SEMANTIC, path, item.getName());
            String oneLiner = StrUtil.nullToEmpty(item.getOneLiner());
            ref.setSubtitle(StrUtil.format(
                    "{} · {} · v{}{}",
                    projectName,
                    "metric".equalsIgnoreCase(kind) || assetType.equalsIgnoreCase("METRIC") ? "指标" : "表",
                    StrUtil.nullToEmpty(project.getVersion()),
                    StrUtil.isBlank(oneLiner) ? "" : " · " + oneLiner));
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
