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

package org.dinky.ai.draft;

import org.dinky.ai.rule.RuleContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FlinkSQL 作业草稿（四段式协议的<b>服务端模型</b>）。
 *
 * <p>四段缺一不可（计划 §4.1）：{@code sql} / {@code customConfig} / {@code ddl} / {@code precheck}。
 * 缺段不判失败（模型可能给纯文本），但交给校验器时按"该段为空"处理 —— 对应规则会返回
 * {@code UNKNOWN} 而不是 {@code PASS}。
 *
 * @since 2026/11/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlinkJobDraft {

    private String sql;

    @Builder.Default
    private Map<String, String> customConfig = new LinkedHashMap<>();

    @Builder.Default
    private List<DdlItem> ddl = new ArrayList<>();

    @Builder.Default
    private List<PrecheckItem> precheck = new ArrayList<>();

    private String name;

    private Integer parallelism;

    private Integer clusterId;

    private Integer envId;

    private Integer taskId;

    /** 工具入参 → 草稿（宽容解析：允许 {@code customConfig} 为对象或 [{key,value}] 数组） */
    public static FlinkJobDraft fromJson(JSONObject json) {
        if (json == null) {
            return FlinkJobDraft.builder().build();
        }
        JSONObject node = json.getJSONObject("draft");
        if (node == null) {
            node = json;
        }
        FlinkJobDraft draft = FlinkJobDraft.builder()
                .sql(StrUtil.trimToNull(node.getStr("sql")))
                .name(StrUtil.trimToNull(node.getStr("name")))
                .parallelism(node.getInt("parallelism"))
                .clusterId(node.getInt("clusterId"))
                .envId(node.getInt("envId"))
                .taskId(node.getInt("taskId"))
                .build();
        Object customConfig = node.get("customConfig");
        if (customConfig instanceof JSONObject) {
            JSONObject object = (JSONObject) customConfig;
            for (String key : object.keySet()) {
                draft.getCustomConfig().put(key.toLowerCase(), Convert.toStr(object.get(key)));
            }
        } else if (customConfig instanceof JSONArray) {
            for (Object item : (JSONArray) customConfig) {
                if (item instanceof JSONObject) {
                    JSONObject kv = (JSONObject) item;
                    String key = StrUtil.trimToNull(kv.getStr("key"));
                    if (key != null) {
                        draft.getCustomConfig().put(key.toLowerCase(), Convert.toStr(kv.get("value")));
                    }
                }
            }
        }
        Object ddl = node.get("ddl");
        if (ddl instanceof JSONArray) {
            for (Object item : (JSONArray) ddl) {
                if (item instanceof JSONObject) {
                    JSONObject ddlItem = (JSONObject) item;
                    draft.getDdl()
                            .add(new DdlItem(
                                    StrUtil.trimToNull(ddlItem.getStr("engine")),
                                    StrUtil.trimToNull(ddlItem.getStr("database")),
                                    StrUtil.trimToNull(ddlItem.getStr("table")),
                                    StrUtil.trimToNull(ddlItem.getStr("statement"))));
                }
            }
        }
        Object precheck = node.get("precheck");
        if (precheck instanceof JSONArray) {
            for (Object item : (JSONArray) precheck) {
                if (item instanceof JSONObject) {
                    JSONObject precheckItem = (JSONObject) item;
                    draft.getPrecheck()
                            .add(new PrecheckItem(
                                    StrUtil.trimToNull(precheckItem.getStr("item")),
                                    StrUtil.trimToNull(precheckItem.getStr("how")),
                                    StrUtil.trimToNull(precheckItem.getStr("status"))));
                }
            }
        }
        return draft;
    }

    /** 转成规则引擎上下文（任务字段 + 四段式内容） */
    public RuleContext toRuleContext() {
        List<String> ddlStatements = new ArrayList<>();
        String connector = null;
        for (DdlItem item : ddl) {
            if (StrUtil.isNotBlank(item.getStatement())) {
                ddlStatements.add(item.getStatement());
            }
            if (connector == null && StrUtil.isNotBlank(item.getEngine())) {
                connector = item.getEngine().trim().toLowerCase();
            }
        }
        return RuleContext.builder()
                .sql(sql)
                .customConfig(customConfig == null ? new LinkedHashMap<>() : customConfig)
                .ddlStatements(ddlStatements)
                .taskId(taskId)
                .taskName(name)
                .parallelism(parallelism)
                .clusterId(clusterId)
                .envId(envId)
                .sinkConnectorOverride(connector)
                .build();
    }

    public boolean hasSql() {
        return StrUtil.isNotBlank(sql);
    }

    /** 四段式 ddl 段的一项 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DdlItem {

        private String engine;

        private String database;

        private String table;

        private String statement;
    }

    /** 四段式 precheck 段的一项 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrecheckItem {

        private String item;

        private String how;

        /** pass / unknown / fail（unknown 表示"没能判定"，不得当通过） */
        private String status;
    }
}
