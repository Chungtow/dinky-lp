-- =============================================================================
-- B2 · bench_02_position_top5 —— 单流窗口聚合（Top-5 的"可落 CH"形态）
--
-- 形态：Kafka(traccar-cdc / tc_positions) → 5 分钟滚动窗口 → 每设备位置条数 → ClickHouse
-- 难度：★★★；并行度 3，sink 并行度 1，占 4 slots（计划 §3.3）
--
-- 【与计划 v1.1 的差异（有意为之，实测得出）】
--   计划原设计是"按自然日累计 + ROW_NUMBER 取 Top-5"。这在 CH 侧跑不通：
--     · 自研 ClickHouseDialect 是 **insert-only**（getUpsertStatement 返回 empty），
--       所以 sink 不能声明 PRIMARY KEY NOT ENFORCED；
--     · 不声明 PK 时，JDBC sink 的 validatePrimaryKey 会拒绝任何含 update/delete
--       的查询："please declare primary key for sink table when query contains
--       update/delete record."；
--     · 而"日累计计数"和 ROW_NUMBER 排名**都是回撤/更新流**。
--   → 结论：往 CH 落数据必须先做成 append-only。本作业改用**窗口聚合**：
--     未开 early-fire 时，窗口只在 watermark 越过窗口末尾时输出一次，天然 insert-only。
--   → Top-N 因此从"作业内算子"降级为"查询端计算"（见文件末尾的查询样例）。
--     ROW_NUMBER 版本保留在注释里作为反面语料（计划 §8.3 第 20 条）。
--
-- 【时区口径】traccar-app 与 canal 的 JDBC 连接都是 CST，flatMessage 的 servertime
--   是**北京时间挂钟字符串**；这里用 TO_TIMESTAMP 显式解析，配合
--   table.local-time-zone=Asia/Shanghai，不做任何手工 ±时区。
--   注意：traccar-logs 的 @timestamp 才是 UTC（见 B4），两者不能共用一个公式。
-- =============================================================================

-- ---------- 基础运行参数 ----------
SET 'pipeline.name' = 'bench_02_position_top5';
SET 'parallelism.default' = '3';
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
-- mini-batch：窗口计数是增量聚合，开 mini-batch 降低输出频率；
-- size 必须显式 > 0（默认 -1 会直接报错，反面清单 #3）
SET 'table.exec.mini-batch.enabled' = 'true';
SET 'table.exec.mini-batch.allow-latency' = '5s';
SET 'table.exec.mini-batch.size' = '5000';
-- 不设 execution.checkpointing.min-pause（SQL SET 通道无效，改走 customConfig）

-- ---------- 源：Kafka / raw + JSON_VALUE ----------
-- 【为什么不用 format='canal-json'】canal-json 是 **changelog format**：Kafka source 会产出
--   +I/-U/+U/-D 四种 RowKind。窗口聚合（StreamPhysicalWindowAggregate）只吃 insert-only，
--   实测直接报：
--     TableException: StreamPhysicalWindowAggregate doesn't support consuming
--     update and delete changes which is produced by node TableSourceScan(...)
--   ⇒ 凡是**要开窗口**的作业，Canal 数据必须用 raw 读进来自己解析（B1 同理）。
--   不开窗口、只做无界 GROUP BY 的作业才可以用 canal-json（聚合算子能吃回撤）。
CREATE TABLE src_tc_positions (
    `payload` STRING,
    -- 设备 id：Canal 把数值序列化成字符串，必须 CAST（反面清单 #13）
    `device_id`  AS CAST(JSON_VALUE(`payload`, '$.data[0].deviceid') AS BIGINT),
    -- 事件时间：servertime 是 tc_positions 的真实列（DATETIME），Canal 按 CST 渲染成
    -- 北京时间挂钟字符串 → 显式 TO_TIMESTAMP 解析，不依赖隐式转换
    `event_time` AS TO_TIMESTAMP(JSON_VALUE(`payload`, '$.data[0].servertime'),
                                 'yyyy-MM-dd HH:mm:ss'),
    -- 水位线：容忍 30s 乱序（多分区并发 + canal 批量投递）
    WATERMARK FOR `event_time` AS `event_time` - INTERVAL '30' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'traccar-cdc',
    'properties.bootstrap.servers' = 'kafka03:29092,kafka04:29092,kafka05:29092',
    'properties.group.id' = 'bench_02_position_top5_v1',
    'scan.startup.mode' = 'latest-offset',
    'format' = 'raw'
);

