-- =============================================================================
-- B4 · bench_04_event_minute —— 双 topic 多流聚合（异构消息格式）
--
-- 形态：traccar-cdc（Canal flatMessage JSON） ⨝ traccar-logs（filebeat ECS JSON）
--         → 两路各自解析成 (event_type, source) → UNION ALL → 1 分钟滚动窗口计数
--       → ClickHouse
-- 难度：★★★★；并行度 2，sink 并行度 1，占 4 slots
--
-- 【三个真坑，逐条对应】
--   1) 两路时区口径不同：
--        · canal 侧 eventtime 是**北京时间挂钟字符串**（canal 的 JDBC 连接是 CST）；
--        · filebeat 侧 @timestamp 是**UTC ISO**（traccar-app 容器 TZ=UTC，实测
--          `@timestamp=2026-11-02T09:10:37.738Z` 对应 CST 17:10:37）。
--      ⇒ canal 侧直接 TO_TIMESTAMP；日志侧必须 +8h，否则两路水位线差 8 小时，
--        窗口永远对不齐（这是"看起来在跑、其实一条都没关联"的典型）。
--   2) 日志侧是纯文本 message，只能 REGEXP_EXTRACT；
--        raw 格式没有 ignore-parse-errors 可依赖，必须用 COALESCE/WHERE 兜底，
--        否则一条脏日志就让作业挂（计划 R4）。
--   3) 日志侧**没有设备 id**：`Event id: xxx` 是事件主键，不是设备；
--        设备只在 `[T856f43dd] connected` 这类连接日志里以 Traccar 内部 token 出现，
--        与 MySQL 的数值 deviceid 不是同一命名空间。
--      ⇒ 本作业聚合键取 (window_start, event_type, source)，**不带 device_id**；
--        设备维度由 B3 的 dwd 承担。这不是偷懒，是把"日志侧拿不到的维度"去掉，
--        而不是伪造一个 0 填进去。
--
-- 【为什么同一设备/事件类型要按 source 分开计数】
--   'cdc' 是数据库侧权威口径，'log' 是应用侧观测口径，两者本来就会不一致
--   （日志有采样/重复、CDC 有批量投递延迟）。合并成一行等于把差异抹掉，
--   这张表存在的意义恰恰是让差异可被观察。
-- =============================================================================

-- ---------- 基础运行参数 ----------
SET 'pipeline.name' = 'bench_04_event_minute';
SET 'parallelism.default' = '2';
SET 'table.local-time-zone' = 'Asia/Shanghai';
SET 'execution.checkpointing.interval' = '60s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'execution.checkpointing.timeout' = '10min';
SET 'execution.checkpointing.externalized-checkpoint-retention' = 'RETAIN_ON_CANCELLATION';
SET 'restart-strategy.type' = 'fixed-delay';
SET 'restart-strategy.fixed-delay.attempts' = '3';
SET 'restart-strategy.fixed-delay.delay' = '10s';
SET 'table.exec.state.ttl' = '48h';
-- 【关键】多源/多分区时水位线取"所有输入的最小值"：某个 source 暂时没数据（Canal 是批量
--   投递、filebeat 是低频日志）就会把整个作业的水位线钉住，窗口永远不触发、sink 一行不写。
--   30s 无数据即标记 idle，让水位线越过它（2026-11-02 实测 B4 卡住就是这个原因）。
SET 'table.exec.source.idle-timeout' = '30s';
SET 'table.exec.mini-batch.enabled' = 'true';
SET 'table.exec.mini-batch.allow-latency' = '5s';
SET 'table.exec.mini-batch.size' = '5000';
-- 不设 execution.checkpointing.min-pause（SQL SET 通道无效，改走 customConfig）

