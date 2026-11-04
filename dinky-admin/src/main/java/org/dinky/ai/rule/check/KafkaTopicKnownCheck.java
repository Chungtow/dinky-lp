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

package org.dinky.ai.rule.check;

import org.dinky.ai.context.NameRegistryService;
import org.dinky.ai.context.PlatformProfile;
import org.dinky.ai.context.PlatformProfileLoader;
import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;
import org.dinky.ai.rule.SqlKit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.kafka_topic_known}：Kafka 源的 {@code topic} 必须是**平台已知的 topic**。
 *
 * <p><b>为什么必须拦</b>：2026-11-04 实测模型产出了
 * {@code 'topic' = 'ods_traccar_tc_events'}——它把 Hive 表名当成了 topic 名。
 * 这个 topic 不存在，作业提交能过、运行期一直空跑（或直接 no offset），
 * 而草稿层面"connector=kafka、字段齐全"，看起来毫无问题。
 *
 * <p><b>已知清单的来源</b>（两处并集，都是既有事实，不新增连接）：
 * <ol>
 *   <li>平台画像 {@code topics}（人工维护，见 {@code platform-profile.yaml}）；
 *   <li>作业名册里出现过的 {@code kafkaTopic}（从历史作业 DDL 提取）。
 * </ol>
 *
 * <p>两者都空时判 {@code unknown} 而不是 fail —— 清单缺失不等于 topic 不存在。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
public class KafkaTopicKnownCheck implements RuleCheck {

    private static final int MAX_REPORTED = 10;

    private final PlatformProfileLoader platformProfileLoader;

    private final NameRegistryService nameRegistryService;

    public KafkaTopicKnownCheck() {
        this(null, null);
    }

    @Autowired
    public KafkaTopicKnownCheck(PlatformProfileLoader platformProfileLoader, NameRegistryService nameRegistryService) {
        this.platformProfileLoader = platformProfileLoader;
        this.nameRegistryService = nameRegistryService;
    }

    @Override
    public String kind() {
        return "sql.kafka_topic_known";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        Set<String> used = new LinkedHashSet<>();
        for (SqlKit.TableDef table : context.facts().createTables().values()) {
            String connector = table.option("connector");
            if (connector == null) {
                continue;
            }
            String normalized = connector.trim().toLowerCase(Locale.ROOT);
            if (!"kafka".equals(normalized) && !"upsert-kafka".equals(normalized)) {
                continue;
            }
            String topic = table.option("topic");
            if (StrUtil.isNotBlank(topic)) {
                used.add(topic.trim());
            }
        }
        if (used.isEmpty()) {
            return RuleOutcome.notApplicable("SQL 未声明 kafka / upsert-kafka 源（无 topic 配置）");
        }
        Set<String> known = loadKnownTopics();
        if (known.isEmpty()) {
            return RuleOutcome.unknown("平台画像与作业名册都没有 topic 清单，无法判定 topic 是否存在");
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String topic : used) {
            if (!known.contains(topic)) {
                missing.add(topic);
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("声明了平台不存在的 topic: " + join(missing) + "（已知 topic: " + join(known)
                    + "）。topic 名不是表名——源表名要靠 canal-json.table.include 过滤");
        }
        return RuleOutcome.pass("topic 均在已知清单内: " + join(used));
    }

    /** 平台画像 topics ∪ 作业名册 kafkaTopic */
    private Set<String> loadKnownTopics() {
        Set<String> known = new LinkedHashSet<>();
        try {
            if (platformProfileLoader != null) {
                PlatformProfile profile = platformProfileLoader.profile();
                if (profile != null) {
                    for (Map<String, String> topic : profile.getTopics()) {
                        String name = topic.get("name");
                        if (StrUtil.isNotBlank(name)) {
                            known.add(name.trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("kafka topic check: load platform profile failed", e);
        }
        try {
            if (nameRegistryService != null) {
                NameRegistryService.Snapshot snapshot = nameRegistryService.getSnapshot();
                if (snapshot != null) {
                    List<String> topics = snapshot.get("kafkaTopic");
                    if (topics != null) {
                        for (String topic : topics) {
                            if (StrUtil.isNotBlank(topic)) {
                                known.add(topic.trim());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("kafka topic check: load name registry failed", e);
        }
        return known;
    }

    private static String join(Set<String> topics) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String topic : topics) {
            if (shown >= MAX_REPORTED) {
                sb.append(" …");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append('`').append(topic).append('`');
            shown++;
        }
        return sb.toString();
    }
}
