-- 语义层 P4 一期：语义资产反馈闭环 + AI Chat 上下文口径（PostgreSQL 版）
--
-- 与 mysql/V20261023.1.4.9__semantic_feedback.sql 等价，差异仅方言：
--   BIGINT AUTO_INCREMENT → BIGSERIAL PRIMARY KEY
--   TINYINT               → SMALLINT
--   表内 KEY              → 独立 CREATE INDEX IF NOT EXISTS
--   列级 COMMENT '...'    → 省略（本库其它迁移同样未使用 COMMENT ON COLUMN，保持一致）
--   ADD COLUMN 幂等       → PostgreSQL 原生支持 ADD COLUMN IF NOT EXISTS
--
-- 幂等：表 IF NOT EXISTS；加列 IF NOT EXISTS；权限点先删后插。

-- ---------------------------------------------------------------------------
-- ① 语义资产反馈表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dinky_semantic_feedback
(
    id            BIGSERIAL PRIMARY KEY,
    project_name  VARCHAR(64)  NOT NULL,
    asset_name    VARCHAR(128) NOT NULL,
    asset_type    VARCHAR(32)  NULL,
    version       VARCHAR(64)  NULL,
    yaml_sha256   VARCHAR(64)  NULL,
    rating        SMALLINT     NOT NULL,
    feedback_type VARCHAR(32)  NULL,
    content       TEXT         NULL,
    session_id    VARCHAR(64)  NULL,
    user_id       INT          NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    resolver_id   INT          NULL,
    resolve_note  TEXT         NULL,
    resolve_time  TIMESTAMP    NULL,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_semfb_project_status ON dinky_semantic_feedback (project_name, status);

CREATE INDEX IF NOT EXISTS idx_semfb_asset ON dinky_semantic_feedback (project_name, asset_name);

CREATE INDEX IF NOT EXISTS idx_semfb_create ON dinky_semantic_feedback (create_time);

-- ---------------------------------------------------------------------------
-- ② dinky_ai_chat_log 加两列
-- ---------------------------------------------------------------------------
ALTER TABLE dinky_ai_chat_log ADD COLUMN IF NOT EXISTS semantic_project VARCHAR(64) NULL;

ALTER TABLE dinky_ai_chat_log ADD COLUMN IF NOT EXISTS context_fingerprint VARCHAR(64) NULL;

-- ---------------------------------------------------------------------------
-- ③ 权限点：处理语义反馈（提交反馈沿用 datastudio:semantic:view）
-- ---------------------------------------------------------------------------
DELETE FROM dinky_sys_role_menu WHERE menu_id = 190;

DELETE FROM dinky_sys_menu WHERE id = 190;

INSERT INTO dinky_sys_menu (id, parent_id, name, path, component, perms, icon, type, display, order_num,
                            create_time, update_time, note)
VALUES (190, 5, '语义反馈处理', '/datastudio/semantic/feedback', NULL, 'datastudio:semantic:feedback',
        'CommentOutlined', 'F', 0, 151, NOW(), NOW(),
        'P4：处理语义资产反馈（提交反馈沿用 semantic:view，所有人可提）');
