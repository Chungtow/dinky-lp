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

import org.dinky.data.model.DataBase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * 请求线程预解析的上下文快照（AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>为什么要有它</b>：工具循环跑在异步线程池，那里的<b>租户上下文（ThreadLocal）已经丢失</b>
 * （{@code AiToolContext} 头注释已记录该事实）。而 P1 的两个工具都要"按名字找元数据"：
 * <ul>
 *   <li>{@code describe_source} 要按数据源名解析 {@link DataBase}——异步线程里查库会因租户过滤而"查不到"；</li>
 *   <li>{@code check_name_conflict} 要读命名名册（{@code dinky_task} 全表扫描，同样带租户过滤）。</li>
 * </ul>
 * 因此统一在<b>请求线程</b>算好快照，随请求传入。与 P0 的 {@code visibleSkills} 快照同一套路。
 *
 * <p><b>为什么给索引而不只给名字</b>：{@code describe_source} 拿到 {@link DataBase} 后要直连其
 * {@code driverConfig}——{@code Driver} 是纯 JDBC，不依赖 Web/租户上下文（这正是它能跨线程工作的原因）。
 *
 * <p><b>安全</b>：本对象含数据源连接信息，<b>永不进入发往模型的请求体</b>（同 {@link org.dinky.ai.AiToolContext}）。
 *
 * @since 2026/10/08
 */
@Getter
public class RequestSnapshot {

    /** 本租户<b>启用中</b>的数据源：名字 → 数据源（保序，便于给出可选名提示） */
    private final Map<String, DataBase> dataBasesByName;

    /** 命名名册快照（已含 topic ↔ canal 源表血缘） */
    private final NameRegistryService.Snapshot nameRegistry;

    private RequestSnapshot(Map<String, DataBase> dataBasesByName, NameRegistryService.Snapshot nameRegistry) {
        this.dataBasesByName = dataBasesByName;
        this.nameRegistry = nameRegistry;
    }

    /**
     * 构建快照（<b>必须在 HTTP 请求线程调用</b>）。
     *
     * @param databases 本租户启用中的数据源（{@code DataBaseService#listEnabledAll()}）
     * @param nameRegistry 命名名册快照（{@code NameRegistryService#getSnapshot()}）
     */
    public static RequestSnapshot of(List<DataBase> databases, NameRegistryService.Snapshot nameRegistry) {
        Map<String, DataBase> byName = new LinkedHashMap<>();
        if (databases != null) {
            for (DataBase dataBase : databases) {
                if (dataBase != null && StrUtil.isNotBlank(dataBase.getName())) {
                    byName.put(dataBase.getName(), dataBase);
                }
            }
        }
        return new RequestSnapshot(byName, nameRegistry);
    }

    /** 空快照（请求线程解析失败时的降级值）：工具会明确告知"快照不可用"，而不是静默给错结果。 */
    public static RequestSnapshot empty() {
        return new RequestSnapshot(Collections.emptyMap(), null);
    }

    public boolean isEmpty() {
        return dataBasesByName.isEmpty() && nameRegistry == null;
    }
}
