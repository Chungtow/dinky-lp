-- AI Chat 阶段 4a（Skills 基础与引用）：技能元数据表
-- 承载 skill 的「语义与权限」：谁创建的、谁能看 / 改、是否启用、版本与内容哈希。
-- 文件内容不在此表：skill 的 SKILL.md 等文件存放在资源存储的 skills/<name>/ 目录下（由 dinky_resources 登记）。
-- 注意：本表**不接入** MyBatis-Plus 租户插件（未加入 MybatisPlusConfig.IGNORE_TABLE_NAMES），
--       权限判定全部走显式条件（owner_id / tenant_id），以免受异步线程丢失租户上下文的影响。
CREATE TABLE IF NOT EXISTS dinky_skill
(
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    description   VARCHAR(512) NULL,
    dir_full_name VARCHAR(255) NOT NULL,
    owner_id      INT          NOT NULL,
    tenant_id     INT          NULL,
    visibility    VARCHAR(16)  NOT NULL DEFAULT 'private',
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    source        VARCHAR(16)  NOT NULL DEFAULT 'local',
    asset_type    VARCHAR(16)  NOT NULL DEFAULT 'skill',
    meta_json     TEXT         NULL,
    version       INT          NOT NULL DEFAULT 1,
    content_hash  VARCHAR(64)  NULL,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_name ON dinky_skill (name, owner_id);
CREATE INDEX IF NOT EXISTS idx_skill_tenant ON dinky_skill (tenant_id);
CREATE INDEX IF NOT EXISTS idx_skill_dir ON dinky_skill (dir_full_name);

COMMENT ON TABLE dinky_skill IS 'Dinky Skills（阶段 4a）';
COMMENT ON COLUMN dinky_skill.name IS 'skill 名（与 SKILL.md frontmatter 一致）';
COMMENT ON COLUMN dinky_skill.description IS '一句话说明（来自 frontmatter）';
COMMENT ON COLUMN dinky_skill.dir_full_name IS 'skill 目录在资源存储中的 full_name';
COMMENT ON COLUMN dinky_skill.owner_id IS '属主（sys_user.id）';
COMMENT ON COLUMN dinky_skill.tenant_id IS '租户（sys_tenant.id）';
COMMENT ON COLUMN dinky_skill.visibility IS 'private | tenant';
COMMENT ON COLUMN dinky_skill.enabled IS '是否可被引用（0 否 / 1 是）';
COMMENT ON COLUMN dinky_skill.source IS '来源：local | third_party';
COMMENT ON COLUMN dinky_skill.asset_type IS '资产类型（预留）';
COMMENT ON COLUMN dinky_skill.meta_json IS '类型特有元数据（JSON，4a 未使用）';
COMMENT ON COLUMN dinky_skill.version IS '内容版本（每次保存 +1）';
COMMENT ON COLUMN dinky_skill.content_hash IS 'SKILL.md 内容哈希（变更审计）';
