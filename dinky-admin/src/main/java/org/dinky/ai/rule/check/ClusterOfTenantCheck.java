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

import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * {@code api.cluster_of_tenant}：clusterId 必须属于当前租户。
 *
 * <p>填了别的租户的集群时接口不会友好报错：{@code submitTask} 抛
 * {@code NullPointerException at RemoteStreamExecutor.<init>}，最终被包成一句
 * {@code Instance class [...] error!}（team_a 的集群 id 是 4，填 1 属 tenant 1）。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class ClusterOfTenantCheck implements RuleCheck {

    private final PrecheckGateway gateway;

    @Override
    public String kind() {
        return "api.cluster_of_tenant";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        if (context.getClusterId() == null) {
            return RuleOutcome.notApplicable("草稿未指定 clusterId");
        }
        Set<Integer> ids = gateway.tenantClusterIds(context.getTenantId());
        if (ids == null || ids.isEmpty()) {
            return RuleOutcome.unknown("当前租户的集群清单不可用");
        }
        if (!ids.contains(context.getClusterId())) {
            return RuleOutcome.fail("clusterId=" + context.getClusterId() + " 不属于当前租户（可用: " + ids + "）");
        }
        return RuleOutcome.pass("clusterId=" + context.getClusterId());
    }
}
