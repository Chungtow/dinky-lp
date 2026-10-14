-- Kafka 配置 Tab 权限点登记
-- 背景：前端已定义常量 SETTING_GLOBAL_KAFKA / _EDIT 并在全局设置页用于判权
--       （SettingOverView/index.tsx:264-269），但菜单表缺这两个 F 项 →
--       非超管永远看不到 Kafka Tab，管理员也无法勾选（缺口调研 §4.2）。
--       （同页的 LLM 配置已由 V20260926.1.4.0__ai_chat_menu.sql 登记，Kafka 漏了。）
-- 说明：path 与 perms 自洽；order_num 取全局设置(24)现有最大值 131 之后。

DELETE FROM dinky_sys_menu WHERE id IN (180, 181);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (180, 24,  'Kafka 配置', '/settings/globalsetting/kafka',      NULL, 'settings:globalsetting:kafka',      'ClusterOutlined', 'F', 0, 133, NOW(), NOW(), NULL),
       (181, 180, '编辑',      '/settings/globalsetting/kafka/edit', NULL, 'settings:globalsetting:kafka:edit', 'EditOutlined',    'F', 0, 134, NOW(), NOW(), NULL);
