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

package org.dinky.cache;

import org.dinky.data.model.Schema;
import org.dinky.data.model.Table;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * 数据源元数据缓存（库 / 库表树 / 表），带 TTL。
 *
 * <p>取代原先 Controller 上的 {@code @Cacheable(cacheNames = "metadata_schema")}。三个动机：
 *
 * <ol>
 *   <li><b>没有过期策略</b>：Spring 默认 {@code ConcurrentMapCacheManager} 写入后只能靠重启进程、
 *       保存数据源、手动刷新才能清除，于是"在目标库里新建一个 database/schema"这类<b>外部变更</b>
 *       永远进不来（表现为 {@code @source/<ds>/} 里看不到新库）。改为固定 TTL 后最多滞后
 *       {@link #TTL_MILLIS}，同时保留手动失效通道。
 *   <li><b>key 冲突</b>：原来 {@code /getSchemasAndTables} 与 {@code /getSchemas} 共用同一个
 *       {@code cacheNames="metadata_schema"} + {@code key=id}，两者返回内容不同（前者含表、后者不含），
 *       谁先写谁生效，必然互相污染。这里按用途分命名空间。
 *   <li><b>表级下钻未缓存</b>：{@code /getTables} 每展开一个库就连一次元数据库，是下钻时最热的调用，
 *       一并纳入缓存。
 * </ol>
 *
 * <p>不引入 Caffeine 依赖：Dinky 运行时 lib 中没有 {@code caffeine}，自建实现可避免构建面变化。
 *
 * @since 2026-11-02
 */
@Component
public class MetadataSchemaCache {

    /** 缓存有效期（毫秒）。取 60s：既能挡住 {@code @source} 连续下钻的重复抓取，又让外部新建库最多 1 分钟可见 */
    public static final long TTL_MILLIS = 60_000L;

    private static final String SCHEMAS = "schemas";

    private static final String SCHEMAS_AND_TABLES = "schemasAndTables";

    private static final String TABLES = "tables";

    /** key 形如 {@code "<id>:<namespace>[:<schema>]"}，value 为对应结果 */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /**
     * 取「库 + 表」树，未命中或已过期则执行 loader 并回填。
     *
     * @param id     数据源 id，{@code null} 表示不缓存直接执行
     * @param loader 真实抓取逻辑（连库）
     * @return 库表树
     */
    public List<Schema> getSchemasAndTables(Integer id, Supplier<List<Schema>> loader) {
        return get(id, SCHEMAS_AND_TABLES, loader);
    }

    /**
     * 取「仅库」列表，未命中或已过期则执行 loader 并回填。
     *
     * @param id     数据源 id，{@code null} 表示不缓存直接执行
     * @param loader 真实抓取逻辑（连库）
     * @return 库列表
     */
    public List<Schema> getSchemas(Integer id, Supplier<List<Schema>> loader) {
        return get(id, SCHEMAS, loader);
    }

    /**
     * 取某个库下的表列表，未命中或已过期则执行 loader 并回填。
     *
     * @param id         数据源 id，{@code null} 表示不缓存直接执行
     * @param schemaName 库名（参与 key，不同库互不影响）
     * @param loader     真实抓取逻辑（连库）
     * @return 表列表
     */
    public List<Table> getTables(Integer id, String schemaName, Supplier<List<Table>> loader) {
        if (id == null) {
            return loader.get();
        }
        return get(key(id, TABLES + ":" + schemaName), loader);
    }

    /**
     * 失效单个数据源的全部缓存（保存 / 删除 / 启停 / 前端手动刷新时调用）。
     *
     * @param id 数据源 id
     */
    public void invalidate(Integer id) {
        if (id == null) {
            return;
        }
        String prefix = id + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 清空全部（数据源批量变更时使用）。 */
    public void clear() {
        cache.clear();
    }

    private <T> List<T> get(Integer id, String namespace, Supplier<List<T>> loader) {
        if (id == null) {
            return loader.get();
        }
        return get(key(id, namespace), loader);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> get(String key, Supplier<List<T>> loader) {
        long now = System.currentTimeMillis();
        Entry entry = cache.get(key);
        if (entry != null && entry.expireAt > now) {
            return (List<T>) entry.data;
        }
        List<T> data = loader.get();
        cache.put(key, new Entry(data, now + TTL_MILLIS));
        return data;
    }

    private static String key(Integer id, String namespace) {
        return id + ":" + namespace;
    }

    private static final class Entry {

        private final Object data;

        private final long expireAt;

        private Entry(Object data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }
    }
}
