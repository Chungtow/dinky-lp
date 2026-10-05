-- AI Chat 阶段 4a（Skills 基础与引用）：技能元数据表
-- 承载 skill 的「语义与权限」：谁创建的、谁能看 / 改、是否启用、版本与内容哈希。
-- 文件内容不在此表：skill 的 SKILL.md 等文件存放在资源存储的 skills/<name>/ 目录下（由 dinky_resources 登记）。
-- 注意：本表**不接入** MyBatis-Plus 租户插件（未加入 MybatisPlusConfig.IGNORE_TABLE_NAMES），
--       权限判定全部走显式条件（owner_id / tenant_id），以免受异步线程丢失租户上下文的影响。
CREATE TABLE IF NOT EXISTS dinky_skill
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(64)  NOT NULL COMMENT 'skill 名（^[a-z0-9][a-z0-9-]{1,63}$，与 SKILL.md frontmatter 一致）',
    description   VARCHAR(512) NULL COMMENT '一句话说明（来自 SKILL.md frontmatter，用于清单注入与 @ 候选）',
    dir_full_name VARCHAR(255) NOT NULL COMMENT 'skill 目录在资源存储中的 full_name（如 /skills/dw-sql-review）',
    owner_id      INT          NOT NULL COMMENT '属主（sys_user.id）',
    tenant_id     INT          NULL COMMENT '租户（sys_tenant.id，请求线程内取值）',
    visibility    VARCHAR(16)  NOT NULL DEFAULT 'private' COMMENT 'private | tenant',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可被引用（0 否 / 1 是）',
    source        VARCHAR(16)  NOT NULL DEFAULT 'local' COMMENT '来源：local | third_party',
    asset_type    VARCHAR(16)  NOT NULL DEFAULT 'skill' COMMENT '资产类型（预留：未来 semantic 等）',
    meta_json     TEXT         NULL COMMENT '类型特有元数据（JSON，4a 未使用）',
    version       INT          NOT NULL DEFAULT 1 COMMENT '内容版本（每次保存 +1）',
    content_hash  VARCHAR(64)  NULL COMMENT 'SKILL.md 内容哈希（变更审计）',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_name (name, owner_id),
    KEY idx_skill_tenant (tenant_id),
    KEY idx_skill_dir (dir_full_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Dinky Skills（阶段 4a）';
