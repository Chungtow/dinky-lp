-- =============================================================================
-- B3 · bench_03_events_enriched —— 流 + 维表（JDBC lookup join）
--
-- 形态：Kafka(traccar-cdc / tc_events) 事实流
--         JOIN MySQL(traccar.tc_devices) 维表（LOOKUP hint 强制 lookup join）
--       → 补齐设备名/型号/uniqueid + 事件类型白名单 → ClickHouse
-- 难度：★★；并行度 2，sink 并行度 1，占 2 slots（lookup 连接不吃 slot）
--
-- 【为什么用 lookup，而不是双流 interval join】
--   tc_devices 的 CDC 消息只在设备变更时才出现（一天几条），双流 join 会让
--   99% 的事件关联不上、还要靠水位线推进，属于"看起来高级、实际漏数"。
--   lookup 每次查主库拿实时态，语义正确、无状态膨胀。
--
-- 【三处与计划 v1.1 的差异，全是实测逼出来的】
--   1) `FOR SYSTEM_TIME AS OF` 必须挂**处理时间**（`proc_time AS PROCTIME()`），
--      不能挂事件时间：挂事件时间会被规划成 versioned temporal join 并报
--      "requires both primary key and row time attribute in versioned table"，
--      而且此时 LOOKUP hint 也救不回来（实测）。
--   2) 必须显式 `/*+ LOOKUP('table'='d') */`：不加 hint 会退化成 **普通流 join**
--      （计划里是 Join(InnerJoin) + 两次 TableSourceScan，右侧把 33461 行设备全量扫进状态，
--      且拿不到实时态）。加上 hint 才是 LookupJoin（每行一次主库查询 + 缓存）。
--   3) LEFT JOIN 可以保留：LookupJoin 的输出是 append-only（不像普通 LEFT JOIN
--      会因右表 NULL→有值产生 -U/+U），所以事件不会因设备缺失被丢弃。
--
-- 【为什么这段 SQL 是 append-only】
--   lookup join 每条输入产出一条输出，不产生回撤 → 正好能落 insert-only 的 CH sink。
--   这也是 B3 存在的意义：证明"CH 不是只能接窗口聚合，流+维表也可以"。
--
-- 【口令】'password' = '__TRACCAR_MYSQL_PASSWORD__' 由
--   sync_benchmarks.sh 在投递时从环境变量/.env 替换，仓库源文件不含明文。
-- =============================================================================

-- ---------- 基础运行参数 ----------
SET 'pipeline.name' = 'bench_03_events_enriched';
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
-- lookup join 的额外开关：连接复用 + 异步（1.17 支持 lookup.async 的 table hint，
--   这里保守用同步 + 缓存，先保证语义正确，性能留给后续迭代）

-- ---------- 源：Kafka / raw（事实流） ----------
-- 【为什么不用 canal-json】它是 changelog format：UPDATE 事件会变成 -U/+U 两种 RowKind，
--   一路带进 lookup join 再进 sink，insert-only 的 CH sink 会拒绝
--   （"please declare primary key for sink table when query contains update/delete record"）。
--   用 raw 自己解析，整条链路就是纯 append-only。详见 B2 源表注释与 §8.3 第 19/20 条。
CREATE TABLE src_tc_events (
    `payload` STRING,
    `event_id`     AS CAST(JSON_VALUE(`payload`, '$.data[0].id')       AS BIGINT),
    `event_type`   AS JSON_VALUE(`payload`, '$.data[0].type'),
    `event_time`   AS TO_TIMESTAMP(JSON_VALUE(`payload`, '$.data[0].eventtime'),
                                   'yyyy-MM-dd HH:mm:ss'),
    -- 类型必须与 MySQL 的 tc_events.deviceid（INT）一致：声明 BIGINT 会在 lookup 时
    -- 抛 ClassCastException: java.lang.Integer cannot be cast to java.lang.Long（实测）
    `device_id`    AS CAST(JSON_VALUE(`payload`, '$.data[0].deviceid')  AS INT),
    `position_id`  AS CAST(JSON_VALUE(`payload`, '$.data[0].positionid') AS BIGINT),
    `geofence_id`  AS CAST(JSON_VALUE(`payload`, '$.data[0].geofenceid') AS BIGINT),
    `attributes`   AS JSON_VALUE(`payload`, '$.data[0].attributes'),
    -- 处理时间属性：lookup join 的 `FOR SYSTEM_TIME AS OF` 用**处理时间**才能稳定
    -- 命中 LookupJoin 计划（用事件时间会被规划成 versioned temporal join 并报错）
    `proc_time`    AS PROCTIME(),
    -- 水位线：本作业的 join 不走 temporal 语义（见下方说明），水位线留着是为了
    -- 给后续加窗口时直接用，同时让 event_time 成为正式的事件时间属性。
    WATERMARK FOR `event_time` AS `event_time` - INTERVAL '10' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'traccar-cdc',
    'properties.bootstrap.servers' = 'kafka03:29092,kafka04:29092,kafka05:29092',
    'properties.group.id' = 'bench_03_events_enriched_v1',
    'scan.startup.mode' = 'latest-offset',
    'format' = 'raw'
);

