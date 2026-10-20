-- 语义层（P2）：dbt 语义项目在线编辑 —— 权限点登记
--
-- 背景：数据开发页新增「语义项目」tab（读写 HDFS /semantic/<project>/ 下的 dbt 语义文件）。
--       该 tab 是页面内的前端 tab，不是左侧菜单，因此只登记**功能权限**，
--       不新增可导航菜单行（type='F' + display=0，与 AI Chat 的登记方式一致）。
--
-- 权限点（必须 3 段式，超管通配 *:*:* 按冒号分段匹配）：
--   datastudio:semantic:view → 看项目列表 / 文件树 / 读文件
--   datastudio:semantic:edit → 保存回 HDFS（写操作，走审计）
-- path 与 perms 自洽：path=/datastudio/semantic/view ⇔ perms=datastudio:semantic:view
--
-- id 选择：dinky_sys_menu 当前最大 id = 187（2026-10-20 实查），故取 188/189。
-- parent_id = 5（数据开发）；order_num 取 AI Chat（147/148）之后。
--
-- 幂等：先按 id 清理（含角色关联），再插入，重复执行安全。

DELETE FROM dinky_sys_role_menu WHERE menu_id IN (188, 189);

DELETE FROM dinky_sys_menu WHERE id IN (188, 189);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (188, 5, '语义项目查看', '/datastudio/semantic/view', NULL, 'datastudio:semantic:view', 'FileTextOutlined', 'F', 0, 149, NOW(), NOW(), 'P2：dbt 语义项目列表 / 文件树 / 读文件'),
       (189, 5, '语义项目编辑', '/datastudio/semantic/edit', NULL, 'datastudio:semantic:edit', 'EditOutlined', 'F', 0, 150, NOW(), NOW(), 'P2：语义文件保存回 HDFS（乐观锁 + 审计）');
