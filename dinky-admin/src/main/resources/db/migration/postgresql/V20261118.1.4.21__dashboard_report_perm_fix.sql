-- 自定义报表 · 入口权限点从 2 段修正为 3 段（PostgreSQL 方言）
--
-- 与 mysql/V20261118.1.4.21__dashboard_report_perm_fix.sql 等价：
--   2 段权限 `dashboard:report` 无法被超管通配 `*:*:*`（正则 .*:.*:.*）匹配，
--   导致超管也被 403；入口权限点统一改为 `dashboard:report:list`。
-- 幂等：全新环境（.1.4.20 已写 3 段）UPDATE 命中 0 行。

UPDATE dinky_sys_menu
SET perms = 'dashboard:report:list', update_time = NOW()
WHERE id = 196
  AND perms = 'dashboard:report';
