-- 自定义报表（HTML 静态页托管）
--
-- 背景：品牌方要看的经营报表是 AI 生成的**单文件自包含 HTML**，已能上传到资源中心（HDFS），
--   但没有任何入口以「可渲染」方式送进浏览器。本迁移只做登记与授权，不改任何既有表结构：
--     ① 新增 dinky_report（报表 → 资源引用）与 dinky_report_role（报表 ↔ 角色授权）；
--     ② 在父菜单 164（看板）下追加 4 行菜单（1 个页面 + 3 个权限点）；
--     ③ 仅给 role_code='SuperAdmin' 授权（D10：其它角色由部署后按需勾选，避免批量开权限）。
--
-- 纪律：dinky_report.tenant_id 由代码**显式赋值**（D9），列上的 default 1 只是兜底，
--   不得依赖（历史踩过「tenant_id 落 DB 默认 1」的事故）。
-- 既有看板（dinky_dashboard / 菜单 165-173 / 185）一行不动。

-- ---------- ① 表结构 ----------
CREATE TABLE IF NOT EXISTS `dinky_report` (
    `id`            int         NOT NULL AUTO_INCREMENT,
    `tenant_id`     int         NOT NULL DEFAULT 1           COMMENT '租户ID（代码显式赋值，勿依赖默认值）',
    `name`          varchar(128) NOT NULL                    COMMENT '报表名称（同租户内唯一）',
    `remark`        varchar(512)         DEFAULT NULL        COMMENT '备注/说明',
    `resource_id`   int         NOT NULL                     COMMENT '资源ID -> dinky_resources.id',
    `resource_path` varchar(512) NOT NULL                    COMMENT '冗余：dinky_resources.full_name，便于失效识别',
    `sort_num`      int         NOT NULL DEFAULT 0           COMMENT '排序（预留）',
    `enabled`       tinyint     NOT NULL DEFAULT 1           COMMENT '启用状态（预留）',
    `creator`       int                  DEFAULT NULL        COMMENT '创建人',
    `updater`       int                  DEFAULT NULL        COMMENT '更新人',
    `create_time`   datetime             DEFAULT NULL        COMMENT '创建时间',
    `update_time`   datetime             DEFAULT NULL        COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_tenant_name` (`tenant_id`, `name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '自定义报表（HTML 静态页托管）';

CREATE TABLE IF NOT EXISTS `dinky_report_role` (
    `id`          int      NOT NULL AUTO_INCREMENT,
    `report_id`   int      NOT NULL              COMMENT '报表ID -> dinky_report.id',
    `role_id`     int      NOT NULL              COMMENT '角色ID -> dinky_role.id',
    `create_time` datetime          DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_role` (`report_id`, `role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '自定义报表-角色授权（无授权行 = 公共可见）';

-- ---------- ② 菜单：164 看板下追加 ----------
-- 196 C 页面入口（前端 HasAuthority/路由判定用 path）
-- 197 F 查看（详情 + 预览）
-- 198 F 管理（新增/修改/删除）
-- 199 F 打开（查看页入口，与 :view 共用页面但语义独立，供后续细化）
DELETE FROM `dinky_sys_menu` WHERE `id` IN (196, 197, 198, 199);
-- 清残留绑定：不清会「意外继承」历史角色的授权（V20261014.1.4.5 的教训）
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
