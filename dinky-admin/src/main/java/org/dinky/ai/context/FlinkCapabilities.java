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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.Data;

/**
 * 集群能力画像（L1）：**由部署脚本扫 Flink lib 探测生成**，不是人写的。
 *
 * <p><b>为什么必须探测</b>：把 connector 清单写成代码常量或人工 yaml，迟早会与集群真实情况漂移；
 * 一旦漂移，模型就会写出"提交阶段才失败"的作业（M1-01：`'connector' = 'hive'`）。
 * 探测产物随部署一起上机，换集群 / 加 jar 时自动更新。
 *
 * @since 2026/11/03
 */
@Data
public class FlinkCapabilities {

    /** 形如 {@code 1.17.2}；探测不到时为空 */
    private String flinkVersion = "";

    /** 探测时间（yaml 里写入；用于判断文件是否过期） */
    private String generatedAt = "";

    /** 探测来源（哪个节点、哪个 lib 目录） */
    private String sourceHost = "";

    /** 从 Flink lib 真实存在的 connector jar 解析出的 connector 名 */
    private List<String> connectors = new ArrayList<>();

    /** 探测覆盖不到的补充项（如内置 datagen/print、或按需手工加的驱动类 connector） */
    private List<String> extraConnectors = new ArrayList<>();

    /** **只能用于临时验证**的 connector（print/datagen/blackhole）——禁止作为交付 sink */
    private List<String> debugConnectors = new ArrayList<>(Arrays.asList("print", "datagen", "blackhole"));

    /** 除调试类之外的全部可用 connector（去重、保序、小写） */
    public List<String> usableConnectors() {
        Set<String> result = new LinkedHashSet<>();
        List<String> debug = normalized(debugConnectors);
        for (String item : normalized(connectors)) {
            if (!debug.contains(item)) {
                result.add(item);
            }
        }
        for (String item : normalized(extraConnectors)) {
            if (!debug.contains(item)) {
                result.add(item);
            }
        }
        return new ArrayList<>(result);
    }

    /** 含调试类的完整白名单（规则与画像共用） */
    public List<String> allConnectors() {
        Set<String> result = new LinkedHashSet<>(normalized(connectors));
        result.addAll(normalized(extraConnectors));
        result.addAll(normalized(debugConnectors));
        return new ArrayList<>(result);
    }

    public boolean isEmpty() {
        return connectors.isEmpty() && extraConnectors.isEmpty() && flinkVersion.isEmpty();
    }

    public static FlinkCapabilities of(Map<String, Object> raw) {
        FlinkCapabilities capabilities = new FlinkCapabilities();
        capabilities.setFlinkVersion(asString(raw.get("flink_version")));
        capabilities.setGeneratedAt(asString(raw.get("generated_at")));
        capabilities.setSourceHost(asString(raw.get("source_host")));
        capabilities.setConnectors(asList(raw.get("connectors")));
        capabilities.setExtraConnectors(asList(raw.get("extra_connectors")));
        List<String> debug = asList(raw.get("debug_connectors"));
        if (!debug.isEmpty()) {
            capabilities.setDebugConnectors(debug);
        }
        return capabilities;
    }

    private static List<String> normalized(List<String> source) {
        List<String> list = new ArrayList<>();
        if (source == null) {
            return list;
        }
        for (String item : source) {
            if (item != null && !item.trim().isEmpty()) {
                list.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }
        return list;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
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
}