-- ---------- 流 1：Kafka / raw（事件表变更） ----------
-- 【必须 raw 不能用 canal-json】本作业要开窗口，而 canal-json 是 changelog format
--   （UPDATE → -U/+U），窗口聚合只吃 insert-only，实测直接
--   "StreamPhysicalWindowAggregate doesn't support consuming update and delete changes"。
--   详见 B2 源表注释 / §8.3 第 19 条。
CREATE TABLE src_events_cdc (
    `payload` STRING,
    `event_type` AS JSON_VALUE(`payload`, '$.data[0].type'),
    -- canal 侧 eventtime 是北京时间挂钟字符串（canal 的 JDBC 连接是 CST）
    `event_time` AS TO_TIMESTAMP(JSON_VALUE(`payload`, '$.data[0].eventtime'),
                                 'yyyy-MM-dd HH:mm:ss'),
    WATERMARK FOR `event_time` AS `event_time` - INTERVAL '10' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'traccar-cdc',
    'properties.bootstrap.servers' = 'kafka03:29092,kafka04:29092,kafka05:29092',
    'properties.group.id' = 'bench_04_event_minute_cdc_v1',
    'scan.startup.mode' = 'latest-offset',
    'format' = 'raw'
);

-- ---------- 流 2：Kafka / raw（filebeat ECS JSON 日志） ----------
CREATE TABLE src_logs (
    `payload` STRING,
    -- 日志侧：@timestamp 是 UTC ISO（2026-11-02T09:10:37.738Z）→ 转北京时间挂钟需 +8h。
    -- 用 SUBSTR 切成 "yyyy-MM-dd HH:mm:ss" 再 TO_TIMESTAMP，避开格式串里的引号地狱。
    `event_time` AS TO_TIMESTAMP(
                        CONCAT(SUBSTR(JSON_VALUE(`payload`, '$."@timestamp"'), 1, 10), ' ',
                               SUBSTR(JSON_VALUE(`payload`, '$."@timestamp"'), 12, 8)),
                        'yyyy-MM-dd HH:mm:ss') + INTERVAL '8' HOUR,
    WATERMARK FOR `event_time` AS `event_time` - INTERVAL '10' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'traccar-logs',
    'properties.bootstrap.servers' = 'kafka03:29092,kafka04:29092,kafka05:29092',
    -- 【独立 group.id】与 cdc 侧不是同一个消费组，否则两个源会互相抢分区
    'properties.group.id' = 'bench_04_event_minute_log_v1',
    'scan.startup.mode' = 'latest-offset',
    'format' = 'raw'
);

-- ---------- 目标：ClickHouse ----------
CREATE TABLE ch_event_minute (
    `window_start` TIMESTAMP(3),
    `window_end`   TIMESTAMP(3),
    `event_type`   STRING,
    `source`       STRING,
    `event_cnt`    BIGINT
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:clickhouse://doris05:38123/traccar',
    'table-name' = 'dws_event_minute',
    'username' = 'default',
    'password' = '__CH_PASSWORD__',
    'sink.buffer-flush.max-rows' = '500',
    'sink.buffer-flush.interval' = '5s',
    'sink.max-retries' = '3',
    'sink.parallelism' = '1'
);

-- ---------- 两路统一结构 → 1 分钟滚动窗口计数 ----------
-- 必须先把 UNION ALL 落成视图再喂给窗口 TVF：TUMBLE 的 TABLE 参数只接受表/视图引用，
-- 不接受内联子查询（写内联会报 "Encountered error while parsing" 一类的语法错）。
CREATE TEMPORARY VIEW v_events_unified AS
-- 数据库侧（权威口径）
SELECT event_time, event_type, 'cdc' AS source
FROM src_events_cdc
WHERE JSON_VALUE(`payload`, '$.table') = 'tc_events'   -- 一个 topic 混全库变更
  AND event_type IS NOT NULL AND event_type <> ''
UNION ALL
-- 日志侧（观测口径）：message 形如
--   "2026-11-02 09:10:37  INFO: Event id: 865118228539340, time: ..., type: deviceOnline, notifications: 0"
SELECT event_time,
       COALESCE(REGEXP_EXTRACT(JSON_VALUE(`payload`, '$.message'), 'type: ([a-zA-Z]+)', 1), '') AS event_type,
       'log' AS source
FROM src_logs
WHERE JSON_VALUE(`payload`, '$."@timestamp"') IS NOT NULL;

INSERT INTO ch_event_minute
SELECT
    window_start,
    window_end,
    event_type,
    source,
    COUNT(*) AS event_cnt
FROM TABLE(
    TUMBLE(TABLE v_events_unified, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)
)
WHERE event_type IS NOT NULL AND event_type <> ''   -- 兜底：解析不出来的日志行直接不进窗口
GROUP BY window_start, window_end, event_type, source;
