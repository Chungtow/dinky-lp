-- 修正本批（2026-10-14 菜单与权限登记缺口修复）暴露的两个问题
--
-- 修正 1：AI Chat 权限点由 2 段改为 3 段
--   原因：超管的通配权限是 *:*:*，Sa-Token 按「冒号分段」做通配匹配，
--         2 段串（aiChat:use / aiChat:write）连超管都匹配不上 → 接口对所有人返回
--         NotPermissionException（实测：超管调 /api/aiChat/config 被拒，而 3 段串正常）。
--   修正：aiChat:use → aiChat:panel:use；aiChat:write → aiChat:panel:write（path 同步改为 3 段，保持自洽）。
--
-- 修正 2：清理指向本批新增菜单 id 的历史残留角色绑定
--   原因：dinky_sys_role_menu 中存在指向 menu_id=174/175 的历史绑定（原菜单已删除、绑定未级联清理，
--         记录创建于 2026-10-14 00:23）；本批新菜单复用这些 id 后会"意外继承"这些授权
--         （实测 3 个角色因此获得了 registration:skill:view）。
--   修正：显式清理本批全部新增 id 的角色绑定，保证新权限点**默认不授权给任何角色**，
--         授权一律由管理员在"角色 → 分配菜单"中显式勾选（见修复计划 §4.2）。

UPDATE dinky_sys_menu SET path = '/aiChat/panel/use',   perms = 'aiChat:panel:use',   update_time = NOW() WHERE id = 182;
UPDATE dinky_sys_menu SET path = '/aiChat/panel/write', perms = 'aiChat:panel:write', update_time = NOW() WHERE id = 183;

DELETE FROM dinky_sys_role_menu WHERE menu_id IN (174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186);
