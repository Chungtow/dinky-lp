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

import org.dinky.context.TenantContextHolder;
import org.dinky.data.vo.MentionCandidateVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @} 候选聚合服务（{@code @} 候选升级 C1）。
 *
 * <p><b>它在解决什么问题</b>：改造前前端浮层有 9 组 provider，按前缀分派、每输入一个字符就打一次后端，
 * 且是"按类型分派的多次异构查询"。现在收敛成<b>一个端点 + 一份按人裁剪的快照</b>：
 * 打开浮层取一次（命中 ETag 则 304），之后每个按键都在浏览器本地匹配，后端零请求。
 *
 * <p><b>缓存 key 为什么必须含租户与用户</b>（越权红线）：快照是<b>按人裁剪后的结果</b>，
 * 跨用户复用等于把 A 的可见资产下发给 B。这里把 {@code tenantId + userId + 类型集合} 全部放进 key，
 * 粒度比"租户 + 角色指纹"更细（同一用户角色变更也不会串号）。键里的用户来自
 * {@code StpUtil}（请求线程），租户来自 {@link TenantContextHolder}（请求线程），
 * 与各 provider 内部使用的可见性口径一致。
 *
 * <p><b>为什么用进程内 Map 而不是 Caffeine / Redis</b>：Dinky 是单实例，2 万条量级、
 * TTL 60s、每用户一条，ConcurrentHashMap 足够；引入中间件的收益为零（评估 §4.1/§4.4）。
 *
 * <p><b>两种模式</b>：{@code prefetch}（无 q，返回全量列式快照）与 {@code search}
 * （有 q，在<b>同一份快照</b>上做服务端过滤，用于条目超阈值或前端降级）。二者共用同一份缓存，
 * 因此"降级"不增加数据库压力，只增加一次 HTTP 往返。
 *
 * @since 2026/10/30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentionCandidateService {

    /** 快照 TTL：60s 内重复打开浮层不再查库（新建资产另有前端乐观插入兜底） */
    private static final long CACHE_TTL_MILLIS = 60_000L;

    /** 快照条目上限：超过即整体淘汰（本项目实际为百级~千级，仅作兜底） */
    private static final int MAX_CACHE_ENTRIES = 200;

    /** 单次预取条目上限：万级封顶，避免异常数据把响应撑爆（前端阈值见评估 §3.2） */
    private static final int MAX_PREFETCH_ITEMS = 20_000;

    /** 搜索模式的默认条数与硬上限 */
    private static final int DEFAULT_SEARCH_LIMIT = 30;

    private static final int MAX_SEARCH_LIMIT = 200;

    /**
     * 触发"表名实时搜索"的最小关键词长度。
     *
     * <p>1 个字符几乎命中全部表（还要连库），既慢又没有区分度——这正是 C1 反对"每键一请求"的原因，
     * 所以阈值卡在 2 个字符（实施计划 C1.5 判据）。
     */
    private static final int MIN_TABLE_KEYWORD = 2;

    /**
     * 副标题列的条目阈值：超过则整列留空。
     *
     * <p>候选总量在百级~千级时，副标题（库名/描述）是"能不能选中"的关键信息；
     * 但若某天 doc 涨到万级，副标题会让载荷与常驻内存翻倍——那时退回
     * "选中后再拉 {@code /mention/detail}"。阈值取 2000（约 2×100B×2000 ≈ 0.4MB）。
     */
    private static final int MAX_SUB_ITEMS = 2_000;

    private final List<MentionCandidateProvider> providers;

    /**
     * C1.5 轻量表名搜索（2026-10-31）：表是万级、{@code prefetchable=false}，不进快照；
     * 只在<b>搜索模式</b>（{@code ?q=}）且关键词 ≥2 字符时按需实时查（内部带 5 分钟缓存 + 超时）。
     */
    private final TableNameSearchService tableNameSearchService;

    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * 取候选（预取 / 搜索）。
     *
     * @param typesCsv 逗号分隔的类型串（{@code skill,doc,semantic,kb}）；空表示全类型
     * @param q        关键词；空 = 预取模式
     * @param limit    搜索模式条数（预取模式忽略）
     * @return 列式候选（{@code etag} 供客户端做 If-None-Match）
     */
    public MentionCandidateVO candidates(String typesCsv, String q, Integer limit) {
        List<AssetType> requested = AssetType.parseList(typesCsv);
        String keyword = q == null ? "" : q.trim();
        Snapshot snapshot = snapshotOf(typesCsv, requested);
        if (keyword.isEmpty()) {
            return toVO(snapshot, MentionCandidateVO.MODE_PREFETCH, MAX_PREFETCH_ITEMS);
        }
        int max = limit == null || limit <= 0 ? DEFAULT_SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
        List<AssetRef> hits = new ArrayList<>(search(snapshot.getRefs(), keyword, max));
        // C1.5（2026-10-31）：快照里没有表（万级、prefetchable=false），搜索引擎模式补一层
        // "按表名实时搜"。命中项仍是 source 类型（code=<ds>/<db>/<tbl>），插入即 @source/...，
        // 因此解析 / 校验 / 装配全走既有链路，不引入新协议。
        if (wantsTables(requested) && hits.size() < max && keyword.length() >= MIN_TABLE_KEYWORD) {
            for (AssetRef ref : tableNameSearchService.search(keyword, max - hits.size())) {
                if (hits.size() >= max) {
                    break;
                }
                if (!containsCode(hits, ref.getCode())) {
                    hits.add(ref);
                }
            }
        }
        return toVO(hits, MentionCandidateVO.MODE_SEARCH, max);
    }

    /** 表名搜索是否参与本次搜索：{@code types} 为空（全类型）或显式包含 source / table。 */
    private boolean wantsTables(List<AssetType> requested) {
        if (CollUtil.isEmpty(requested)) {
            return true;
        }
        return requested.contains(AssetType.SOURCE) || requested.contains(AssetType.TABLE);
    }

    /** 去重：快照命中与表名命中共用同一 code 时只保留快照那条（副标题更丰富）。 */
    private boolean containsCode(List<AssetRef> refs, String code) {
        if (StrUtil.isBlank(code)) {
            return false;
        }
        for (AssetRef ref : refs) {
            if (ref != null && StrUtil.equals(ref.getCode(), code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取单条详情（副标题 / 摘要）。
     *
     * <p><b>不看候选缓存</b>：用户点击时按 {@code code} 走各 provider 的<b>可见性方法</b>重新判定，
     * 保证"权限刚被撤销"的用户拿不到详情（缓存只服务候选列表，不服务越权判定）。
     *
     * @param typeCsv 协议值（{@code skill} / {@code kb} …）
     * @param code    候选的稳定标识
     * @return 详情；未命中或已不可见返回 {@code null}
     */
    public AssetRef detail(String typeCsv, String code) {
        AssetType type = AssetType.of(typeCsv);
        if (type == null || StrUtil.isBlank(code)) {
            return null;
        }
        for (MentionCandidateProvider provider : providers) {
            if (provider.type() != type) {
                continue;
            }
            try {
                return provider.detail(code.trim());
            } catch (Exception e) {
                log.warn("@ 候选详情读取失败（type={}, code={}）: {}", type.getCode(), code, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** 清空全部候选快照（兜底；常规路径优先用 {@link #invalidate(Integer)} 按租户清） */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * 失效指定租户的候选快照（资产写路径调用）。
     *
     * <p><b>谁该调</b>：任何改变「候选可见集合」或「候选展示内容」的写操作——kb 的新建/改名/
     * 启停/授权变更、skill 的新建/正文（含 description）/可见性/删除。**新增资产写接口时
     * 必须同步接线**，否则该接口的变更要等 {@code CACHE_TTL_MILLIS}（60s）才可见。
     *
     * <p><b>为什么按租户前缀清</b>：缓存 key 形如 {@code tenantId:userId:types}（见
     * {@link #cacheKey(String)}），同一租户的所有用户/类型组合共享同一批资产。只清本租户
     * 既能做到"改名即时生效"，又不会让其它租户的快照被迫回源。
     *
     * @param tenantId 租户 ID；为 {@code null}（无租户上下文）时退化为全量清
     */
    public void invalidate(Integer tenantId) {
        if (tenantId == null) {
            cache.clear();
            return;
        }
        String prefix = tenantId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Snapshot snapshotOf(String typesCsv, List<AssetType> requested) {
        String key = cacheKey(typesCsv);
        long now = System.currentTimeMillis();
        Snapshot cached = cache.get(key);
        if (cached != null && cached.getExpireAt() > now) {
            return cached;
        }
        List<AssetRef> refs = new ArrayList<>();
        for (MentionCandidateProvider provider : providers) {
            if (!requested.contains(provider.type())) {
                continue;
            }
            try {
                List<AssetRef> loaded = provider.load();
                if (loaded != null && !loaded.isEmpty()) {
                    refs.addAll(loaded);
                }
            } catch (Exception e) {
                // 单类失败不影响其它类：候选浮层是"尽力而为"的辅助功能，
                // 但必须留痕——静默吞掉会让"某类候选恒空"变成排查黑洞。
                log.warn("@ 候选聚合失败，已跳过类型 {}: {}", provider.type().getCode(), e.getMessage());
            }
        }
        Snapshot fresh = new Snapshot(refs, etagOf(refs), now + CACHE_TTL_MILLIS);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, fresh);
        return fresh;
    }

    /**
     * 缓存 key：{@code tenantId:userId:规范化类型串}。
     *
     * <p>类型串必须<b>规范化</b>（排序 + 去重 + 未知类型剔除），否则 {@code ?types=skill,doc}
     * 与 {@code ?types=doc,skill} 会各自缓存一份，白白翻倍。
     */
    private String cacheKey(String typesCsv) {
        Integer userId = null;
        try {
            userId = StpUtil.getLoginIdAsInt();
        } catch (Exception ignored) {
            // 未登录：Controller 已有 @SaCheckLogin 拦截，这里只保证不抛异常
        }
        Integer tenantId = Convert.toInt(TenantContextHolder.get(), null);
        List<String> codes = new ArrayList<>();
        for (AssetType type : AssetType.parseList(typesCsv)) {
            codes.add(type.getCode());
        }
        codes.sort(Comparator.naturalOrder());
        return tenantId + ":" + userId + ":" + String.join(",", codes);
    }

    /**
     * 内容指纹：{@code <hex>-<count>}。
     *
     * <p>用「条目顺序 + type/code + 展示字段」的滚动哈希，而不是 {@code max(updated_at)}：
     * provider 侧的 DTO（{@code SkillBrief} / {@code KbCollectionBrief}）并不都带时间戳，
     * 强行补一个会让契约多出一个"没人用但会漂"的字段；而内容变则 etag 必变，
     * 这已经满足「变了才重传」的唯一用途。
     *
     * <p><b>为什么 title / subtitle 必须参与哈希</b>（2026-10-31 修复）：最初只哈希
     * {@code type+code}，而这两者都是<b>不可变地址</b>；可变的展示内容（kb 库名、skill 描述）
     * 落不进指纹。于是"改名"这类纯展示变更的 ETag 恒定不变 → 客户端（浏览器）带
     * {@code If-None-Match} 时被判定"内容未变"→ <b>304</b> → 浏览器复用旧响应体，
     * 表现为「改名后候选永远显示旧名、刷新/清缓存/重启都无效」。
     *
     * <p>口径：**凡是会出现在响应体里的字段，都必须影响 ETag**；后续新增列（如将来补
     * {@code icons} / {@code badges}）要同步加进这里。
     */
    private String etagOf(List<AssetRef> refs) {
        long hash = 1125899906842597L;
        for (AssetRef ref : refs) {
            hash = 31 * hash + (ref.getType() == null ? 0 : ref.getType().hashCode());
            hash = 31 * hash + (ref.getCode() == null ? 0 : ref.getCode().hashCode());
            // 展示字段：改名 / 改描述必须换 ETag（否则客户端 304 复用旧名称）
            hash = 31 * hash + (ref.getTitle() == null ? 0 : ref.getTitle().hashCode());
            hash = 31 * hash
                    + (ref.getSubtitle() == null ? 0 : ref.getSubtitle().hashCode());
        }
        return Long.toHexString(hash) + "-" + refs.size();
    }

    private MentionCandidateVO toVO(Snapshot snapshot, String mode, int max) {
        return toVO(snapshot.getRefs(), mode, max, snapshot.getEtag());
    }

    private MentionCandidateVO toVO(List<AssetRef> refs, String mode, int max) {
        return toVO(refs, mode, max, etagOf(refs));
    }

    private MentionCandidateVO toVO(List<AssetRef> refs, String mode, int max, String etag) {
        int size = Math.min(refs.size(), max);
        MentionCandidateVO vo = new MentionCandidateVO();
        vo.setEtag(etag);
        vo.setMode(mode);
        vo.setCount(size);
        List<String> types = new ArrayList<>(size);
        List<String> codes = new ArrayList<>(size);
        List<String> titles = new ArrayList<>(size);
        List<String> subs = new ArrayList<>(size);
        boolean withSubs = size <= MAX_SUB_ITEMS;
        for (int i = 0; i < size; i++) {
            AssetRef ref = refs.get(i);
            types.add(ref.getType());
            codes.add(ref.getCode());
            titles.add(ref.getTitle());
            // 副标题只在阈值内下发；超出时整列为 null，前端按"无提示"渲染
            subs.add(withSubs ? ref.getSubtitle() : null);
        }
        vo.setTypes(types);
        vo.setCodes(codes);
        vo.setTitles(titles);
        vo.setSubs(subs);
        return vo;
    }

    /**
     * 服务端过滤（搜索模式）：前缀命中 &gt; 标题包含 &gt; 编码包含。
     *
     * <p>与前端本地匹配（{@code mentionIndex.ts}）保持同一套优先级，避免"预取模式排前面、
     * 降级后换个顺序"的观感跳变。
     */
    private List<AssetRef> search(List<AssetRef> refs, String keyword, int max) {
        String q = keyword.toLowerCase(Locale.ROOT);
        List<Scored> hits = new ArrayList<>();
        for (AssetRef ref : refs) {
            int score = score(ref, q);
            if (score > 0) {
                hits.add(new Scored(ref, score));
            }
        }
        hits.sort(Comparator.comparingInt(Scored::getScore));
        List<AssetRef> result = new ArrayList<>(Math.min(hits.size(), max));
        for (Scored hit : hits) {
            if (result.size() >= max) {
                break;
            }
            result.add(hit.getRef());
        }
        return result;
    }

    private int score(AssetRef ref, String q) {
        String title = StrUtil.nullToEmpty(ref.getTitle()).toLowerCase(Locale.ROOT);
        String code = StrUtil.nullToEmpty(ref.getCode()).toLowerCase(Locale.ROOT);
        if (title.equals(q)) {
            return 1;
        }
        if (title.startsWith(q)) {
            return 2;
        }
        if (title.contains(q)) {
            return 3;
        }
        if (code.startsWith(q)) {
            return 4;
        }
        if (code.contains(q)) {
            return 5;
        }
        return 0;
    }

    /** 候选快照（不可变视图 + 过期时间） */
    private static final class Snapshot {

        private final List<AssetRef> refs;

        private final String etag;

        private final long expireAt;

        private Snapshot(List<AssetRef> refs, String etag, long expireAt) {
            this.refs = refs;
            this.etag = etag;
            this.expireAt = expireAt;
        }

        private List<AssetRef> getRefs() {
            return refs;
        }

        private String getEtag() {
            return etag;
        }

        private long getExpireAt() {
            return expireAt;
        }
    }

    /** 打分中间态（避免给 {@link AssetRef} 加一个只用于排序的临时字段） */
    private static final class Scored {

        private final AssetRef ref;

        private final int score;

        private Scored(AssetRef ref, int score) {
            this.ref = ref;
            this.score = score;
        }

        private AssetRef getRef() {
            return ref;
        }

        private int getScore() {
            return score;
        }
    }
}
