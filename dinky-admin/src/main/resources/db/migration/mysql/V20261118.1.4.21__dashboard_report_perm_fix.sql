-- 自定义报表 · 入口权限点从 2 段修正为 3 段
--
-- 起因（2026-11-18 部署测试槽实测）：`V20261118.1.4.20` 把菜单 196 的 perms 写成
--   `dashboard:report`（2 段）。超管的权限集合是通配 `*:*:*`（BaseConstant.ALL_PERMISSION），
--   Sa-Token 用 vagueMatch 把它当正则 `.*:.*:.*`（要求 2 个冒号）——
--   2 段权限少一个冒号，**超管反而匹配不上**，`GET /api/report/list` 直接返回
--   "You do not have permission for this operation"，日志 `Permission denied: user=1`。
-- 处置：入口权限点改为 `dashboard:report:list`（与 registration:cluster:instance:* 同形），
--   控制器常量 PermissionConstants.DASHBOARD_REPORT 同步。
--
-- 本脚本仅做幂等归一：已按 .1.4.20 落库的环境（2 段）被纠正；全新环境本就写的是 3 段，UPDATE 命中 0 行。

UPDATE `dinky_sys_menu`
SET `perms` = 'dashboard:report:list', `update_time` = NOW()
WHERE `id` = 196
  AND `perms` = 'dashboard:report';