-- ---------- 维表：MySQL / tc_devices（lookup） ----------
-- PK NOT ENFORCED 在这里不是"去重声明"，而是 lookup join 的**关联键**声明；
-- 没有它会报 "Lookup join requires the table to be a lookup table with primary key"。
CREATE TABLE dim_tc_devices (
    -- 与 MySQL tc_devices.id（int）保持 INT：类型不一致时 JDBC lookup 会把 Integer
    -- 强转成 Long 报 ClassCastException
    `id`       INT,
    `name`     STRING,
    `uniqueid` STRING,
    `model`    STRING,
    PRIMARY KEY (`id`) NOT ENFORCED
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:mysql://manager:33306/traccar?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=UTF-8',
    'table-name' = 'tc_devices',
    'username' = 'root',
    'password' = '__TRACCAR_MYSQL_PASSWORD__',
    -- 缓存：最多 1000 条、10 分钟过期。33461 台设备全量也就 3 万行，
    -- 缓存命中率高；不加缓存会让每条事件都打一次主库（计划 R3）
    'lookup.cache.max-rows' = '1000',
    'lookup.cache.ttl' = '10min',
    'lookup.max-retries' = '3'
);

-- ---------- 目标：ClickHouse ----------
CREATE TABLE ch_dwd_events (
    `event_id`         BIGINT,
    `event_type`       STRING,
    `event_time`       TIMESTAMP(3),
    `device_id`        BIGINT,
    `position_id`      BIGINT,
    `geofence_id`      BIGINT,
    `device_name`      STRING,
    `device_unique_id` STRING,
    `device_model`     STRING,
    `attributes`       STRING
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:clickhouse://doris05:38123/traccar',
    'table-name' = 'dwd_tc_events_enriched',
    'username' = 'default',
    'password' = '__CH_PASSWORD__',
    'sink.buffer-flush.max-rows' = '500',
    'sink.buffer-flush.interval' = '5s',
    'sink.max-retries' = '3',
    'sink.parallelism' = '1'
);

-- ---------- 关联 + 裁剪 ----------
-- 事件类型白名单：只保留有业务含义的事件，过滤掉高频噪声（deviceUnknown 等按需放开）
--
-- 【LOOKUP hint 是必需的，不是优化】
--   不加 hint 时规划器会把这条 join 规划成**普通流 join**（右侧全表扫入状态），
--   而不是 lookup；显式 `/*+ LOOKUP('table'='d') */` 才生成 LookupJoin
--   （实测 Flink 1.17.2 + flink-connector-jdbc 3.1.2）。
INSERT INTO ch_dwd_events
SELECT /*+ LOOKUP('table' = 'd') */
    e.`event_id`,
    e.`event_type`,
    e.`event_time`,
    CAST(e.`device_id` AS BIGINT)                                 AS device_id,
    e.`position_id`,
    e.`geofence_id`,
    -- COALESCE：CH 列非 Nullable，维表取不到的列必须兜底（反面清单 #11）。
    -- LEFT JOIN + LookupJoin：设备已删除时事件仍然保留，设备字段落空串。
    COALESCE(d.`name`, '')                                        AS device_name,
    COALESCE(d.`uniqueid`, '')                                    AS device_unique_id,
    COALESCE(d.`model`, '')                                       AS device_model,
    COALESCE(e.`attributes`, '{}')                                AS attributes
FROM src_tc_events AS e
LEFT JOIN dim_tc_devices FOR SYSTEM_TIME AS OF e.`proc_time` AS d
       ON e.`device_id` = d.`id`
WHERE e.`device_id` IS NOT NULL
  AND JSON_VALUE(e.`payload`, '$.table') = 'tc_events'   -- 一个 topic 混全库变更，必须按表过滤
  AND e.`event_type` IN ('deviceOnline', 'deviceOffline', 'deviceMoving', 'deviceStopped',
                         'alarm', 'geofenceEnter', 'geofenceExit', 'deviceUnknown');
