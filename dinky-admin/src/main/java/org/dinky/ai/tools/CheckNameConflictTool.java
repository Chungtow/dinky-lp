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
import org.dinky.ai.context.NameRegistryService;
import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 检查「名字是否已被现有作业占用」（🟢 纯元数据；AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>为什么需要</b>：FlinkSQL 作业有三个必须逐作业唯一的标识——{@code pipeline.name}、
 * Kafka {@code properties.group.id}、Doris {@code sink.label-prefix}。历史事故：两个作业复用同一
 * {@code group.id} → 瓜分同一消费组的分区 → 每个作业只看到一部分数据（Top5 条数偏小）；
 * 同名 {@code pipeline.name} → YARN app 名 / 告警 / 排障定位全部失效。
 * 模型在"生成新作业"时最容易在这里犯错，因此给它一个能自查的工具。
 *
 * <p><b>数据来源</b>：请求线程预解析的命名名册快照（只统计<b>本租户</b>的
 * {@code FlinkSql} / {@code FlinkSqlEnv} 任务）——异步线程查库会因租户上下文丢失而失败（见 {@code RequestSnapshot}）。
 * 口径会原样写进返回内容，避免模型把"本租户没有"误读为"全局没有"。
 *
 * @since 2026/10/08
 */
@Slf4j
@Component
public class CheckNameConflictTool implements AiTool {

    public static final String NAME = "check_name_conflict";

    /** 名字白名单（与作业命名习惯一致：字母数字 + 下划线/连字符/点/冒号） */
    private static final Pattern SAFE_VALUE = Pattern.compile("[\\p{L}\\p{N}_\\-.$:]{1,128}");

    private static final int MAX_LIST = 20;

    @Override
    public String name() {
        return NAME;
    }

    /** P1：与 {@code @source/} 同开关（都属于"跨对象上下文"能力包） */
    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config.isLlmMentionSourceEnable();
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set(
                "kind",
                AiToolSpec.stringProperty("可选，名册种类：pipelineName / kafkaGroupId / dorisLabelPrefix / "
                        + "dorisTargetTable / kafkaTopic；留空表示在全部种类里检查"));
        properties.set("value", AiToolSpec.stringProperty("必填，待检查的名字（如 traccar_device_top5_v1）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("检查某个名字是否已被现有 FlinkSQL 作业占用（pipeline.name / Kafka group.id / "
                        + "Doris sink.label-prefix / Doris table.identifier / Kafka topic），并给出同族已占用清单与建议值。"
                        + "在生成新作业、起新的 group.id 或 label-prefix 之前应先自查："
                        + "复用 group.id 会让两个作业瓜分分区导致数据偏小，同名 pipeline.name 会让告警与排障定位失效。"
                        + "只为【本租户】的 FlinkSQL 作业统计。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("value")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        NameRegistryService.Snapshot snapshot =
                context == null ? null : context.getRequestSnapshot().getNameRegistry();
        if (snapshot == null) {
            return AiToolResult.failure(
                    "本次对话没有可用的命名名册快照，无法检查名字占用（可让用户改用 @topic/ 或稍后重试）。", System.currentTimeMillis() - start);
        }
        String value = args == null ? null : StrUtil.trimToNull(args.getStr("value"));
        if (value == null) {
            return AiToolResult.failure("缺少必填参数 value（待检查的名字）", System.currentTimeMillis() - start);
        }
        if (!SAFE_VALUE.matcher(value).matches()) {
            return AiToolResult.failure("名字含非法字符，已拒绝检查", System.currentTimeMillis() - start);
        }
        String kind = args == null ? null : StrUtil.trimToNull(args.getStr("kind"));
        List<String> kinds = new ArrayList<>();
        if (kind == null) {
            kinds.addAll(NameRegistryService.Snapshot.kinds());
        } else {
            for (String candidate : NameRegistryService.Snapshot.kinds()) {
                if (candidate.equalsIgnoreCase(kind)) {
                    kinds.add(candidate);
                    break;
                }
            }
            if (kinds.isEmpty()) {
                return AiToolResult.failure(
                        "未知的 kind `" + kind + "`；可选：" + String.join(" / ", NameRegistryService.Snapshot.kinds()),
                        System.currentTimeMillis() - start);
            }
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("- 待检查名字: `").append(value).append("`\n");
        sb.append("- 名册范围: 本租户的 FlinkSql / FlinkSqlEnv 任务（共 ")
                .append(snapshot.getTaskCount())
                .append(" 个作业），不含其它租户与未启用的作业\n");
        boolean anyConflict = false;
        for (String candidate : kinds) {
            List<String> conflicts = snapshot.conflictsOf(candidate, value);
            String label = NameRegistryService.Snapshot.kindLabel(candidate);
            if (CollUtil.isEmpty(conflicts)) {
                sb.append("    - [未占用] ").append(label).append('\n');
            } else {
                anyConflict = true;
                sb.append("    - [已占用] ")
                        .append(label)
                        .append("：")
                        .append(String.join(" / ", conflicts))
                        .append('\n');
            }
        }
        if (anyConflict) {
            sb.append("- 结论: **该名字已被占用**，请换一个（务必逐作业唯一）。\n");
        } else {
            sb.append("- 结论: 该名字在检查的范围内**未被占用**。\n");
        }
        appendSameKindList(sb, snapshot, kinds);
        appendSuggestion(sb, kinds);
        return AiToolResult.success(sb.toString(), System.currentTimeMillis() - start);
    }

    /** 附件同族已占用清单（限 20 条）：模型据此避开已有名字，而不是逐个试错。 */
    private void appendSameKindList(StringBuilder sb, NameRegistryService.Snapshot snapshot, List<String> kinds) {
        if (kinds.size() != 1) {
            return;
        }
        List<String> occupied = snapshot.get(kinds.get(0));
        if (CollUtil.isEmpty(occupied)) {
            sb.append("- 该种类的已占用清单: (空)\n");
            return;
        }
        sb.append("- 该种类的已占用清单（共 ")
                .append(occupied.size())
                .append(" 个，最多列 ")
                .append(MAX_LIST)
                .append("）: ");
        List<String> limited = occupied.subList(0, Math.min(occupied.size(), MAX_LIST));
        sb.append(String.join(" / ", limited));
        if (occupied.size() > limited.size()) {
            sb.append(" …");
        }
        sb.append('\n');
    }

    /** 起名建议：group.id 用统一前缀（取值来自全局 Kafka 配置），避免各写各的。 */
    private void appendSuggestion(StringBuilder sb, List<String> kinds) {
        if (!kinds.contains("kafkaGroupId")) {
            return;
        }
        sb.append("- 建议 group.id: `")
                .append(SystemConfiguration.getInstances().getKafkaConsumerGroupPrefix())
                .append("_<作业名>_v1`（前缀可在【配置中心 - 全局设置 - Kafka 配置】统一调整）\n");
    }
}
