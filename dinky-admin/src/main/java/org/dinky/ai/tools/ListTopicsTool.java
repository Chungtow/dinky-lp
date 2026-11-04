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

package org.dinky.ai.tools;

import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.ai.kafka.KafkaTopicCatalog;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 列出 Kafka 集群里的真实 topic（P1，2026-11-04，只读）。
 *
 * <p><b>解决什么问题</b>：在这之前，模型被问"能连哪些 topic"时只能说"我从平台画像里看到 traccar-cdc"，
 * 而且会补一句"实际可能更多，但未登记的我无法确认"——那句话是对的，但它因此不敢用任何未登记的 topic，
 * 用户想用新 topic 时就卡住了。有了这个工具，"有哪些"从"查登记表"变成"问集群"。
 *
 * <p><b>为什么不给它读消息的能力</b>：列举是元数据操作，安全且快；读消息样本涉及数据内容，
 * 需要单独的数据权限设计，不在本轮范围内（模型需要字段口径时应走 {@code @source/} 或 {@code @topic/}）。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListTopicsTool implements AiTool {

    public static final String NAME = "list_topics";

    private final KafkaTopicCatalog kafkaTopicCatalog;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AiToolSpec spec() {
        return AiToolSpec.builder()
                .name(NAME)
                .description("列出 Kafka 集群里**真实存在**的 topic（来自 broker，不是人工登记表）。"
                        + "返回 {reachable, topics:[{name, partitions, internal}]}。"
                        + "当用户问\"有哪些 topic\"、或你准备写 kafka 源表但不确定 topic 是否存在时调用。"
                        + "reachable=false 表示 Kafka 暂时不可达（此时**不要**断言 topic 不存在）。")
                .parameters(AiToolSpec.objectSchema(null, new JSONObject(), Collections.emptyList()))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        List<KafkaTopicCatalog.TopicInfo> topics = kafkaTopicCatalog.listTopics();
        JSONObject result = new JSONObject();
        if (topics == null) {
            // 不可达 ≠ 没有：明确区分，否则模型会把"查不到"写成"不存在"
            result.set("reachable", false);
            result.set("topics", new JSONArray());
            result.set(
                    "message",
                    "Kafka 集群当前不可达（或未配置 bootstrap.servers），无法列举 topic；"
                            + "这不代表没有 topic，请改用上下文里「平台链路约定」登记的 topic，或让用户确认");
            return AiToolResult.success(result.toString(), System.currentTimeMillis() - start);
        }
        JSONArray array = new JSONArray();
        for (KafkaTopicCatalog.TopicInfo topic : topics) {
            if (topic.isInternal()) {
                // 内部 topic（__consumer_offsets 等）对写作业没有意义，列出来只会干扰
                continue;
            }
            array.add(new JSONObject().set("name", topic.getName()).set("partitions", topic.getPartitions()));
        }
        result.set("reachable", true);
        result.set("topics", array);
        result.set("count", array.size());
        return AiToolResult.success(result.toString(), System.currentTimeMillis() - start);
    }
}
