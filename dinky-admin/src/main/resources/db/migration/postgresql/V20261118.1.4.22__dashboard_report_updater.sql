-- 自定义报表 · 补 updater 列（PostgreSQL 方言）
--
-- Report 继承 SuperEntity，自带 updater（MyBatis-Plus 会拼进 SQL）；.1.4.20 建表漏写，
-- 导致 "Unknown column 'updater' in 'field list'"。建表脚本已补，这里给已建表的环境补齐。
ALTER TABLE dinky_report ADD COLUMN IF NOT EXISTS updater INT NULL;
COMMENT ON COLUMN dinky_report.updater IS '更新人';
