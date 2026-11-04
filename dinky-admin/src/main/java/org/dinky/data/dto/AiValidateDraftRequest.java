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

package org.dinky.data.dto;

import org.dinky.ai.draft.FlinkJobDraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 「校验当前 SQL」请求（前端按钮直连，**不经过大模型**）。
 *
 * <p><b>为什么要有这个接口</b>：UAT 里"审作业零误报"的判据是**规则引擎行为**，不是模型行为。
 * 靠模型转述会出现两种失真：① 模型压根没调工具（2026-11-03 实测 {@code tool_call_count=0}，
 * 却照样给出"命中/未命中"的结论）；② 模型把 error 说成"建议"或反之。
 * 直连接口返回结构化报告，判据稳定且不消耗模型额度。
 *
 * @since 2026/11/03
 */
@Data
@ApiModel("AI 规则校验请求（直连，不经模型）")
public class AiValidateDraftRequest {

    @ApiModelProperty(value = "完整 FlinkSQL（含 SET 头与 INSERT INTO）", required = true)
    private String sql;

    /**
     * 任务「自定义配置」。
     *
     * <p>兼容两种形态：{@code [{"key":"k","value":"v"}]}（前端作业配置的形态）
     * 与 {@code {"k":"v"}}（脚本调用更顺手）。
     */
    @ApiModelProperty(value = "任务自定义配置，[{key,value}] 或 {k:v}")
    private Object customConfig;

    @ApiModelProperty(value = "目标库建表 DDL 列表")
    private List<FlinkJobDraft.DdlItem> ddl;

    @ApiModelProperty(value = "前置检查结论列表")
    private List<FlinkJobDraft.PrecheckItem> precheck;

    @ApiModelProperty(value = "作业名（校验 pipeline.name 一致性）")
    private String name;

    @ApiModelProperty(value = "任务并行度（校验 parallelism.default）")
    private Integer parallelism;

    @ApiModelProperty(value = "目标 Flink 集群 id（校验租户归属）")
    private Integer clusterId;

    @ApiModelProperty(value = "FlinkSQL 环境 id")
    private Integer envId;

    @ApiModelProperty(value = "既有作业 id（更新场景，校验是否可改）")
    private Integer taskId;

    /** 请求体 → 草稿（宽容解析 customConfig，避免前端为自己的数据结构再写一套转换） */
    public FlinkJobDraft toDraft() {
        return FlinkJobDraft.builder()
                .sql(sql)
                .name(name)
                .parallelism(parallelism)
                .clusterId(clusterId)
                .envId(envId)
                .taskId(taskId)
                .customConfig(normalizeCustomConfig(customConfig))
                .ddl(ddl == null ? new ArrayList<>() : ddl)
                .precheck(precheck == null ? new ArrayList<>() : precheck)
                .build();
    }

    private static Map<String, String> normalizeCustomConfig(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(
                            String.valueOf(entry.getKey()).trim().toLowerCase(),
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
            }
        } else if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Object key = ((Map<?, ?>) item).get("key");
                    if (key == null || String.valueOf(key).trim().isEmpty()) {
                        continue;
                    }
                    Object value = ((Map<?, ?>) item).get("value");
                    result.put(String.valueOf(key).trim().toLowerCase(), value == null ? "" : String.valueOf(value));
                }
            }
        }
        return result;
    }
}
