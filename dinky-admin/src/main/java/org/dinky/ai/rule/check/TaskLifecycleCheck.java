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

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * {@code api.task_lifecycle}：已发布（{@code step=2}）的作业不能直接改。
 *
 * <p><b>F5 口径收窄（S0 发现）</b>：B1~B4 都是 {@code step=2}。若"原样校验"把线上作业直接喂进来，
 * 本条必然 error，与"0 误报"直接矛盾。因此：
 * <b>只有草稿带 taskId（= 有更新意图）时才判定</b>；纯只读校验（无 taskId）判 {@code NOT_APPLICABLE}。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class TaskLifecycleCheck implements RuleCheck {

    private final PrecheckGateway gateway;

    @Override
    public String kind() {
        return "api.task_lifecycle";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        if (context.getTaskId() == null) {
            return RuleOutcome.notApplicable("新建草稿（无 taskId），不涉及已发布作业修改");
        }
        Integer step = gateway.taskStep(context.getTenantId(), context.getTaskId());
        if (step == null) {
            return RuleOutcome.unknown("任务 " + context.getTaskId() + " 状态查询失败");
        }
        if (step == 2) {
            return RuleOutcome.fail("任务 " + context.getTaskId() + " 已发布（step=2），须先下线再改");
        }
        return RuleOutcome.pass("step=" + step);
    }
}
