-- =============================================================================
-- B1 · bench_01_devices_ods —— 单流纯裁剪（最小可用标杆作业）
--
-- 形态：Kafka(traccar-cdc / tc_devices) → 过滤 + 字段映射 + 空值兜底 → ClickHouse
-- 语义：Canal flatMessage 原样读入（format=raw），在 SELECT 里用 JSON_VALUE 取
--       data[0].* 与根级 table/ts；无状态、无窗口、无聚合 → 输出是 append-only。
-- 难度：★（1 人 0.5 天）；并行度 2，sink 并行度 1，占 2 slots
--
-- 为什么这个作业是"最小可用"：
--   它只覆盖"取数 + 类型转换 + 兜底 + 幂等键"四件事，没有状态、没有回撤，
--   是排查链路（Kafka 有没有数据 / CH 能不能写 / 时区对不对）的第一块试金石。
--
-- 【为什么用 format=raw 而不是 canal-json】
--   canal-json 只能拿到 data[0] 的列，拿不到根级 ts；而 ver 必须取消息的 ts（毫秒）
--   才能保证"后到的记录 ver 更大"（ReplacingMergeTree(ver) 去重的前提）。
--   用 raw + JSON_VALUE 两者都能取到，代价是解析失败要靠 SQL 兜底而非
--   canal-json.ignore-parse-errors（见反面清单 #13、#19）。
--
-- 【坑位对照 · 计划 §8.3】
--   #9  时间口径：lastupdate 是 traccar-app 写入的 TIMESTAMP，Canal 按 UTC 渲染，
--       本作业**不做时区补偿**，直接落 CH 的 DateTime64(3,'Asia/Shanghai') 列，
--       因此 ods 层该列是"源库 UTC 挂钟值"，跨层对账时要注意口径（DW 层才规整）。
--   #11 非 Nullable 列不能传 NULL：phone/model/attributes/lastupdate 全部 COALESCE。
--   #12 ver 单调：取根级 ts（毫秒）。
--   #13 Canal 数值序列化成字符串：id/ts 必须 CAST。
-- =============================================================================

-- ---------- 基础运行参数（Standalone 下 Dinky 不读 flink-conf.yaml，只能写这里） ----------
SET 'pipeline.name' = 'bench_01_devices_ods';
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
-- 注意：execution.checkpointing.min-pause 走 SQL SET **不生效**（1.17 实测），
--       它必须在任务的 customConfig 里，本批由 sync_benchmarks.sh 统一写入：
--       execution.checkpointing.min-pause = 10s

-- ---------- 源：Kafka / traccar-cdc 原样消息 ----------
CREATE TABLE src_canal_devices (
    `payload` STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'traccar-cdc',
    'properties.bootstrap.servers' = 'kafka03:29092,kafka04:29092,kafka05:29092',
    -- 【必须独立】不能与其它作业共用消费组，否则会瓜分同一 group 的分区（反面清单 #2）
    'properties.group.id' = 'bench_01_devices_ods_v1',
    -- latest-offset：tc_devices 随设备上报持续更新（近 10 分钟有 5000 台），无需回放历史
    'scan.startup.mode' = 'latest-offset',
    'format' = 'raw'
);

-- ---------- 目标：ClickHouse / ods_tc_devices（ReplacingMergeTree(ver)） ----------
-- 列与 CH 物理表严格对齐：不做 PRIMARY KEY 声明（自研方言 insert-only，见 §8.2 说明）
CREATE TABLE ch_ods_tc_devices (
    `id`          BIGINT,
    `name`        STRING,
    `unique_id`   STRING,
    `status`      STRING,
    `phone`       STRING,
    `model`       STRING,
    `last_update` TIMESTAMP(3),
    `attributes`  STRING,
    `ver`         BIGINT
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:clickhouse://doris05:38123/traccar',
    'table-name' = 'ods_tc_devices',
    'username' = 'default',
    'password' = '__CH_PASSWORD__',
    -- 三个参数官方 connector 默认 500/5s/-1，JDBC connector 默认 100/1s/3 —— 必须显式写
    'sink.buffer-flush.max-rows' = '500',
    'sink.buffer-flush.interval' = '5s',
    'sink.max-retries' = '3',
    'sink.parallelism' = '1'
);

-- ---------- 裁剪 + 映射 ----------
-- Canal 把数值序列化成字符串 → CAST；可空列 → COALESCE（CH 列非 Nullable）
-- 按根级 table 过滤：traccar-cdc 混有整个库的变更事件
INSERT INTO ch_ods_tc_devices
SELECT
    CAST(JSON_VALUE(payload, '$.data[0].id') AS BIGINT)                              AS id,
    COALESCE(JSON_VALUE(payload, '$.data[0].name'), '')                              AS name,
    COALESCE(JSON_VALUE(payload, '$.data[0].uniqueid'), '')                          AS unique_id,
    COALESCE(JSON_VALUE(payload, '$.data[0].status'), '')                            AS status,
    COALESCE(JSON_VALUE(payload, '$.data[0].phone'), '')                             AS phone,
    COALESCE(JSON_VALUE(payload, '$.data[0].model'), '')                             AS model,
    COALESCE(CAST(JSON_VALUE(payload, '$.data[0].lastupdate') AS TIMESTAMP(3)),
             TIMESTAMP '1970-01-01 00:00:00')                                        AS last_update,
    COALESCE(JSON_VALUE(payload, '$.data[0].attributes'), '{}')                      AS attributes,
    CAST(JSON_VALUE(payload, '$.ts') AS BIGINT)                                      AS ver
FROM src_canal_devices
WHERE JSON_VALUE(payload, '$.table') = 'tc_devices'
  AND JSON_VALUE(payload, '$.data[0].id') IS NOT NULL;
