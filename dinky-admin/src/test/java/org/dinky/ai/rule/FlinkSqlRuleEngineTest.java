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

import org.dinky.ai.rule.check.AppendOnlyCheck;
import org.dinky.ai.rule.check.ColumnNullSafetyCheck;
import org.dinky.ai.rule.check.ConnectorWhitelistCheck;
import org.dinky.ai.rule.check.ContainsCheck;
import org.dinky.ai.rule.check.DorisAggKeyCheck;
import org.dinky.ai.rule.check.DorisUpsertCheck;
import org.dinky.ai.rule.check.JdbcUrlConformanceCheck;
import org.dinky.ai.rule.check.KafkaTopicKnownCheck;
import org.dinky.ai.rule.check.LookupConformanceCheck;
import org.dinky.ai.rule.check.NotContainsCheck;
import org.dinky.ai.rule.check.RequiredSetCheck;
import org.dinky.ai.rule.check.SetAbsentCheck;
import org.dinky.ai.rule.check.SetValueCheck;
import org.dinky.ai.rule.check.SinkPersistentCheck;
import org.dinky.ai.rule.check.SinkTableExistsCheck;
import org.dinky.ai.rule.check.UndefinedVariableCheck;
import org.dinky.service.FragmentVariableService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import cn.hutool.core.io.IoUtil;

/**
 * 规则引擎回归：B1~B4 必须 0 误报，且每个反例必须命中。
 *
 * <p><b>这个测试就是"零误报"的守门人</b>：S0 发现的 F1（注释里写 ROW_NUMBER）、
 * F2（窗口内 GROUP BY）、F3（changelog format 条件化）只要有一条退化，B1~B4 会立刻红。
 *
 * @since 2026/11/02
 */
public class FlinkSqlRuleEngineTest {

    private static FlinkSqlRuleEngine engine;

    @BeforeClass
    public static void setUp() {
        List<RuleCheck> checks = Arrays.asList(
                new AppendOnlyCheck(),
                new ConnectorWhitelistCheck(),
                new JdbcUrlConformanceCheck(),
                new NotContainsCheck(),
                new SetAbsentCheck(),
                new RequiredSetCheck(),
                new SetValueCheck(),
                new ContainsCheck(),
                new LookupConformanceCheck(),
                new ColumnNullSafetyCheck(),
                new DorisUpsertCheck(),
                new DorisAggKeyCheck(),
                new SinkPersistentCheck(),
                new UndefinedVariableCheck(),
                new KafkaTopicKnownCheck(),
                new SinkTableExistsCheck());
        engine = new FlinkSqlRuleEngine(new RuleLoader(), checks);
    }

    @Test
    public void baselineHasTwentyTwoRules() {
        // 2026-11-04：FLINK-SINK-004（调试 connector 不能当交付 sink）
        //           + FLINK-CONNECTOR-002 / FLINK-SOURCE-001 / FLINK-SINK-005 → 22 → 26
        Assert.assertEquals(26, new RuleLoader().load(null).size());
    }

    /**
     * 臆造的 {@code ${...}} 必须判 fail。
     *
     * <p>2026-11-04 M1-01 第六轮实测：模型产出 {@code ${kafka.bootstrap.servers}}、
     * {@code ${clickhouse.username}} 等一整套占位符（本租户根本没有全局变量），
     * 草稿层面完全看不出来，提交阶段必报 does not exist。
     */
    @Test
    public void undefinedVariableIsBlocking() {
        FragmentVariableService variableService = Mockito.mock(FragmentVariableService.class);
        Mockito.when(variableService.listEnabledAll()).thenReturn(new ArrayList<>());
        List<RuleCheck> onlyVariableCheck = new ArrayList<>();
        onlyVariableCheck.add(new UndefinedVariableCheck(variableService));
        FlinkSqlRuleEngine local = new FlinkSqlRuleEngine(new RuleLoader(), onlyVariableCheck);

        String sql = "CREATE TABLE src (id INT) WITH ('connector' = 'kafka', "
                + "'topic' = 'traccar-cdc', "
                + "'properties.bootstrap.servers' = '${kafka.bootstrap.servers}');\n"
                + "CREATE TABLE sink (id INT) WITH ('connector' = 'print');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = local.validateDraft(contextOf(sql, null, 2));
        boolean blocking = false;
        for (RuleViolation violation : violations) {
            if ("FLINK-CONNECTOR-002".equals(violation.getId()) && violation.getStatus() == RuleStatus.FAIL) {
                blocking = true;
            }
        }
        Assert.assertTrue("未定义的 ${...} 必须 fail，实际: " + describe(violations), blocking);
    }

