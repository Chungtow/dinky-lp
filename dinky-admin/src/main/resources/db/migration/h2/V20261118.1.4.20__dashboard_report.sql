-- 自定义报表（HTML 静态页托管）—— H2 方言
--
-- 与 mysql / postgresql 的 V20261118.1.4.20__dashboard_report.sql 等价。
-- H2 用于嵌入式体验库，语法沿用基线脚本的 MySQL 风格。

-- ---------- ① 表结构 ----------
CREATE TABLE IF NOT EXISTS `dinky_report` (
    `id`            int          NOT NULL AUTO_INCREMENT,
    `tenant_id`     int          NOT NULL DEFAULT 1        COMMENT '租户ID（代码显式赋值，勿依赖默认值）',
    `name`          varchar(128) NOT NULL                  COMMENT '报表名称（同租户内唯一）',
    `remark`        varchar(512) DEFAULT NULL              COMMENT '备注/说明',
    `resource_id`   int          NOT NULL                  COMMENT '资源ID -> dinky_resources.id',
    `resource_path` varchar(512) NOT NULL                  COMMENT '冗余：dinky_resources.full_name',
    `sort_num`      int          NOT NULL DEFAULT 0        COMMENT '排序（预留）',
    `enabled`       tinyint      NOT NULL DEFAULT 1        COMMENT '启用状态（预留）',
    `creator`       int          DEFAULT NULL              COMMENT '创建人',
    `updater`       int          DEFAULT NULL              COMMENT '更新人',
    `create_time`   datetime     DEFAULT NULL              COMMENT '创建时间',
    `update_time`   datetime     DEFAULT NULL              COMMENT '更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_report_tenant_name` UNIQUE (`tenant_id`, `name`)
);

CREATE TABLE IF NOT EXISTS `dinky_report_role` (
    `id`          int      NOT NULL AUTO_INCREMENT,
    `report_id`   int      NOT NULL              COMMENT '报表ID -> dinky_report.id',
    `role_id`     int      NOT NULL              COMMENT '角色ID -> dinky_role.id',
    `create_time` datetime DEFAULT NULL          COMMENT '创建时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_report_role` UNIQUE (`report_id`, `role_id`)
);

-- ---------- ② 菜单：164 看板下追加 ----------
DELETE FROM `dinky_sys_menu` WHERE `id` IN (196, 197, 198, 199);
DELETE FROM `dinky_sys_role_menu` WHERE `menu_id` IN (196, 197, 198, 199);

INSERT INTO `dinky_sys_menu` (`id`, `parent_id`, `name`, `path`, `component`, `perms`, `icon`, `type`,
                              `display`, `order_num`, `create_time`, `update_time`, `note`)
VALUES (196, 164, '自定义报表', '/dashboard/report', './Dashboard/Report', 'dashboard:report:list', 'BarChartOutlined', 'C',
        1, 170, NOW(), NOW(), '报表中心：卡片列表，按角色可见性过滤'),
       (197, 164, '查看报表', '/dashboard/report/view', './Dashboard/Report/View', 'dashboard:report:view', 'FundViewOutlined', 'F',
        1, 171, NOW(), NOW(), '查看报表（iframe 渲染 + 页内遮盖全屏）'),
       (198, 164, '管理报表', '/dashboard/report/manage', NULL, 'dashboard:report:manage', 'EditOutlined', 'F',
        1, 172, NOW(), NOW(), '新建/编辑/删除报表与授权角色'),
       (199, 164, '打开报表', '/dashboard/report/open', NULL, 'dashboard:report:open', 'FundViewOutlined', 'F',
        1, 173, NOW(), NOW(), '打开报表（预留：与查看分离，便于后续按角色细分）');

-- ---------- ③ 授权：仅 SuperAdmin（D10） ----------
INSERT INTO `dinky_sys_role_menu` (`role_id`, `menu_id`, `create_time`, `update_time`)
SELECT r.`id`, m.`menu_id`, NOW(), NOW()
FROM `dinky_role` r
         CROSS JOIN (SELECT 196 AS `menu_id`
                     UNION ALL
                     SELECT 197
                     UNION ALL
                     SELECT 198
                     UNION ALL
                     SELECT 199) m
WHERE r.`role_code` = 'SuperAdmin';
