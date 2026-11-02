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

import org.dinky.ai.rule.PrecheckGateway;
import org.dinky.ai.rule.Rule;
import org.dinky.ai.rule.RuleCheck;
import org.dinky.ai.rule.RuleContext;
import org.dinky.ai.rule.RuleOutcome;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * {@code api.env_statement_contains}：作业挂载的 env 必须真的带 Flink 参数。
 *
 * <p>Standalone 下 Dinky 不读 {@code flink-conf.yaml}：env 里只有 catalog（如 96 DefaultCatalog，384B）时，
 * 作业会"裸跑"—— checkpoint 不开、时区跑 UTC、mini-batch 关闭，且没有任何报错。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class EnvStatementContainsCheck implements RuleCheck {

    private final PrecheckGateway gateway;

    @Override
    public String kind() {
        return "api.env_statement_contains";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        if (context.getEnvId() == null) {
            return RuleOutcome.notApplicable("草稿未指定 envId");
        }
        String statement = gateway.envStatement(context.getTenantId(), context.getEnvId());
        if (statement == null) {
            return RuleOutcome.unknown("env " + context.getEnvId() + " 查询失败或不存在");
        }
        List<String> missing = new ArrayList<>();
        for (String key : CheckParams.strings(rule, "must_contain")) {
            if (StrUtil.isNotBlank(key) && !statement.contains(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return RuleOutcome.fail("env " + context.getEnvId() + " 缺少参数: " + String.join(", ", missing));
        }
        return RuleOutcome.pass("env " + context.getEnvId() + " 参数齐备");
    }
}
