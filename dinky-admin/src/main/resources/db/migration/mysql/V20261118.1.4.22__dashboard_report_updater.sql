-- 自定义报表 · 补 updater 列
--
-- 起因（2026-11-18 测试槽实测）：`Report extends SuperEntity`，而 SuperEntity 带
--   `updater`（@TableField(fill = INSERT_UPDATE)），MyBatis-Plus 会把它拼进 SELECT/INSERT。
--   建表时只写了 creator，漏了 updater → `GET /api/report/list` 报
--   "Unknown column 'updater' in 'field list'"。
-- 处置：`.1.4.20` 的建表语句已补该列；本脚本负责把**已经建过表**的环境补上（幂等）。
--
-- MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema 判存后动态执行。

SET @ddl = (SELECT IF(COUNT(*) = 0,
                      'ALTER TABLE `dinky_report` ADD COLUMN `updater` int DEFAULT NULL COMMENT ''更新人''',
                      'DO 0')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'dinky_report'
              AND COLUMN_NAME = 'updater');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
