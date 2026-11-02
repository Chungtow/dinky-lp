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
import org.dinky.ai.rule.RuntimeGateway;

import java.util.Map;

import org.springframework.stereotype.Component;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * {@code runtime.checkpoint_ok}：checkpoint 必须真的在跑。
 *
 * <p>参数写对了不等于生效：env 挂错、customConfig 被覆盖、sink 卡住都会让 checkpoint
 * 一直 in_progress 或反复失败，而作业状态仍是 RUNNING。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class CheckpointOkCheck implements RuleCheck {

    private final RuntimeGateway gateway;

    @Override
    public String kind() {
        return "runtime.checkpoint_ok";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        if (StrUtil.isBlank(context.getJobId())) {
            return RuleOutcome.notApplicable("尚无 Flink 作业 id（未提交）");
        }
        Integer completed = gateway.completedCheckpoints(context.getJobId());
        Integer failed = gateway.failedCheckpoints(context.getJobId());
        if (completed == null || failed == null) {
            return RuleOutcome.unknown("checkpoint 统计获取失败: " + context.getJobId());
        }
        Map<String, Object> counts = CheckParams.child(rule, "counts");
        int minCompleted = Convert.toInt(counts.get("completed_gte"), 1);
        int maxFailed = Convert.toInt(counts.get("failed_eq"), 0);
        if (completed < minCompleted || failed > maxFailed) {
            return RuleOutcome.fail("completed=" + completed + " failed=" + failed + "（要求 completed>=" + minCompleted
                    + " 且 failed=" + maxFailed + "）");
        }
        return RuleOutcome.pass("completed=" + completed + " failed=" + failed);
    }
}
