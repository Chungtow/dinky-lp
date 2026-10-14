-- 菜单 component 与前端路由保持一致（2026-10-14 用户反馈四项问题修复 · P4）
--
-- 背景：id=121 /metrics/job 的 component 此前被 V20261014.1.4.4 改为 './Metrics/JobMetricsList'。
--       但该组件从 props 取 timeRange，而 Umi 渲染路由组件时不传任何 props
--       （React.createElement(Component, null)）→ 抛 TypeError:
--       Cannot read properties of undefined (reading 'isReal')；又因项目此前没有 ErrorBoundary，
--       React 18 下未捕获错误会卸载整棵根树 → 连累其它页面（数据开发、认证中心等），须硬刷新恢复。
--       本批已把前端路由（config/routes.ts）改挂容器页 './Metrics'（它持有 timeRange state、
--       提供时间筛选，并在 Dinky 服务未开启时给出降级提示）。
--       此迁移同步菜单元数据，避免下次审计时"菜单 component"与"前端路由"不一致
--       （该字段不参与运行时路由解析，仅作元数据）。
--
-- 说明：/metrics/server（id=120）保持 type='F' 不在导航展示——其前端组件从未实现
--       （src/pages/Metrics/Server/ 下无入口文件），注册路由会导致构建失败。

UPDATE dinky_sys_menu
SET component = './Metrics',
    update_time = NOW()
WHERE id = 121;
