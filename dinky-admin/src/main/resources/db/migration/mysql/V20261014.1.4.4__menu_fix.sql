-- 菜单数据修正（缺口调研 C 类：菜单配错与权限点缺失）
--
-- 1) 页面被误标为按钮（type='F' 但 component 非空）：
--    id=172 /dashboard/list、id=173 /dashboard/dashboard-layout —— 二者本有静态路由，修正类型恢复语义。
--    ⚠️ id=120 /metrics/server **保持 F 不改 C**：该页面的前端组件从未实现
--       （src/pages/Metrics/Server/ 下无入口文件），注册路由会导致前端构建失败；
--       保持 F 可避免在导航中出现"点开即 404"的入口。待该页面实现后再改 C 并补路由。
-- 2) id=121 /metrics/job 的 component 与前端实际目录不符
--    （./Metrics/Job → 实际为 src/pages/Metrics/JobMetricsList/）；该页面入口存在，
--    配合前端 F6 新增的 /metrics 路由段使其真正可达。
-- 3) 补齐两个"后端已有权限点、菜单缺失"的按钮项（非超管此前永远授不到）：
--    dashboard:chart:delete（id=185）、registration:cluster:instance:recycle（id=186）

UPDATE dinky_sys_menu SET type = 'C', update_time = NOW() WHERE id IN (172, 173);

UPDATE dinky_sys_menu SET component = './Metrics/JobMetricsList', update_time = NOW() WHERE id = 121;

DELETE FROM dinky_sys_menu WHERE id IN (185, 186);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (185, 164, '删除图表', '/dashboard/chart/delete', NULL, 'dashboard:chart:delete', 'DeleteOutlined', 'F', 0, 169, NOW(), NOW(), NULL),
       (186, 28,  '回收实例', '/registration/cluster/instance/recycle', NULL, 'registration:cluster:instance:recycle', 'RollbackOutlined', 'F', 0, 146, NOW(), NOW(), NULL);
