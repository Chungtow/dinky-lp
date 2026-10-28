-- 知识库（@kb）接入 · 批次 D：打开管理页菜单
--
-- 背景：批次 A 建了 id=191「知识库」C 类菜单但只有占位（component 为 NULL），批次 B 为了不让
-- 主侧边栏出现空白入口，在 .1.4.13 里把它的 display 置 0。批次 D 的真实管理页已就位
-- （dinky-web/src/pages/RegCenter/Kb），这里把它打开并指向组件。
--
-- ⚠️ 顺序要求：本迁移必须在**前端制品已包含 RegCenter/Kb** 之后才执行，否则用户点进去是空白页。
--    Flyway 随应用启动执行，而制品是同一次发布——两者总是同时生效，故不需要额外开关。
--
-- 权限口径不变：
--   191 C（页面入口，可见性由菜单授权决定）
--   192 F /registration/kb/save   建库/改名/启停
--   193 F /registration/kb/share  授权增删
--   194 F /registration/kb/write  推送
--   195 F /registration/kb/view   左侧栏「知识库」tab 与浏览

UPDATE dinky_sys_menu
SET component   = './RegCenter/Kb',
    display     = 1,
    name        = '知识库',
    update_time = NOW()
WHERE id = 191;

-- 192/193/194 是 F 类权限点，display 保持 1 以便在角色授权树里可见可勾选；
-- 它们不会出现在导航里（导航只渲染 C 类）。
UPDATE dinky_sys_menu
SET display = 1, update_time = NOW()
WHERE id IN (192, 193, 194);
