-- 自定义报表（HTML 静态页托管）—— PostgreSQL 方言
--
-- 与 mysql/V20261118.1.4.20__dashboard_report.sql 等价：
--   ① 新增 dinky_report / dinky_report_role；
--   ② 父菜单 164（看板）下追加 4 行菜单；
--   ③ 仅给 role_code='SuperAdmin' 授权（D10）。
-- tenant_id 由代码显式赋值（D9），列默认值仅兜底。

-- ---------- ① 表结构 ----------
CREATE TABLE IF NOT EXISTS dinky_report
(
    id            SERIAL PRIMARY KEY,
    tenant_id     INT          NOT NULL DEFAULT 1,
    name          VARCHAR(128) NOT NULL,
    remark        VARCHAR(512) NULL,
    resource_id   INT          NOT NULL,
    resource_path VARCHAR(512) NOT NULL,
    sort_num      INT          NOT NULL DEFAULT 0,
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    creator       INT          NULL,
    updater       INT          NULL,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_tenant_name ON dinky_report (tenant_id, name);

CREATE TABLE IF NOT EXISTS dinky_report_role
(
    id          SERIAL PRIMARY KEY,
    report_id   INT       NOT NULL,
    role_id     INT       NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_role ON dinky_report_role (report_id, role_id);

COMMENT ON TABLE dinky_report IS '自定义报表（HTML 静态页托管）';
COMMENT ON COLUMN dinky_report.tenant_id IS '租户ID（代码显式赋值，勿依赖默认值）';
COMMENT ON COLUMN dinky_report.name IS '报表名称（同租户内唯一）';
COMMENT ON COLUMN dinky_report.resource_id IS '资源ID -> dinky_resources.id';
COMMENT ON COLUMN dinky_report.resource_path IS '冗余：dinky_resources.full_name，便于失效识别';
COMMENT ON TABLE dinky_report_role IS '自定义报表-角色授权（无授权行 = 公共可见）';

-- ---------- ② 菜单：164 看板下追加 ----------
DELETE FROM dinky_sys_menu WHERE id IN (196, 197, 198, 199);
DELETE FROM dinky_sys_role_menu WHERE menu_id IN (196, 197, 198, 199);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num,
                            create_time, update_time, note)
VALUES (196, 164, '自定义报表', '/dashboard/report', './Dashboard/Report', 'dashboard:report:list', 'BarChartOutlined', 'C',
        1, 170, NOW(), NOW(), '报表中心：卡片列表，按角色可见性过滤'),
       (197, 164, '查看报表', '/dashboard/report/view', './Dashboard/Report/View', 'dashboard:report:view', 'FundViewOutlined', 'F',
        1, 171, NOW(), NOW(), '查看报表（iframe 渲染 + 页内遮盖全屏）'),
       (198, 164, '管理报表', '/dashboard/report/manage', NULL, 'dashboard:report:manage', 'EditOutlined', 'F',
        1, 172, NOW(), NOW(), '新建/编辑/删除报表与授权角色'),
       (199, 164, '打开报表', '/dashboard/report/open', NULL, 'dashboard:report:open', 'FundViewOutlined', 'F',
        1, 173, NOW(), NOW(), '打开报表（预留：与查看分离，便于后续按角色细分）');

-- ---------- ③ 授权：仅 SuperAdmin（D10） ----------
INSERT INTO dinky_sys_role_menu (role_id, menu_id, create_time, update_time)
SELECT r.id, m.menu_id, NOW(), NOW()
FROM dinky_role r
         CROSS JOIN (SELECT 196 AS menu_id
                     UNION ALL
                     SELECT 197
                     UNION ALL
                     SELECT 198
                     UNION ALL
                     SELECT 199) m
WHERE r.role_code = 'SuperAdmin';
