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

import org.dinky.data.model.Skill;
import org.dinky.service.SkillService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * skill / doc 的共享快照（{@code @} 候选升级 C1）。
 *
 * <p><b>为什么需要它</b>：skill 与 doc 共用 {@code dinky_skill} 表、同一个
 * {@code SkillService#listVisible()}，只是按 {@code asset_type} 分流。若两个 provider 各查一次，
 * 每次候选聚合都要跑两遍同样的可见性 SQL（含 owner / tenant / visibility 三段条件）。
 * 这里做<b>3 秒</b>的进程内快照：一次聚合只查一次库，同时保证"刚建的 skill"最多 3 秒后可见
 * （新建资产另有前端乐观插入兜底）。
 *
 * <p>TTL 刻意取得比候选缓存（60s）短得多——它是"同一次聚合内的去重"，不是"对外缓存"，
 * 不承担新鲜度责任。
 *
 * @since 2026/10/30
 */
@Component
@RequiredArgsConstructor
public class SkillAssetSnapshot {

    private static final long TTL_MILLIS = 3_000L;

    private final SkillService skillService;

    private volatile List<Skill> cache;

    private volatile long expireAt;

    /** 当前用户可见的 skill / doc 原始行（已按可见性裁剪，未按 assetType 分流） */
    public List<Skill> list() {
        long now = System.currentTimeMillis();
        List<Skill> local = cache;
        if (local != null && now < expireAt) {
            return local;
        }
        List<Skill> fresh = skillService.listVisible();
        List<Skill> snapshot = fresh == null ? new ArrayList<>() : fresh;
        cache = snapshot;
        expireAt = now + TTL_MILLIS;
        return snapshot;
    }

    /** 是否属于 doc 类（{@code asset_type='doc'}）；其余（含历史空值）一律视为 skill */
    public static boolean isDoc(Skill skill) {
        return skill != null && "doc".equalsIgnoreCase(skill.getAssetType());
    }

    /** 清空快照（保存 / 删除资产后由写路径调用；C1 仅内部使用，未接钩子时靠 TTL 自然过期） */
    public void invalidate() {
        cache = null;
        expireAt = 0L;
    }
}
