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

package org.dinky.ai.kafka;

import org.dinky.data.model.SystemConfiguration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicListing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka topic 目录（P1，2026-11-04）：**实时列举**集群里真实存在的 topic。
 *
 * <p><b>为什么必须有这一层</b>：此前 topic 清单唯一来源是 {@code platform-profile.yaml} 的人工登记，
 * 于是出现两个问题：① 模型被问"有哪些 topic"时只能回答登记过的那一个，说不出所以然；
 * ② 新 topic 建好后如果不改配置文件，AI 永远看不到它，"未登记"被误读成"不存在"。
 *
 * <p><b>为什么不走 Kafka UI 的 REST</b>：kafka-ui 的 {@code /api/clusters/{c}/topics} 实测可用，
 * 但它有三个不适合做产品依赖的问题：① 内部 API 无版本承诺，UI 一升级就可能变；
 * ② 它连的是 **EXTERNAL listener（29094）**，而 Flink 作业必须用 **INTERNAL（29092）**——
 * 两个 listener 的网络可达性不同，会出现"UI 看得到、Flink 连不上"的误判；
 * ③ 运维工具不该成为数据面依赖。这里直接连 Flink 用的同一个 listener。
 *
 * <p><b>失败一律降级</b>：Kafka 不可达时返回 {@code null}（调用方据此判"未知"而不是"不存在"），
 * 绝不让一次 broker 抖动把整条对话打挂。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
public class KafkaTopicCatalog {

    /** topic 清单缓存：列举是元数据操作，没必要每次对话都打一遍 broker */
    private static final long CACHE_TTL_MS = 60_000L;

    /**
     * <b>失败也要缓存</b>（2026-11-04 实测后补）。
     *
     * <p>原先只在成功时写缓存，后果是：Kafka 一旦不可达，<b>每个请求</b>都要等满 5 秒超时才降级——
     * 集群侧一次抖动会被放大成"所有 AI 对话都卡 5 秒"。失败缓存用更短的 TTL：
     * 既能止住风暴，又能在 Kafka 恢复后快速自愈。
     */
    private static final long FAILURE_TTL_MS = 30_000L;

    /** 单次列举的硬超时：Kafka 卡住时不能让用户等 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private volatile CachedTopics cached;

    /**
     * 列举集群里的 topic（含内部分区信息）。
     *
     * @return topic 清单；Kafka 不可达 / 未配置时返回 {@code null}（= 判不了，不是"没有"）
     */
    public List<TopicInfo> listTopics() {
        CachedTopics local = cached;
        long now = System.currentTimeMillis();
        if (local != null) {
            // 成功缓存 60s、失败缓存 30s：失败也命中缓存，避免 Kafka 挂掉时每个请求都等满超时
            long ttl = local.topics == null ? FAILURE_TTL_MS : CACHE_TTL_MS;
            if (now - local.loadedAt < ttl) {
                return local.topics;
            }
        }
        List<TopicInfo> loaded = load();
        cached = new CachedTopics(loaded, now);
        return loaded;
    }

    /** topic 名清单（排序后）；Kafka 不可达时返回空集合 —— 调用方需用 {@link #listTopics()} 区分"判不了" */
    public Set<String> topicNames() {
        List<TopicInfo> topics = listTopics();
        if (topics == null) {
            return Collections.emptySet();
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        for (TopicInfo topic : topics) {
            if (!topic.isInternal()) {
                names.add(topic.getName());
            }
        }
        return names;
    }

    /** 真实存在且非内部 topic 的清单（给 prompt 用）；判不了时返回 null */
    public List<TopicInfo> externalTopics() {
        List<TopicInfo> topics = listTopics();
        if (topics == null) {
            return null;
        }
        List<TopicInfo> result = new ArrayList<>();
        for (TopicInfo topic : topics) {
            if (!topic.isInternal()) {
                result.add(topic);
            }
        }
        return result;
    }

    /** 该 topic 是否存在（Kafka 不可达时返回 null = 不确定） */
    public Boolean exists(String topic) {
        if (StrUtil.isBlank(topic)) {
            return null;
        }
        List<TopicInfo> topics = listTopics();
        if (topics == null) {
            return null;
        }
        for (TopicInfo item : topics) {
            if (topic.equals(item.getName())) {
                return true;
            }
        }
        return false;
    }

    private List<TopicInfo> load() {
        String bootstrap = null;
        try {
            bootstrap = SystemConfiguration.getInstances().getKafkaBootstrapServers();
        } catch (Exception e) {
            log.debug("kafka catalog: read bootstrap servers failed", e);
        }
        if (StrUtil.isBlank(bootstrap)) {
            log.debug("kafka catalog: bootstrap servers not configured, skip");
            return null;
        }
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "dinky-ai-topic-catalog");
        try (AdminClient client = AdminClient.create(props)) {
            Map<String, TopicListing> listings =
                    client.listTopics().namesToListings().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<String, Integer> partitions = loadPartitions(client, listings.keySet());
            List<TopicInfo> topics = new ArrayList<>();
            for (TopicListing listing : listings.values()) {
                topics.add(new TopicInfo(
                        listing.name(), partitions.getOrDefault(listing.name(), -1), listing.isInternal()));
            }
            topics.sort((left, right) -> left.getName().compareTo(right.getName()));
            log.info("kafka catalog: {} topics from {}", topics.size(), bootstrap);
            return topics;
        } catch (Exception e) {
            // 降级：返回 null 让调用方判"未知"，而不是误报"没有 topic"
            log.warn("kafka catalog: list topics failed ({}): {}", bootstrap, e.getMessage());
            return null;
        }
    }

    /** 分区数属于"锦上添花"：拿不到就用 -1 表示未知，不影响主流程 */
    private Map<String, Integer> loadPartitions(AdminClient client, Set<String> names) {
        Map<String, Integer> partitions = new HashMap<>();
        try {
            client.describeTopics(names)
                    .allTopicNames()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .forEach((name, description) ->
                            partitions.put(name, description.partitions().size()));
        } catch (Exception e) {
            log.debug("kafka catalog: describe topics failed: {}", e.getMessage());
        }
        return partitions;
    }

    /** 一条 topic 的概况（只有元数据，不读消息） */
    public static final class TopicInfo {

        private final String name;

        private final int partitions;

        private final boolean internal;

        public TopicInfo(String name, int partitions, boolean internal) {
            this.name = name;
            this.partitions = partitions;
            this.internal = internal;
        }

        public String getName() {
            return name;
        }

        public int getPartitions() {
            return partitions;
        }

        public boolean isInternal() {
            return internal;
        }
    }

    /** 缓存项 */
    private static final class CachedTopics {

        private final List<TopicInfo> topics;

        private final long loadedAt;

        private CachedTopics(List<TopicInfo> topics, long loadedAt) {
            this.topics = topics;
            this.loadedAt = loadedAt;
        }
    }
}
