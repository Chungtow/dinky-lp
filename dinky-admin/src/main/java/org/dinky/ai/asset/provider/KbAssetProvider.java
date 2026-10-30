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
import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.service.KbCollectionService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * 知识库候选（{@code @kb/<code>}）。
 *
 * <p><b>名字存编码、显示用库名</b>：{@code code}（{@code c_} + 8 位 hex）是系统生成且永不变的，
 * 可直接写进 kb-service 的 {@code filter.collection}；显示名可改可重名，只用于展示。
 * 因此这里 `code` 列 = 库编码、`title` 列 = 库名（前端按 title 匹配、按 code 插入）。
 *
 * <p><b>门控</b>：{@code sys.kb.settings.enable} 关闭时不下发——与 {@code @kb} 注入同源。
 *
 * @since 2026/10/30
 */
@Component
@RequiredArgsConstructor
public class KbAssetProvider implements MentionCandidateProvider {

    private final KbCollectionService kbCollectionService;

    @Override
    public AssetType type() {
        return AssetType.KB;
    }

    @Override
    public List<AssetRef> load() {
        if (!SystemConfiguration.getInstances().isKbEnable()) {
            return new ArrayList<>();
        }
        List<AssetRef> refs = new ArrayList<>();
        for (KbCollectionBrief brief : kbCollectionService.listVisibleBriefs()) {
            if (brief == null || StrUtil.isBlank(brief.getCode())) {
                continue;
            }
            refs.add(AssetRef.of(
                    AssetType.KB, brief.getCode(), StrUtil.blankToDefault(brief.getName(), brief.getCode())));
        }
        return refs;
    }

    @Override
    public AssetRef detail(String code) {
        if (!SystemConfiguration.getInstances().isKbEnable()) {
            return null;
        }
        for (KbCollectionBrief brief : kbCollectionService.listVisibleBriefs()) {
            if (brief == null || !StrUtil.equals(brief.getCode(), code)) {
                continue;
            }
            AssetRef ref = AssetRef.of(
                    AssetType.KB, brief.getCode(), StrUtil.blankToDefault(brief.getName(), brief.getCode()));
            ref.setSubtitle(brief.getDescription());
            return ref;
        }
        return null;
    }
}