    /**
     * 127.0.0.1 必须被拦：Flink 在 TaskManager 上建连，回环地址指的是 TM 自己。
     *
     * <p>2026-11-03 M1-01 实测模型把 source/sink 都写成 {@code jdbc:mysql://127.0.0.1:3306/traccar}，
     * 而 {@code jdbc} 在 connector 白名单内，旧规则集完全没拦住。
     */
    /**
     * 绑定到作业、但服务端取不到作业名（跨租户 / 已删除）时，PARAM-004 必须判 <b>unknown</b>。
     *
     * <p>加固前的行为是 expected=null → 跳过比对 → <b>pass</b>，等于"模型自报同名"或"干脆不传 name"
     * 都能让这条规则形同虚设（2026-11-03 实测被绕过）。
     */
    @Test
    public void boundTaskWithoutTaskNameIsUnknown() {
        RuleContext context = RuleContext.builder()
                .sql(resource("ai/benchmarks/B1_devices_ods.sql"))
                .taskId(124)
                .taskName(null)
                .parallelism(2)
                .clusterId(4)
                .envId(122)
                .build();
        List<RuleViolation> violations = engine.validateDraft(context);
        boolean unknown = false;
        for (RuleViolation violation : violations) {
            if ("FLINK-PARAM-004".equals(violation.getId()) && violation.getStatus() == RuleStatus.UNKNOWN) {
                unknown = true;
            }
        }
        Assert.assertTrue("已绑定作业但无作业名时必须 unknown，实际: " + describe(violations), unknown);
    }

    /**
     * print 当交付 sink 必须判 fail。
     *
     * <p>2026-11-04 M1-01 实测：模型把 {@code 'connector' = 'print'} 写在结果表上。
     * print 确实在连接器白名单内（集群已装），所以 {@code FLINK-CONNECTOR-001} 会判 pass，
     * 而它只把数据打到 TaskManager 日志 —— 作业 RUNNING、无异常、一行没落库。
     */
    @Test
    public void printAsSinkIsBlocking() {
        String sql = "CREATE TABLE src (id INT) WITH ('connector' = 'kafka', "
                + "'properties.bootstrap.servers' = 'kafka03:29092', 'topic' = 'traccar-cdc');\n"
                + "CREATE TABLE sink (id INT) WITH ('connector' = 'print');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, null, 2));
        boolean blocking = false;
        for (RuleViolation violation : violations) {
            if ("FLINK-SINK-004".equals(violation.getId()) && violation.getStatus() == RuleStatus.FAIL) {
                blocking = true;
            }
        }
        Assert.assertTrue("print 当交付 sink 必须 fail，实际: " + describe(violations), blocking);
    }

    /** 反过来：jdbc(ClickHouse) 当 sink 不能被这条规则误伤 */
    @Test
    public void jdbcSinkPassesPersistentCheck() {
        String sql = "CREATE TABLE src (id INT) WITH ('connector' = 'kafka', "
                + "'properties.bootstrap.servers' = 'kafka03:29092', 'topic' = 'traccar-cdc');\n"
                + "CREATE TABLE sink (id INT) WITH ('connector' = 'jdbc', "
                + "'url' = 'jdbc:clickhouse://doris05:38123/traccar', 'table-name' = 'dws_x');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, null, 2));
        for (RuleViolation violation : violations) {
            if ("FLINK-SINK-004".equals(violation.getId()) && violation.getStatus() == RuleStatus.FAIL) {
                Assert.fail("jdbc sink 被 FLINK-SINK-004 误伤: " + describe(violations));
            }
        }
    }

