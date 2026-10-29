-- AI Chat：配置中心 - 全局设置 新增「LLM 配置」Tab（含权限项）
-- admin（ADMIN_ID）天然拥有全部权限，其它角色需在角色管理中勾选授权。
DELETE FROM dinky_sys_menu WHERE id IN (158, 159);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (158, 24, 'LLM 配置', '/settings/globalsetting/llm', NULL, 'settings:globalsetting:llm', 'RobotOutlined', 'F', 0, 131, NOW(), NOW(), NULL),
       (159, 158, '编辑', '/settings/globalsetting/llm/edit', NULL, 'settings:globalsetting:llm:edit', 'EditOutlined', 'F', 0, 132, NOW(), NOW(), NULL);
