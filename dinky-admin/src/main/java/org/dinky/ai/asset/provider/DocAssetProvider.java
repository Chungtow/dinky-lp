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
 * doc（知识文档）候选（{@code @doc/<名>}）。
 *
 * <p>与 {@link SkillAssetProvider} 同源（{@code dinky_skill} 表 + 同一套权限模型），
 * 仅按 {@code asset_type='doc'} 分流；因此共享 3 秒快照，不重复查库。
 *
 * @since 2026/10/30
 */
@Component
@RequiredArgsConstructor
public class DocAssetProvider implements MentionCandidateProvider {

    private final SkillAssetSnapshot snapshot;

    private final SkillService skillService;

    @Override
    public AssetType type() {
        return AssetType.DOC;
    }

    @Override
    public List<AssetRef> load() {
        if (!SystemConfiguration.getInstances().isLlmSkillEnable()) {
            return new ArrayList<>();
        }
        List<AssetRef> refs = new ArrayList<>();
        for (Skill skill : snapshot.list()) {
            if (skill == null || StrUtil.isBlank(skill.getName()) || !SkillAssetSnapshot.isDoc(skill)) {
                continue;
            }
            AssetRef ref = AssetRef.of(AssetType.DOC, skill.getName(), skill.getName());
            // 副标题 = 文档一句话说明（候选面板右侧提示，与 skill 同源）
            ref.setSubtitle(skill.getDescription());
            refs.add(ref);
        }
        return refs;
    }

    @Override
    public AssetRef detail(String code) {
        Skill skill = skillService.findVisibleByName(code);
        if (skill == null || !SkillAssetSnapshot.isDoc(skill)) {
            return null;
        }
        AssetRef ref = AssetRef.of(AssetType.DOC, skill.getName(), skill.getName());
        ref.setSubtitle(skill.getDescription());
        return ref;
    }
}
