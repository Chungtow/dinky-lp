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
import org.dinky.data.model.Skill;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.service.SkillService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * skill 候选（{@code @skill/<名>}）。
 *
 * <p><b>门控</b>：沿用 {@code sys.llm.settings.skillEnable}——关闭时前端不再展示 skill 候选，
 * 后端也就没必要下发（否则用户选中一个用不了的句柄，注入阶段才失败）。
 *
 * <p><b>可见性</b>：直接复用 {@code SkillService#listVisible()}（本人所有 + 同租户共享 + 公开，
 * 仅 enabled），不在此处重写 SQL。
 *
 * @since 2026/10/30
 */
@Component
@RequiredArgsConstructor
public class SkillAssetProvider implements MentionCandidateProvider {

    private final SkillAssetSnapshot snapshot;

    private final SkillService skillService;

    @Override
    public AssetType type() {
        return AssetType.SKILL;
    }

    @Override
    public List<AssetRef> load() {
        if (!SystemConfiguration.getInstances().isLlmSkillEnable()) {
            return new ArrayList<>();
        }
        List<AssetRef> refs = new ArrayList<>();
        for (Skill skill : snapshot.list()) {
            if (skill == null || StrUtil.isBlank(skill.getName())) {
                continue;
            }
            // doc 由 DocAssetProvider 负责，这里跳过，避免同一资产出现在两个类型下
            if (SkillAssetSnapshot.isDoc(skill)) {
                continue;
            }
            refs.add(AssetRef.of(AssetType.SKILL, skill.getName(), skill.getName()));
        }
        return refs;
    }

    @Override
    public AssetRef detail(String code) {
        Skill skill = skillService.findVisibleByName(code);
        if (skill == null) {
            return null;
        }
        AssetRef ref = AssetRef.of(AssetType.SKILL, skill.getName(), skill.getName());
        ref.setSubtitle(skill.getDescription());
        return ref;
    }
}
