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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code sql.jdbc_url_conformance}：jdbc url 不得指向本机地址。
 *
 * <p><b>为什么单列一条</b>：Flink 的 JDBC connector 在 <b>TaskManager</b> 上建连，{@code 127.0.0.1}
 * 指向的是 TM 自己，而不是开发机上的 MySQL / ClickHouse —— 提交阶段必报 {@code Connection refused}。
 * 这类错误在规则库里原本完全失明：{@code jdbc} 在 connector 白名单内，{@code FLINK-CONNECTOR-001}
 * 根本不会拦（2026-11-03 M1-01 实测：模型同时把 source 与 sink 写成
 * {@code jdbc:mysql://127.0.0.1:3306/traccar}）。
 *
 * <p>只判"本机地址"这一确定性事实，<b>不判断该不该用 MySQL</b>：MySQL 维表 lookup 在本集群是
 * 合法用法（{@code manager:33306}），把"jdbc + mysql"一律判错会造成误报。
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
public class JdbcUrlConformanceCheck implements RuleCheck {

    /** 在 TM 上毫无意义的回环地址（含 IPv6） */
    private static final Set<String> LOOPBACK_HOSTS =
            new HashSet<>(Arrays.asList("127.0.0.1", "localhost", "0.0.0.0", "::1", "[::1]"));

    @Override
    public String kind() {
        return "sql.jdbc_url_conformance";
    }

    @Override
    public RuleOutcome check(Rule rule, RuleContext context) {
        Map<String, SqlKit.TableDef> tables = context.facts().createTables();
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (SqlKit.TableDef table : tables.values()) {
            String url = table.option("url");
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            checked++;
            String host = hostOf(url);
            if (host == null || host.isEmpty()) {
                continue;
            }
            if (LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                bad.add(table.getName() + "(" + host + ")");
            }
        }
        if (checked == 0) {
            return RuleOutcome.notApplicable("CREATE TABLE 中未声明 jdbc url");
        }
        if (!bad.isEmpty()) {
            return RuleOutcome.fail("jdbc url 指向本机地址: " + String.join(", ", bad)
                    + "（Flink 在 TaskManager 上建连，127.0.0.1 是 TM 自己，提交必报 Connection refused）");
        }
        return RuleOutcome.pass("jdbc url 均非本机地址（共 " + checked + " 个）");
    }

    /**
     * 从 jdbc url 取 host：{@code jdbc:<sub>://[user:pass@]host[:port]/db}。
     *
     * @return host（无 host 的形态如 {@code jdbc:mysql:///db} 返回空串）；非 {@code //} 形态返回 {@code null}
     */
    static String hostOf(String url) {
        int idx = url.indexOf("//");
        if (idx < 0) {
            return null;
        }
        String rest = url.substring(idx + 2);
        int end = rest.length();
        for (char stop : new char[] {'/', '?', ';'}) {
            int i = rest.indexOf(stop);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String hostPort = rest.substring(0, end).trim();
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        if (hostPort.startsWith("[")) {
            // IPv6 字面量：[::1]:8123
            int close = hostPort.indexOf(']');
            return close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        }
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            hostPort = hostPort.substring(0, colon);
        }
        return hostPort;
    }
}
