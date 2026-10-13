-- AI Chat 权限点登记（分级：使用 / 写操作）
-- 背景：AiChatController 6 个接口此前仅要求登录，任何用户都能使用（含写能力），
--       且菜单表无对应权限点，角色无法收敛（详见缺口调研 §5.1）。
-- 设计：AI Chat 面板位于数据开发工具栏、无独立路由，故权限点独立成模块 aiChat:*：
--       aiChat:use   → 面板入口、对话、只读工具（context/mention）
--       aiChat:write → 写审计 / 确认 / 取消（落盘闸门）
-- 注意：path 与 perms 自洽（path=/aiChat/use → perms=aiChat:use）；
--       order_num 取数据开发(5)现有最大值 146 之后。

DELETE FROM dinky_sys_menu WHERE id IN (182, 183);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (182, 5,   'AI Chat',    '/aiChat/use',   NULL, 'aiChat:use',   'RobotOutlined', 'F', 0, 147, NOW(), NOW(), '数据开发工具栏 AI Chat 面板（对话 + 只读工具）'),
       (183, 182, 'AI 写操作', '/aiChat/write', NULL, 'aiChat:write', 'EditOutlined',  'F', 0, 148, NOW(), NOW(), '写审计 / 确认 / 取消（落盘闸门）');
