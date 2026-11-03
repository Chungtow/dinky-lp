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

package org.dinky.ai.rule.check;

import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;
import org.dinky.ai.rule.SqlKit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.connector_whitelist}：connector 必须在本集群已安装的白名单内。
 *
 * <p><b>为什么单列一条规则</b>：这是"硬失败"而不是"风格问题"——模型的通用知识里
 * Flink 支持 hive/doris/elasticsearch，但本集群 Flink lib 只有 kafka + jdbc + CH。
 * 写错的代价是提交阶段 {@code Could not find any factories}，排查成本极高
 * （2026-11-03 M1-01 实测：模型给整段作业配了 {@code 'connector' = 'hive'} 和
 * {@code 'hive-version' = '3.1.2'}）。
 *
 * <p>与「平台运行画像」互补：画像负责<b>事先告知</b>，本条负责<b>事后拦截</b>。
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
public class ConnectorWhitelistCheck implements RuleCheck {

    @Override
    public String kind() {
        return "sql.connector_whitelist";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        List<String> allowed = CheckParams.strings(rule, "allowed");
        if (allowed.isEmpty()) {
            return RuleOutcome.unknown("规则未配置 allowed 白名单，跳过 connector 校验");
        }
        Set<String> allowedSet = new LinkedHashSet<>();
        for (String item : allowed) {
            allowedSet.add(item.toLowerCase(Locale.ROOT));
        }
        Set<String> unknown = new LinkedHashSet<>();
        List<String> used = new ArrayList<>();
        for (SqlKit.TableDef table : context.facts().createTables().values()) {
            String connector = table.option("connector");
            if (connector == null || connector.trim().isEmpty()) {
                continue;
            }
            String normalized = connector.trim().toLowerCase(Locale.ROOT);
            used.add(normalized);
            if (!allowedSet.contains(normalized)) {
                unknown.add(normalized);
            }
        }
        if (used.isEmpty()) {
            return RuleOutcome.notApplicable("SQL 未声明 connector（无 CREATE TABLE ... WITH）");
        }
        if (!unknown.isEmpty()) {
            return RuleOutcome.fail("使用了本集群未安装的 connector: " + String.join(", ", unknown) + "（可用: "
                    + String.join(", ", allowedSet) + "）");
        }
        return RuleOutcome.pass("connector 均在白名单内: " + String.join(", ", used));
    }
}
