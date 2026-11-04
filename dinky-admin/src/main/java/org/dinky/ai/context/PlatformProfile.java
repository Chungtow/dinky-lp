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
 * 平台画像（人工维护的那部分）。
 *
 * <p><b>2026-11-03 P0 瘦身</b>：原先把"集群能力"（connector 清单）、"默认 sink"、
 * "业务口径"都塞在这里，违背了分工铁律。现在只保留人工才说得清的三样：
 * <ul>
 *   <li>{@code connectorNotes} —— connector 的**用法约束**（如"jdbc 是唯一写通道"），能力清单本身在
 *       {@link FlinkCapabilities}（探测生成）</li>
 *   <li>{@code pipelines} —— 平台标准链路形态</li>
 *   <li>{@code topics} —— 已知 topic 与血缘</li>
 *   <li>{@code urlAliases} —— jdbc host 别名映射（给连接串一致性规则兜误报）</li>
 * </ul>
 * 业务口径（"位置条数用 tc_positions"）**不进这里**，归知识库（`lpdw-dict` / skill），由业务维护。
 *
 * @since 2026/11/02
 */
@Data
public class PlatformProfile {

    private Integer version;

    /** connector 用法约束（不是清单） */
    private List<String> connectorNotes = new ArrayList<>();

    /** 标准链路描述 */
    private List<String> pipelines = new ArrayList<>();

    /** 已知 topic（name/note） */
    private List<Map<String, String>> topics = new ArrayList<>();

    /** jdbc host 别名 → 真实 host:port（配置化解决"数据源写别名"导致的误报） */
    private Map<String, String> urlAliases = new LinkedHashMap<>();

    public boolean isEmpty() {
        return connectorNotes.isEmpty() && pipelines.isEmpty() && topics.isEmpty() && urlAliases.isEmpty();
    }

    /** 覆盖合并：标量取覆盖值，清单整段替换（避免"半新半旧"的缝合状态） */
    public PlatformProfile overlay(PlatformProfile other) {
        if (other == null) {
            return this;
        }
        PlatformProfile merged = new PlatformProfile();
        merged.setVersion(other.getVersion() == null ? version : other.getVersion());
        merged.setConnectorNotes(pick(other.getConnectorNotes(), connectorNotes));
        merged.setPipelines(pick(other.getPipelines(), pipelines));
        merged.setTopics(pick(other.getTopics(), topics));
        merged.setUrlAliases(
                other.getUrlAliases() == null || other.getUrlAliases().isEmpty()
                        ? new LinkedHashMap<>(urlAliases)
                        : new LinkedHashMap<>(other.getUrlAliases()));
        return merged;
    }

    private static <T> List<T> pick(List<T> overlay, List<T> base) {
        if (overlay != null && !overlay.isEmpty()) {
            return new ArrayList<>(overlay);
        }
        return base == null ? new ArrayList<>() : new ArrayList<>(base);
    }

    public static PlatformProfile of(Map<String, Object> raw) {
        PlatformProfile profile = new PlatformProfile();
        profile.setVersion(asInt(raw.get("version")));
        profile.setConnectorNotes(asStringList(raw.get("connector_notes")));
        profile.setPipelines(asStringList(raw.get("pipelines")));
        profile.setTopics(asMapList(raw.get("topics")));
        profile.setUrlAliases(asStringMap(raw.get("url_aliases")));
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
                    list.add(asStringMap(item));
                }
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object value) {
        Map<String, String> map = new LinkedHashMap<>();
        if (value instanceof Map) {
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                map.put(
                        String.valueOf(entry.getKey()),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return map;
    }
}