-- ---------- 目标：ClickHouse ----------
-- 键 (window_start, device_id)：一个窗口里一台设备一行，重复写靠 ReplacingMergeTree(ver) 去重。
-- ver 由 CH 侧 DEFAULT 生成（写入时刻毫秒）——窗口聚合虽是 append-only，
-- 但作业重启会重放未 checkpoint 的窗口，同键重写要靠 ver 决定"谁是最新"。
CREATE TABLE ch_position_window (
    `window_start` TIMESTAMP(3),
    `window_end`   TIMESTAMP(3),
    `device_id`    BIGINT,
    `position_cnt` BIGINT
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:clickhouse://doris05:38123/traccar',
    'table-name' = 'dws_device_position_window',
    'username' = 'default',
    'password' = '__CH_PASSWORD__',
    'sink.buffer-flush.max-rows' = '500',
    'sink.buffer-flush.interval' = '5s',
    'sink.max-retries' = '3',
    -- 窗口结束一次吐出整窗，单并行度写避免同键并发乱序
    'sink.parallelism' = '1'
);

-- ---------- 5 分钟窗口内每设备的位置条数 ----------
INSERT INTO ch_position_window
SELECT
    window_start,
    window_end,
    device_id,
    COUNT(*)      AS position_cnt
FROM TABLE(
    TUMBLE(TABLE src_tc_positions, DESCRIPTOR(event_time), INTERVAL '5' MINUTE)
)
WHERE device_id IS NOT NULL         -- 反面清单 #8：NULL 设备会聚成一个假分组
  AND JSON_VALUE(payload, '$.table') = 'tc_positions'   -- 一个 topic 混全库变更，必须按表过滤
GROUP BY window_start, window_end, device_id;

-- =============================================================================
-- 【反面语料 · 不要直接启用的写法】窗口内 Top-5 排名
--
-- INSERT INTO ch_position_window
-- SELECT window_start, window_end, device_id, position_cnt FROM (
--     SELECT window_start, window_end, deviceid AS device_id, COUNT(*) AS position_cnt,
--            ROW_NUMBER() OVER (PARTITION BY window_start
--                               ORDER BY COUNT(*) DESC, deviceid ASC) AS rank_num
--     FROM TABLE(TUMBLE(TABLE src_tc_positions, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
--     WHERE deviceid IS NOT NULL
--     GROUP BY window_start, window_end, deviceid
-- ) WHERE rank_num <= 5;
--
-- 为什么不能启用：ROW_NUMBER 输出带 UPDATE_BEFORE/UPDATE_AFTER →
--   `JdbcDynamicTableSink.getChangelogMode` 走到 `validatePrimaryKey` →
--   表没声明 PK 就抛
--     ValidationException: please declare primary key for sink table when
--     query contains update/delete record.
--   一旦声明 PK，JDBC sink 改用 upsert 执行器 → 自研方言 getUpsertStatement
--   返回 empty → 构建期失败。
--   ⇒ CH + JDBC 方言这条路上"回撤流"没有落点。
--
-- Top-5 的正确打开方式（查询端算，`count() FINAL` 去重后再排序）：
--   SELECT window_start, device_id, position_cnt
--   FROM traccar.dws_device_position_window FINAL
--   WHERE window_start = (SELECT max(window_start) FROM traccar.dws_device_position_window)
--   ORDER BY position_cnt DESC, device_id ASC
--   LIMIT 5;
--   —— tie-breaker 用 device_id ASC，否则并列时名次来回跳（反面清单 #7）。
-- =============================================================================
