-- =====================================================================================
-- 知识库 @kb 接入 v1.0 · 表结构补强（2026-10-28，UAT 反馈后）
--
-- ① D-K15：按「租户」授权为主、个人/角色授权为辅 —— 新增 dinky_kb_collection_grant。
--    原设计只有 owner_id + visibility(private|tenant|public) 三档，只能表达
--    「一个库归一个租户」，无法表达「同一个库授权给 A、B 两个租户」。
--
-- ② D-K14：ER 图关系可读性 —— 为知识库两张表补物理外键。
--    背景：dinky_lp 全库 46 张表原本 0 个外键（Dinky 惯例），所以 ER 图里所有表都孤立；
--    ER 图的关系来自 JDBC getImportedKeys/getExportedKeys，只认真实外键约束，不认命名约定。
--    本次只给知识库两表破例，不动其他表。
--
-- ⚠️ 行为变化（ON DELETE RESTRICT）：用户/租户名下仍有知识库时，**物理删除该用户/租户会失败**。
--    Dinky 的 UserServiceImpl.deleteById 是物理删。删除前需先把库转移属主或停用（enabled=0），
--    这被视为正向保护：否则会留下悬空 owner，导致 private 库永久不可见（无人可管理）。
--    若需回退：ALTER TABLE dinky_kb_collection DROP FOREIGN KEY fk_kb_collection_owner;
-- =====================================================================================

-- ---------- ① 授权表 ----------
CREATE TABLE IF NOT EXISTS dinky_kb_collection_grant
(
    id            bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    collection_id bigint      NOT NULL COMMENT '知识库 id（dinky_kb_collection.id）',
    subject_type  varchar(16) NOT NULL COMMENT '被授权主体类型：user | role | tenant（租户为主）',
    subject_id    int         NOT NULL COMMENT '主体 id：user→dinky_user.id / role→dinky_role.id / tenant→dinky_tenant.id',
    permission    varchar(16) NOT NULL DEFAULT 'view' COMMENT '权限：view 可检索 | write 可推送/维护',
    create_by     int                  DEFAULT NULL COMMENT '授权操作人（dinky_user.id）',
    create_time   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_grant (collection_id, subject_type, subject_id, permission),
    KEY idx_kb_grant_subject (subject_type, subject_id),
    CONSTRAINT fk_kb_grant_collection FOREIGN KEY (collection_id) REFERENCES dinky_kb_collection (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库授权（按租户/角色/用户）';

-- subject_id 是多态列（user/role/tenant 共用），无法建单一外键，
-- 故只在应用层保证合法性；ER 图中该表通过 collection_id 与知识库主表连线。

-- ---------- ② 归属外键（有库的用户/租户不允许被物理删除） ----------
-- 加约束前清掉可能存在的悬空引用（正常情况下应为 0 行）
UPDATE dinky_kb_collection c
    LEFT JOIN dinky_user u ON u.id = c.owner_id
SET c.owner_id = 1
WHERE c.owner_id IS NOT NULL
  AND u.id IS NULL;

UPDATE dinky_kb_collection c
    LEFT JOIN dinky_tenant t ON t.id = c.tenant_id
SET c.tenant_id = NULL
WHERE c.tenant_id IS NOT NULL
  AND t.id IS NULL;

ALTER TABLE dinky_kb_collection
    ADD CONSTRAINT fk_kb_collection_owner FOREIGN KEY (owner_id) REFERENCES dinky_user (id) ON DELETE RESTRICT;

ALTER TABLE dinky_kb_collection
    ADD CONSTRAINT fk_kb_collection_tenant FOREIGN KEY (tenant_id) REFERENCES dinky_tenant (id) ON DELETE RESTRICT;
