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
import org.dinky.ai.rule.SqlKit;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code name.unique_across_tasks}：group.id / sink.label-prefix 必须逐作业唯一。
 *
 * <p>多个作业共用同一个 Kafka group.id 会瓜分分区：每个作业只拿到一部分分区，
 * 数据量凭空少一半，且两边都"看起来正常"（B4 双源实测）。
 *
 * <p><b>为什么属 precheck</b>：跨任务比较必须查库（{@link PrecheckGateway#occupiedNames}），
 * 光看单条 SQL 判不出来。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NameUniqueCheck implements RuleCheck {

    private final PrecheckGateway gateway;

    @Override
    public String kind() {
        return "name.unique_across_tasks";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        List<String> fields = CheckParams.strings(rule, "fields");
        List<String> values = new ArrayList<>();
        for (SqlKit.TableDef table : context.facts().createTables().values()) {
            for (String field : fields) {
                String value = table.option(field);
                if (StrUtil.isNotBlank(value) && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            return RuleOutcome.notApplicable("SQL 未声明 " + String.join(" / ", fields));
        }
        List<String> occupied = gateway.occupiedNames(context.getTenantId());
        if (occupied == null) {
            return RuleOutcome.unknown("命名名册不可用（查库失败），跳过唯一性判定");
        }
        if (context.getTaskId() != null) {
            // F10：更新既有作业时，名册无法区分"自己占用的"与"别人占用的" ——
            // 此时不判 pass（会漏拦）也不判 fail（会误拦），交由人工确认。
            return RuleOutcome.unknown("更新既有作业：跨任务唯一性无法排除自身占用，请人工确认 " + String.join(", ", values));
        }
        List<String> conflicts = new ArrayList<>();
        for (String value : values) {
            for (String used : occupied) {
                if (used != null && used.equalsIgnoreCase(value)) {
                    conflicts.add(value);
                    break;
                }
            }
        }
        if (!conflicts.isEmpty()) {
            return RuleOutcome.fail("与其他作业重名: " + String.join(", ", conflicts));
        }
        return RuleOutcome.pass("已检查: " + String.join(", ", values));
    }
}
