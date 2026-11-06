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

import org.dinky.data.model.DataBase;
import org.dinky.data.model.Schema;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.DataBaseService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * C1.5 轻量表名搜索（2026-10-31）：<b>不建投影表</b>，直接按名搜物理表。
 *
 * <p><b>为什么需要它</b>：C1 的统一预取刻意不含表（{@link AssetType#TABLE} /
 * {@link AssetType#SOURCE} 都是 {@code prefetchable=false}，万级规模撑爆浏览器），
 * 于是"记不住表在哪个库"的用户在 {@code @} 里搜表名永远是空的。C2（{@code dinky_asset_index}
 * 投影表）已按 K10 暂缓，中间态就是本类：<b>服务端按需搜、结果带缓存</b>。
 *
 * <p><b>代价与防线</b>（这是本类全部设计的出发点）：
 *
 * <ol>
 *   <li><b>每次都连库</b>：{@code Driver#getSchemasAndTables} 是真实连接操作，Hive / Presto 上
 *       可能秒级。因此：① 只对 {@code q.length >= 2} 触发（由调用方保证）；② 结果按数据源
 *       <b>缓存 5 分钟</b>（表结构变化远慢于候选快照的 60s）；③ 单源读取有<b>超时</b>
 *       （{@value #PER_SOURCE_TIMEOUT_MS}ms），超时即放弃该源，不让一次按键把请求线程挂住。
 *   <li><b>租户边界</b>：数据源清单取 {@code listEnabledAll()}（{@code enabled=1} + 租户插件），
 *       因此"能看到哪些数据源"与数据源管理页完全同源，<b>不另写权限查询</b>。
 *   <li><b>结果必须可控</b>：命中项统一是 {@code source} 类型、code 为
 *       {@code <数据源>/<库>/<表>}——这样插入的句柄是 {@code @source/<ds>/<db>/<tbl>}，
 *       解析 / 校验 / 装配全走既有链路，<b>不引入新协议</b>（实施计划 H6）。
 * </ol>
 *
 * <p><b>不做</b>：不做字段搜索（30 万级，必须命中表后下钻）；不做拼音/别名（C3，按信号触发）；
 * 不建表、不写库（纯读，失败只降级）。
 *
 * @since 2026/10/31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableNameSearchService {

    /** 表结构缓存 TTL：5 分钟（表结构变更远慢于候选；过短等于没缓存） */
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    /** 单数据源读取超时：超时放弃该源（返回缓存里的旧值或空），绝不阻塞整请求 */
    private static final long PER_SOURCE_TIMEOUT_MS = 2_000L;

    /** 缓存条目上限（防止极端租户数量下无界增长） */
    private static final int MAX_CACHE_ENTRIES = 64;

    /** 单次搜索最多返回多少条表命中 */
    private static final int MAX_HITS = 10;

    /** 搜索并行度：源通常 < 10，固定小池即可 */
    private static final int POOL_SIZE = 4;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE, new DaemonFactory());

    private final DataBaseService dataBaseService;

    /** 数据源名 → 该源下全部表（列式 {@link AssetRef}），带过期时间 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 按表名搜索（大小写不敏感）。
     *
     * @param keyword 关键词（调用方保证已 trim 且长度 ≥2；更短的输入匹配面太大，且必然拖慢首屏）
     * @param limit   返回上限（内部再按 {@link #MAX_HITS} 收敛）
     * @return 命中项，code = {@code <ds>/<db>/<tbl>}，type = {@code source}；永不为 {@code null}
     */
    public List<AssetRef> search(String keyword, int limit) {
        String q = StrUtil.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        if (q.length() < 2) {
            return new ArrayList<>();
        }
        int max = limit <= 0 ? MAX_HITS : Math.min(limit, MAX_HITS);
        List<Scored> hits = new ArrayList<>();
        for (AssetRef ref : loadAll()) {
            int score = score(ref, q);
            if (score > 0) {
                hits.add(new Scored(ref, score));
            }
        }
        // 分数语义：1=全等、2=前缀、3=子串、4=路径包含（越小越好）
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

    /** 取全部数据源的表清单（命中缓存则零连接） */
    private List<AssetRef> loadAll() {
        List<DataBase> sources = dataBaseService.listEnabledAll();
        if (CollUtil.isEmpty(sources)) {
            return new ArrayList<>();
        }
        List<AssetRef> all = new ArrayList<>();
        for (DataBase dataBase : sources) {
            if (dataBase == null || StrUtil.isBlank(dataBase.getName())) {
                continue;
            }
            CacheEntry entry = cache.get(dataBase.getName());
            long now = System.currentTimeMillis();
            if (entry != null && entry.expireAt > now) {
                all.addAll(entry.refs);
                continue;
            }
            List<AssetRef> loaded = loadOne(dataBase);
            // 读取失败（超时 / 连接失败）时保留旧缓存：宁可给 5 分钟前的表名，也不要空列表
            if (loaded.isEmpty() && entry != null) {
                all.addAll(entry.refs);
                continue;
            }
            putCache(dataBase.getName(), loaded, now + CACHE_TTL_MILLIS);
            all.addAll(loaded);
        }
        return all;
    }

    /** 读单个数据源的表清单（带超时；任何异常都降级为空列表） */
    private List<AssetRef> loadOne(DataBase dataBase) {
        Future<List<AssetRef>> future = EXECUTOR.submit(() -> readTables(dataBase));
        try {
            return future.get(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (Exception e) {
            // 超时 / 连接失败：取消任务，本轮跳过该源（不写空缓存，下一轮还会重试）
            future.cancel(true);
            log.info("Table name search: 数据源 {} 读取失败或超时（{}），本轮跳过", dataBase.getName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<AssetRef> readTables(DataBase dataBase) {
        List<AssetRef> refs = new ArrayList<>();
        try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
            List<Schema> schemas = driver.getSchemasAndTables();
            if (CollUtil.isEmpty(schemas)) {
                return refs;
            }
            String dsName = dataBase.getName();
            for (Schema schema : schemas) {
                if (schema == null || StrUtil.isBlank(schema.getName()) || CollUtil.isEmpty(schema.getTables())) {
                    continue;
                }
                for (Table table : schema.getTables()) {
                    if (table == null || StrUtil.isBlank(table.getName())) {
                        continue;
                    }
                    AssetRef ref = AssetRef.of(
                            AssetType.SOURCE, dsName + "/" + schema.getName() + "/" + table.getName(), table.getName());
                    // 副标题就是"这张表在哪"——C1.5 要解决的核心问题
                    ref.setSubtitle(dsName + "/" + schema.getName());
                    refs.add(ref);
                }
            }
        } catch (Exception e) {
            log.info("Table name search: 数据源 {} 表清单读取失败（{}）", dataBase.getName(), e.getMessage());
        }
        return refs;
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
        if (code.contains(q)) {
            return 4;
        }
        return 0;
    }

    private void putCache(String key, List<AssetRef> refs, long expireAt) {
        if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(refs, expireAt));
    }

    /** 缓存条目（不可变视图 + 过期时间） */
    private static final class CacheEntry {

        private final List<AssetRef> refs;

        private final long expireAt;

        private CacheEntry(List<AssetRef> refs, long expireAt) {
            this.refs = refs;
            this.expireAt = expireAt;
        }
    }

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

    /** 守护线程工厂：搜索线程不应阻止 JVM 退出 */
    private static final class DaemonFactory implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "dinky-table-search-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