    @Test
    public void loopbackJdbcUrlIsBlocking() {
        String sql = "CREATE TABLE src (id INT) WITH ('connector' = 'jdbc', "
                + "'url' = 'jdbc:mysql://127.0.0.1:3306/traccar', 'table-name' = 'tc_positions');\n"
                + "CREATE TABLE sink (id INT) WITH ('connector' = 'jdbc', "
                + "'url' = 'jdbc:clickhouse://doris05:38123/traccar', 'table-name' = 'ods_x');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, null, 2));
        List<String> blockingIds = new ArrayList<>();
        for (RuleViolation violation : violations) {
            if (violation.blocking()) {
                blockingIds.add(violation.getId());
            }
        }
        Assert.assertTrue("应命中 FLINK-URL-001，实际: " + blockingIds, blockingIds.contains("FLINK-URL-001"));
    }

    @Test
    public void b1ToB4HaveNoBlockingViolation() {
        for (Object[] bench : benchmarks()) {
            String name = (String) bench[0];
            RuleContext context = contextOf((String) bench[1], (String) bench[2], (Integer) bench[3]);
            List<RuleViolation> violations = engine.validateDraft(context);
            List<RuleViolation> blocking = new ArrayList<>();
            for (RuleViolation violation : violations) {
                if (violation.blocking()) {
                    blocking.add(violation);
                }
            }
            Assert.assertTrue(name + " 出现误报: " + describe(blocking), blocking.isEmpty());
        }
    }

    @Test
    public void rowNumberVersionOfB2HitsAppendOnly() {
        String sql = resource("ai/benchmarks/B2_position_top5.sql");
        String broken = sql.replace("INSERT INTO", "INSERT INTO") + "\nSELECT * FROM (SELECT id, "
                + "ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY ts DESC) AS rn FROM t) WHERE rn <= 5;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(broken, "bench_02_position_top5", 3));
        Assert.assertTrue(containsFail(violations, "FLINK-SINK-001"));
    }

    @Test
    public void canalJsonInWindowJobHitsFormatRule() {
        String sql = resource("ai/benchmarks/B2_position_top5.sql");
        String broken = sql.replace("'format' = 'raw'", "'format' = 'canal-json'");
        Assert.assertNotEquals(sql, broken);
        List<RuleViolation> violations = engine.validateDraft(contextOf(broken, "bench_02_position_top5", 3));
        Assert.assertTrue(containsFail(violations, "FLINK-FORMAT-001"));
    }

    @Test
    public void canalJsonWithoutWindowIsAllowed() {
        String sql = "SET 'table.local-time-zone' = 'Asia/Shanghai';\n"
                + "CREATE TABLE src (`id` INT) WITH ('connector' = 'kafka', 'format' = 'canal-json');\n"
                + "CREATE TABLE sink (`id` INT) WITH ('connector' = 'jdbc', 'url' = 'jdbc:clickhouse://ch:8123/db');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, "unbounded_only", 1));
        Assert.assertFalse(containsFail(violations, "FLINK-FORMAT-001"));
    }

    @Test
    public void lookupWithoutHintHitsLookupRule() {
        String sql = resource("ai/benchmarks/B3_events_enriched.sql");
        String broken = sql.replace("/*+ LOOKUP('table' = 'd') */", "");
        Assert.assertNotEquals(sql, broken);
        List<RuleViolation> violations = engine.validateDraft(contextOf(broken, "bench_03_events_enriched", 2));
        Assert.assertTrue(containsFail(violations, "FLINK-LOOKUP-001"));
    }

