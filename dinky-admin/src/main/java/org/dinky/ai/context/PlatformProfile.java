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

package org.dinky.ai.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 平台运行画像（M1 补充）：把"本集群能干什么"变成可配置事实。
 *
 * <p><b>为什么必须显式给出</b>：模型的通用知识里 "Flink 支持 hive/doris/elasticsearch"，
 * 但本集群的 Flink lib 只有 kafka + jdbc + ClickHouse。不显式声明，它就会写
 * {@code 'connector' = 'hive'}，直到提交阶段才报 {@code Could not find any factories}
 * （2026-11-03 M1-01 实测）。
 *
 * <p>字段全部是"渲染即用"的文本/清单，判定逻辑不在这里（那是规则库的事）。
 *
 * @since 2026/11/03
 */
@Data
public class PlatformProfile {

    private Integer version;

    /** 集群 Flink lib 里实际可用的 connector 名 */
    private List<String> connectors = new ArrayList<>();

    /** 每条 connector 的约束说明（如"jdbc 是唯一写通道，目标是 ClickHouse"） */
    private List<String> connectorNotes = new ArrayList<>();

    /** 写侧目标（engine/database/note） */
    private List<Map<String, String>> sinkTargets = new ArrayList<>();

    /** 标准链路描述 */
    private List<String> pipelines = new ArrayList<>();

    /** 已知 topic（name/note） */
    private List<Map<String, String>> topics = new ArrayList<>();

    /** 业务口径约定（"位置条数用 tc_positions" 这类"平台标准答案"） */
    private List<String> conventions = new ArrayList<>();

    public boolean isEmpty() {
        return connectors.isEmpty()
                && connectorNotes.isEmpty()
                && sinkTargets.isEmpty()
                && pipelines.isEmpty()
                && topics.isEmpty()
                && conventions.isEmpty();
    }

    /** 覆盖合并：标量取覆盖值，清单整段替换（避免"半新半旧"的缝合状态） */
    public PlatformProfile overlay(PlatformProfile other) {
        if (other == null) {
            return this;
        }
        PlatformProfile merged = new PlatformProfile();
        merged.setVersion(other.getVersion() == null ? version : other.getVersion());
        merged.setConnectors(pick(other.getConnectors(), connectors));
        merged.setConnectorNotes(pick(other.getConnectorNotes(), connectorNotes));
        merged.setSinkTargets(pick(other.getSinkTargets(), sinkTargets));
        merged.setPipelines(pick(other.getPipelines(), pipelines));
        merged.setTopics(pick(other.getTopics(), topics));
        merged.setConventions(pick(other.getConventions(), conventions));
        return merged;
    }

    private static <T> List<T> pick(List<T> overlay, List<T> base) {
        if (overlay != null && !overlay.isEmpty()) {
            return new ArrayList<>(overlay);
        }
        return base == null ? new ArrayList<>() : new ArrayList<>(base);
    }

    /** 浅拷贝构造（Loader 落库用） */
    public static PlatformProfile of(Map<String, Object> raw) {
        PlatformProfile profile = new PlatformProfile();
        profile.setVersion(asInt(raw.get("version")));
        profile.setConnectors(asStringList(raw.get("connectors")));
        profile.setConnectorNotes(asStringList(raw.get("connector_notes")));
        profile.setSinkTargets(asMapList(raw.get("sink_targets")));
        profile.setPipelines(asStringList(raw.get("pipelines")));
        profile.setTopics(asMapList(raw.get("topics")));
        profile.setConventions(asStringList(raw.get("conventions")));
        return profile;
    }

    private static Integer asInt(Object value) {
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> asMapList(Object value) {
        List<Map<String, String>> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof Map) {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) item).entrySet()) {
                        map.put(
                                String.valueOf(entry.getKey()),
                                entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    }
                    list.add(map);
                }
            }
        }
        return list;
    }
}
