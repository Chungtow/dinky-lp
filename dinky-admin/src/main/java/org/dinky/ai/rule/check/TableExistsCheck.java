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

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * {@code api.table_exists}：目标物理表必须已存在。
 *
 * <p>Flink 侧 {@code CREATE TABLE} 只是逻辑映射，不会真建表；目标库没这张表时，
 * 作业能提交成功，第一条数据进来才失败（或 sink 缓冲满才报）。
 *
 * <p>库名从 {@code INSERT INTO <db>.<table>} 取；草稿只给了裸表名时返回 {@code UNKNOWN}
 * （不猜库名——猜错会把合格作业拦掉）。
 *
 * @since 2026/11/02
 */
@Component
@RequiredArgsConstructor
public class TableExistsCheck implements RuleCheck {

    private final PrecheckGateway gateway;

    @Override
    public String kind() {
        return "api.table_exists";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        String sink = SqlKit.unquote(context.facts().sinkTable());
        if (StrUtil.isBlank(sink)) {
            return RuleOutcome.notApplicable("SQL 未声明 sink 目标表");
        }
        int dot = sink.lastIndexOf('.');
        if (dot <= 0) {
            return RuleOutcome.unknown("sink 表名未带库名（" + sink + "），无法校验物理表是否存在");
        }
        String database = sink.substring(0, dot);
        String table = sink.substring(dot + 1);
        Boolean exists = gateway.tableExists(context.getRequestSnapshot(), database, table);
        if (exists == null) {
            return RuleOutcome.unknown("无法连接 " + database + " 所在数据源，表存在性未知");
        }
        if (!exists) {
            return RuleOutcome.fail("目标物理表不存在: " + database + "." + table);
        }
        return RuleOutcome.pass(database + "." + table + " 已存在");
    }
}
