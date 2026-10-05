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

package org.dinky.ai;

import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具注册表：决定「本次对话向模型暴露哪些工具」。
 *
 * <p>所有 {@link AiTool} 实现作为 Spring Bean 由容器收集注入，无需手工登记。
 *
 * <p><b>为什么按"本次"决定</b>：触碰业务数据行的工具（如 sample_rows）默认不注册——
 * 不下发给模型比"下发后再拒绝"更安全，也避免模型在工具清单里看到不该看到的能力
 * （调研报告 §7.1）。
 *
 * @since 2026/09/28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolRegistry {

    private final List<AiTool> candidates;

    /** 本次可用的工具（按 name 索引） */
    public Map<String, AiTool> enabledTools(SystemConfiguration config) {
        Map<String, AiTool> tools = new LinkedHashMap<>();
        if (candidates == null) {
            return tools;
        }
        for (AiTool tool : candidates) {
            if (tool == null) {
                continue;
            }
            if (!tool.isEnabled(config)) {
                continue;
            }
            tools.put(tool.name(), tool);
        }
        return tools;
    }

    /** 本次下发给模型的工具声明列表 */
    /** 阶段 4b：不依赖数据源的工具名（未绑定数据源时仍下发）。 */
    private static final java.util.List<String> DATA_BASE_FREE_TOOL_NAMES =
            java.util.Arrays.asList("list_skills", "read_skill", "create_skill", "write_skill_file", "delete_skill");

    /**
     * 阶段 4b：按「是否绑定数据源」过滤后的工具清单。
     *
     * <p>未绑定数据源时只保留不依赖数据源的工具——否则模型会把有限的工具调用额度浪费在必然
     * 失败的表查询上（UAT B4 实测：先试两次 list_tables 都失败，额度耗尽后，真正要做的
     * write_skill_file 反而调不到，只能让用户手工粘贴）。
     */
    public Map<String, AiTool> enabledTools(SystemConfiguration config, boolean hasDataBase) {
        Map<String, AiTool> all = enabledTools(config);
        if (hasDataBase) {
            return all;
        }
        Map<String, AiTool> filtered = new LinkedHashMap<>();
        all.forEach((name, tool) -> {
            if (DATA_BASE_FREE_TOOL_NAMES.contains(name)) {
                filtered.put(name, tool);
            }
        });
        log.info("No database bound, offer only database-free tools: {}", filtered.keySet());
        return filtered;
    }

    /** 阶段 4b：按「是否绑定数据源」过滤后的 spec 列表。 */
    public List<AiToolSpec> specs(SystemConfiguration config, boolean hasDataBase) {
        List<AiToolSpec> specs = new java.util.ArrayList<>();
        for (AiTool tool : enabledTools(config, hasDataBase).values()) {
            specs.add(tool.spec());
        }
        return specs;
    }

    public List<AiToolSpec> specs(SystemConfiguration config) {
        List<AiToolSpec> specs = new ArrayList<>();
        for (AiTool tool : enabledTools(config).values()) {
            specs.add(tool.spec());
        }
        return specs;
    }

    /** 按模型给出的名字查找工具；未注册时返回 null（由调用方给出脱敏提示） */
    public AiTool find(String name) {
        if (candidates == null) {
            return null;
        }
        for (AiTool tool : candidates) {
            if (tool != null && tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }
}
