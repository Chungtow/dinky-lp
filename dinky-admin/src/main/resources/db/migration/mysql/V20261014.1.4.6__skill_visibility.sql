-- Skills 三档可见性（2026-10-14 用户反馈四项问题修复 · P2）
--
-- 背景：dinky_skill.visibility 早已支持 private / tenant，但 create 硬编码 private 且全链路没有
--       任何"修改可见性"入口（界面无法选择、创建后无法改）。本批补三档：private / tenant / public。
--       详见 docs/plan/20261014-dinky-用户反馈四项问题修复计划.md §3。
--
-- 本迁移只做两件事：
--   ① 登记「设为全体公开」所需的新权限点（F 项）；
--   ② 更新 visibility 列注释的值域说明（列宽 VARCHAR(16) 足以容纳 public，无需改类型）。
--
-- 权限点命名：registration:skill:share（3 段）
--   段数必须 ≥ 3 —— Sa-Token 通配 *:*:* 按冒号分段匹配，2 段串连超管都会被拒
--   （见 V20261014.1.4.5 的实施期实测教训）。
-- 菜单 id 起点 187：174~186 已被本批其它迁移占用。
-- 必须同时清理 dinky_sys_role_menu 中同 id 的历史残留绑定，否则新权限点会被"意外继承"授权
--   （见 V20261014.1.4.5 修正 2）。

DELETE FROM dinky_sys_menu WHERE id = 187;

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (187, 174, '设公开', '/registration/skill/share', NULL, 'registration:skill:share', 'GlobalOutlined', 'F', 0, 137, NOW(), NOW(),
        '把 skill/doc 可见性设为「全体公开」；同租户共享沿用 registration:skill:save');

DELETE FROM dinky_sys_role_menu WHERE menu_id = 187;

ALTER TABLE dinky_skill
    MODIFY COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'private' COMMENT 'private | tenant | public';
