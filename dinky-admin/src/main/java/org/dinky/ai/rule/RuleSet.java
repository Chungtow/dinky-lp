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

package org.dinky.ai.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次加载得到的规则集合（按 id 索引，保序）。
 *
 * <p>合并语义：内置基线 ∪ 部署层覆盖，<b>同 id 后者整体覆盖前者</b>
 * （只覆盖声明字段由 loader 在合并时逐字段处理，见 {@link RuleLoader}）。
 *
 * @since 2026/11/02
 */
public class RuleSet {

    private final Map<String, Rule> byId = new LinkedHashMap<>();

    public RuleSet() {}

    public RuleSet(List<Rule> rules) {
        if (rules != null) {
            for (Rule rule : rules) {
                if (rule != null && rule.getId() != null) {
                    byId.put(rule.getId(), rule);
                }
            }
        }
    }

    public List<Rule> rules() {
        return new ArrayList<>(byId.values());
    }

    public Rule get(String id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** 可进提示词的规则（draft + primary + enabled + prompt 非空），按 YAML 顺序 */
    public List<Rule> promptedRules() {
        List<Rule> prompted = new ArrayList<>();
        for (Rule rule : byId.values()) {
            if (rule.prompted()) {
                prompted.add(rule);
            }
        }
        return prompted;
    }

    /** 渲染成提示词区块（S8：替代 {@code FlinkContextProvider.RULES} 常量） */
    public String renderPrompt() {
        List<Rule> prompted = promptedRules();
        if (prompted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n### FlinkSQL 作业红线（规则库自动渲染，写错位置会静默失效，必须遵守）\n");
        for (Rule rule : prompted) {
            sb.append("- ").append(rule.getPrompt().trim()).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Rule> asMap() {
        return Collections.unmodifiableMap(byId);
    }
}
