-- 知识库（@kb）接入 v1.0 · 批次 A：库实体 + 权限四件套
--
-- 背景：kb-service（泳道 A）已上线 0.3.2，具备 collection 维度过滤、原文下载、代码类扩展名与
--       检索上下文扩展（expand）。Dinky 侧缺的是「库的实体与权限」——它决定"谁能搜哪个库"。
--       详见 docs/plan/20261027-Dinky AI Chat 知识库（@kb）接入实施计划.md §4.2。
--
-- 本迁移只做四件事：
--   ① 建 dinky_kb_collection（只存语义与权限，内容在 kb-service）；
--   ② 登记浏览权限点 registration:kb:view（菜单 C）+ 三个操作点（F）；
--   ③ 清理上述 id 的历史残留绑定（否则新权限点会被"意外继承"授权，见 V20261014.1.4.5 修正 2）；
--   ④ 插入 2 条**仅测试槽**用的种子库，便于批次 A 在没有管理页的情况下验证 @kb。
--
-- ⚠️ 权限点命名：registration:kb:view / save / share / write，**段数必须 ≥ 3**
--    —— Sa-Token 通配 *:*:* 按冒号分段匹配，2 段串连超管都会被拒
--    （见 V20261014.1.4.5 的实施期实测教训）。前缀用 registration 与 Skills（registration:skill:*）
--    保持同构，因为知识库与 Skills 同在「注册中心」下。
--
-- ⚠️ 菜单 id 起点 191：实测测试槽 dinky_sys_menu 的 MAX(id) = 190（2026-10-27），
--    且 174~190 已被 Skills / 语义层等占用。若在生产环境执行前发现 MAX(id) 已超过 190，
--    请把下面的 191~194 整体顺延，**不要**与其他迁移抢号。
--
-- ⚠️ 本文件一旦执行就不要再改（Flyway 的 validate 虽在本仓库被关掉，但改已执行的迁移会让
--    其它环境的历史不可信）。批次 C 若要给业务表加 kb_doc_id，另开 .1.4.11。

-- ---------- ① 表 ----------
-- 注意：本表**不接入** MyBatis-Plus 租户插件（与 dinky_skill 同理），权限判定全部走显式条件，
--       以免工具循环跑在异步线程时丢失租户上下文（调研 §4.6.1 事实 3）。
CREATE TABLE IF NOT EXISTS dinky_kb_collection
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64)  NOT NULL COMMENT '知识库编码（^c_[0-9a-f]{8}$），写入 kb-service payload，永不变',
    name        VARCHAR(64)  NOT NULL COMMENT '显示名（用户输入，可改，不影响 payload）',
    description VARCHAR(512) NULL COMMENT '一句话说明（用于 @ 候选与范围声明）',
    owner_id    INT          NOT NULL COMMENT '属主（sys_user.id）',
    tenant_id   INT          NULL COMMENT '租户（sys_tenant.id，请求线程内取值）',
    visibility  VARCHAR(16)  NOT NULL DEFAULT 'private' COMMENT 'private | tenant | public',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可被检索（0 否 / 1 是）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_code (code),
    KEY idx_kb_tenant (tenant_id),
    KEY idx_kb_owner (owner_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = 'Dinky AI Chat 知识库（@kb）';

-- ---------- ② 权限点（菜单 C + 操作 F） ----------
DELETE FROM dinky_sys_menu WHERE id IN (191, 192, 193, 194);
DELETE FROM dinky_sys_role_menu WHERE menu_id IN (191, 192, 193, 194);

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num,
                            create_time, update_time, note)
VALUES (191, 3, '知识库', '/registration/kb', NULL, 'registration:kb:view', 'BookOutlined', 'C', 1, 195, NOW(), NOW(),
        '知识库（@kb）浏览：库列表与库内文档只读查看'),
       (192, 191, '新建/编辑库', '/registration/kb/save', NULL, 'registration:kb:save', NULL, 'F', 1, 196, NOW(), NOW(),
        '新建、改名、改描述、启停知识库'),
       (193, 191, '设为公开', '/registration/kb/share', NULL, 'registration:kb:share', NULL, 'F', 1, 197, NOW(), NOW(),
        '把知识库可见性设为「全体公开」；同租户共享沿用 save'),
       (194, 191, '推送到知识库', '/registration/kb/write', NULL, 'registration:kb:write', NULL, 'F', 1, 198, NOW(), NOW(),
        '把编辑区 / 语义层 / skills / 资源 tab 的文本推送入库（批次 C 使用；多归属会扩大可见范围，故独立授权）');

-- ---------- ③ 种子库（仅测试槽；生产上线前必须删除本段，见实施计划 D-K7） ----------
-- 两条种子各司其职，批次 A 才能把检索管道验完整：
--   c_kbdemo1 —— **空库**：kb-service 里没有同编码的 collection。用于验证 U-12「无果即说无果」
--                （模型必须说"未检索到"，不能编造）。
--   testcol2  —— **有货**：kb-service 现存真实 collection（2026-10-27 实测 2 docs / 309 chunks）。
--                用于验证 U-07/U-09/U-10「真的检索到、出处带 §与#、expand 三档有差异」。
--                code 直接沿用 A 侧原 collection 标识——**外部导入的既有库不做 c_ 重编码**，
--                否则 filter.collection 与 kb-service 对不上，检出一片空。
DELETE FROM dinky_kb_collection WHERE code IN ('c_kbdemo1', 'c_kbdemo2', 'testcol2');

INSERT INTO dinky_kb_collection (code, name, description, owner_id, tenant_id, visibility, enabled, create_time,
                                 update_time)
VALUES ('c_kbdemo1', '空库（UAT 用）', '故意留空且私有：验证"库里没有相关内容"时如实回答，同时验证非属主看不到它', 1, NULL,
        'private', 1, NOW(), NOW()),
       ('testcol2', '流程与规范示例（A 侧已有）', 'kb-service 现存语料，用于验证检索命中、出处与 expand 三档', 1, NULL,
        'public', 1, NOW(), NOW());
