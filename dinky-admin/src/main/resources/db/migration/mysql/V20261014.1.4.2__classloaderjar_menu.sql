-- 类加载器 Jars 权限点登记
-- 背景：页面菜单已存在（id=157 /settings/classloaderjars），但无任何按钮权限点，
--       且后端 SystemController.queryAllClassLoaderJarFiles 仅要求登录（缺口调研 §5.1）。
-- 说明：path 与 perms 自洽：/settings/classloaderjars/view → settings:classloaderjars:view
--       order_num 取配置中心(6)现有最大值 155 之后。

DELETE FROM dinky_sys_menu WHERE id IN (184);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (184, 6, '查看 Jars', '/settings/classloaderjars/view', NULL, 'settings:classloaderjars:view', 'EyeOutlined', 'F', 0, 156, NOW(), NOW(), NULL);
