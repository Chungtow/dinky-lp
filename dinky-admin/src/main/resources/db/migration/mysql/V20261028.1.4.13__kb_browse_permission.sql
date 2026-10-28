-- 知识库（@kb）接入 · 批次 B：补「浏览」权限点（F 类）+ 隐藏批次 A 的占位菜单
--
-- 起因（2026-10-28 批次 B 实施时发现）：`V20261027.1.4.10` 把「知识库」登记为 id=191、
--   type='C'（页面菜单）。但前端 `HasAuthority(path)`（dinky-web/src/hooks/useAccess.tsx:80-93）
--   **只收集 type='F' 的菜单 path**，C 类不进 `blocks`。后果：
--     ① 非超管永远看不到左侧栏「知识库」tab（权限点给了也没用）；
--     ② 超管反而会在主侧边栏看到一个 component 为 NULL 的「知识库」入口（点进去空白）。
--   这正是实施计划 §4.2 想避免的"权限点晚注册导致批次 B 前端 canUseKb 恒 false"。
--
-- 本迁移只做两件事：
--   ① 新增 F 类隐藏权限点 195「知识库浏览」：path=/registration/kb/view、perms=registration:kb:view
--      （与 191 同一 perms，但前端按 **path** 判定，两者互不替代）；
--   ② 191 的 display 置 0：批次 D 补真实管理页之前，不留空页面入口。
--
-- ⚠️ 不删 191：它是 192/193/194（save / share / write）的父节点，且批次 D 的管理页会复用它。
-- ⚠️ 与 191 共用 perms 是刻意的：Sa-Token 的权限集合是字符串去重，同 perms 的两条菜单只表示
--    "同一个能力、两种判定入口"（页面入口 vs 前端按钮/面板可见性），不会让权限语义分叉。

-- ---------- ① F 类「浏览」权限点 ----------
DELETE FROM dinky_sys_menu WHERE id = 195;
-- 清残留：不清会「意外继承」历史角色的绑定（V20261014.1.4.5 的教训）
DELETE FROM dinky_sys_role_menu WHERE menu_id = 195;

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num,
                            create_time, update_time, note)
VALUES (195, 191, '知识库浏览', '/registration/kb/view', NULL, 'registration:kb:view', NULL, 'F', 0, 199,
        NOW(), NOW(),
        '左侧栏「知识库」tab 的可见性判定（前端 HasAuthority 只认 F 类 path）；只读浏览库与库内文档');

-- ---------- ② 批次 A 的 C 类占位菜单先隐藏 ----------
UPDATE dinky_sys_menu SET display = 0, update_time = NOW() WHERE id = 191;