    @Test
    public void missingIdleTimeoutHitsParamRule() {
        String sql = resource("ai/benchmarks/B4_event_minute.sql");
        String broken = sql.replaceAll("(?m)^\\s*SET 'table\\.exec\\.source\\.idle-timeout'[^;]*;\\s*$", "");
        Assert.assertNotEquals(sql, broken);
        List<RuleViolation> violations = engine.validateDraft(contextOf(broken, "bench_04_event_minute", 2));
        Assert.assertTrue(containsFail(violations, "FLINK-PARAM-003"));
    }

    @Test
    public void minPauseInSetHitsParamRule() {
        String sql =
                resource("ai/benchmarks/B1_devices_ods.sql") + "\nSET 'execution.checkpointing.min-pause' = '30s';\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, "bench_01_devices_ods", 2));
        Assert.assertTrue(containsFail(violations, "FLINK-PARAM-001"));
    }

    @Test
    public void miniBatchUnlimitedHitsValueRule() {
        String sql = resource("ai/benchmarks/B1_devices_ods.sql")
                .replace("SET 'table.exec.mini-batch.size' = '5000'", "SET 'table.exec.mini-batch.size' = '-1'");
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, "bench_01_devices_ods", 2));
        Assert.assertTrue(containsFail(violations, "FLINK-MINIBATCH-001"));
    }

    @Test
    public void timezoneMissingHitsTzRule() {
        String sql = resource("ai/benchmarks/B2_position_top5.sql")
                .replaceAll("(?m)^\\s*SET 'table\\.local-time-zone'[^;]*;\\s*$", "");
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, "bench_02_position_top5", 3));
        Assert.assertTrue(containsFail(violations, "FLINK-TZ-001"));
    }

    @Test
    public void hiveConnectorHitsConnectorWhitelist() {
        // 2026-11-03 M1-01 的真实坏例：模型给整段作业配了 hive connector
        String sql = "CREATE TABLE src (`id` INT) WITH ('connector' = 'hive', 'hive-version' = '3.1.2');\n"
                + "CREATE TABLE sink (`id` INT) WITH ('connector' = 'jdbc', 'url' = 'jdbc:clickhouse://ch:8123/traccar');\n"
                + "INSERT INTO sink SELECT id FROM src;\n";
        List<RuleViolation> violations = engine.validateDraft(contextOf(sql, "uat_connector_case", 1));
        Assert.assertTrue(containsFail(violations, "FLINK-CONNECTOR-001"));
    }

    private static List<Object[]> benchmarks() {
        List<Object[]> list = new ArrayList<>();
        list.add(new Object[] {"B1", resource("ai/benchmarks/B1_devices_ods.sql"), "bench_01_devices_ods", 2});
        list.add(new Object[] {"B2", resource("ai/benchmarks/B2_position_top5.sql"), "bench_02_position_top5", 3});
        list.add(new Object[] {"B3", resource("ai/benchmarks/B3_events_enriched.sql"), "bench_03_events_enriched", 2});
        list.add(new Object[] {"B4", resource("ai/benchmarks/B4_event_minute.sql"), "bench_04_event_minute", 2});
        return list;
    }

    private static RuleContext contextOf(String sql, String taskName, Integer parallelism) {
        return RuleContext.builder()
                .sql(sql)
                .taskName(taskName)
                .parallelism(parallelism)
                .clusterId(4)
                .envId(122)
                .build();
    }

    private static boolean containsFail(List<RuleViolation> violations, String id) {
        for (RuleViolation violation : violations) {
            if (id.equals(violation.getId()) && violation.getStatus() == RuleStatus.FAIL) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<RuleViolation> violations) {
        StringBuilder sb = new StringBuilder();
        for (RuleViolation violation : violations) {
            sb.append('[')
                    .append(violation.getId())
                    .append("] ")
                    .append(violation.getDetail())
                    .append("; ");
        }
        return sb.toString();
    }

    private static String resource(String path) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            Assert.assertNotNull("missing test resource: " + path, in);
            return IoUtil.readUtf8(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
