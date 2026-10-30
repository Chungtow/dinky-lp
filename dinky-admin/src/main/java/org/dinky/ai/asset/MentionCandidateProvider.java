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

package org.dinky.ai.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一类制品的候选来源（{@code @} 候选升级 C1 的注册表扩展点）。
 *
 * <p><b>设计取向：注册表而非 if/else</b>。改造前 {@code buildMentionContext} 里是 8 段硬编码
 * if/else（约 115 行），只有 {@code @kb} 走了 {@code MentionInjector} 注册表。
 * 每加一类制品就要改那段分派代码——这正是候选侧 9 组 provider 与注入侧 8 段分支的成因。
 * 现在所有实现类由 Spring 收集成 {@code List<MentionCandidateProvider>}，
 * 新增制品只需新增一个 {@code @Component}，聚合与端点零改动。
 *
 * <p><b>实现约束（越权红线）</b>：{@link #load()} 必须<b>只返回当前登录用户可见的条目</b>。
 * 各实现一律复用既有可见性方法（{@code SkillService#listVisible}、
 * {@code KbCollectionService#listVisibleBriefs}、已发布语义清单等），
 * 不要在此处自己写 SQL、也不要依赖 {@code TenantContextHolder}
 * （AI 侧存在异步线程，租户上下文会丢）。
 *
 * @since 2026/10/30
 */
public interface MentionCandidateProvider {

    /** 本 provider 负责的制品类型（一个实现只认一类，避免聚合处再判断） */
    AssetType type();

    /**
     * 取当前用户可见的全量候选。
     *
     * <p>只允许「一次取全量」代价可接受的类型实现：skill / doc / semantic（项目级）/ kb。
     * 表与字段<b>不实现</b>（C1 边界，见 {@link AssetType#isPrefetchable()}）。
     */
    List<AssetRef> load();

    /**
     * 选中后的详情（副标题 / 摘要），用于候选浮层展开与 chip 提示。
     *
     * @param code 候选的 {@link AssetRef#getCode()}
     * @return 找不到（或已不可见）返回 {@code null}——<b>详情接口不报错</b>，前端据此静默降级
     */
    default AssetRef detail(String code) {
        return null;
    }

    /**
     * 兜底实现：在 {@link #load()} 结果上做子串匹配。
     *
     * <p>仅供「预取失败 / 条目超阈值」时的服务端搜索模式使用。默认实现足够，
     * 特殊类型（如需要走服务端 SQL 搜索的）可覆盖。
     */
    default List<AssetRef> search(String keyword, int limit) {
        List<AssetRef> all = load();
        if (all == null || all.isEmpty()) {
            return new ArrayList<>();
        }
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<AssetRef> hits = new ArrayList<>();
        for (AssetRef ref : all) {
            String title = ref.getTitle() == null ? "" : ref.getTitle().toLowerCase(Locale.ROOT);
            String code = ref.getCode() == null ? "" : ref.getCode().toLowerCase(Locale.ROOT);
            if (q.isEmpty() || title.contains(q) || code.contains(q)) {
                hits.add(ref);
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return hits;
    }
}
