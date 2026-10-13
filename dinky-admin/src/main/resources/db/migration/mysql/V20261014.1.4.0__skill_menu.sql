-- Skills 纳入菜单与权限体系
-- 背景：Skills 能力（页面 + 10 个接口）已上线，但 dinky_sys_menu 无任何登记，
--       导致角色管理里勾不到、非超管看不到页面（详见
--       docs/research/20261013-Dinky迭代新增能力菜单与权限登记缺口调研.md §4.1）。
-- 说明：path 与 perms 保持自洽（perms = path 的 '/' 换 ':'）：
--       path=/registration/skill/view → perms=registration:skill:view
-- 注意：order_num 取注册中心(3)现有最大值 73 之后，避免改变该顶级菜单的默认落地页。

DELETE FROM dinky_sys_menu WHERE id IN (174, 175, 176, 177, 178, 179);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num, create_time, update_time, note)
VALUES (174, 3,   'Skills',   '/registration/skill',        './RegCenter/Skills', 'registration:skill',        'ThunderboltOutlined', 'C', 0, 131, NOW(), NOW(), NULL),
       (175, 174, '查看',     '/registration/skill/view',   NULL,                 'registration:skill:view',   'EyeOutlined',         'F', 0, 132, NOW(), NOW(), NULL),
       (176, 174, '新建',     '/registration/skill/add',    NULL,                 'registration:skill:add',    'PlusOutlined',        'F', 0, 133, NOW(), NOW(), NULL),
       (177, 174, '编辑',     '/registration/skill/save',   NULL,                 'registration:skill:save',   'SaveOutlined',        'F', 0, 134, NOW(), NOW(), NULL),
       (178, 174, '删除',     '/registration/skill/delete', NULL,                 'registration:skill:delete', 'DeleteOutlined',      'F', 0, 135, NOW(), NOW(), NULL),
       (179, 174, '文件管理', '/registration/skill/file',   NULL,                 'registration:skill:file',   'FolderOutlined',      'F', 0, 136, NOW(), NOW(), NULL);
