-- 语义层 P4 一期：语义资产反馈闭环 + AI Chat 上下文口径
--
-- 本迁移做三件事：
--   ① 新建 dinky_semantic_feedback —— 让「口径错了」有地方说、有人处理（OPEN → RESOLVED / IGNORED）
--   ② dinky_ai_chat_log 加两列 —— semantic_project（本轮注入的语义项目）/ context_fingerprint（上下文指纹）
--      用于线上口径（M5）：语义引用率、采纳率、负面反馈率
--   ③ 权限点 datastudio:semantic:feedback —— **只用于「处理反馈」**；
--      提交反馈沿用已有的 datastudio:semantic:view（让所有人都能提，才收得到反馈）
--
-- id 选择：dinky_sys_menu 当前最大 id = 189（2026-10-23 实查；188/189 为 P2 的 semantic view/edit），
--          故本批取 190。parent_id = 5（数据开发），order_num = 151（P2 的 149/150 之后）。
-- path 与 perms 自洽：path=/datastudio/semantic/feedback ⇔ perms=datastudio:semantic:feedback
--
-- 幂等：表用 IF NOT EXISTS；加列前查 information_schema（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）；
--       权限点先删角色关联与行本身再插入，重复执行安全。
--
-- 租户说明：本表**不加 tenant_id**。语义项目存放在全局 HDFS 路径 /semantic，本身无租户维度；
--           关联的 dinky_ai_chat_log 亦无租户列，保持与之一致，避免出现「日志与反馈租户口径不一」。

-- ---------------------------------------------------------------------------
-- ① 语义资产反馈表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dinky_semantic_feedback
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_name  VARCHAR(64)  NOT NULL COMMENT '语义项目名（对应 HDFS /semantic/_published/<project>）',
    asset_name    VARCHAR(128) NOT NULL COMMENT '资产名（语义模型名 / 指标名）',
    asset_type    VARCHAR(32)  NULL COMMENT '资产类型：TABLE | METRIC',
    version       VARCHAR(64)  NULL COMMENT '反馈时的发布版本号',
    yaml_sha256   VARCHAR(64)  NULL COMMENT '反馈时资产的 YAML 指纹（用于判断反馈是否已随版本失效）',
    rating        TINYINT      NOT NULL COMMENT '评价：1=有用 / -1=有误',
    feedback_type VARCHAR(32)  NULL COMMENT '问题类型：WRONG_DEFINITION | WRONG_DIMENSION | OUTDATED | OTHER',
    content       TEXT         NULL COMMENT '反馈正文',
    session_id    VARCHAR(64)  NULL COMMENT '来源会话（对应 dinky_ai_chat_log.session_id）',
    user_id       INT          NULL COMMENT '提交人（dinky_sys_user.id）',
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '处理状态：OPEN | RESOLVED | IGNORED',
    resolver_id   INT          NULL COMMENT '处理人（dinky_sys_user.id）',
    resolve_note  TEXT         NULL COMMENT '处理说明',
    resolve_time  DATETIME     NULL COMMENT '处理时间',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（由 DateMetaObjectHandler 填充）',
    PRIMARY KEY (id),
    KEY idx_semfb_project_status (project_name, status),
    KEY idx_semfb_asset (project_name, asset_name),
    KEY idx_semfb_create (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '语义资产反馈（P4 一期：口径错了有地方说）';

-- ---------------------------------------------------------------------------
-- ② dinky_ai_chat_log 加两列（幂等：先查 information_schema 再决定是否 ALTER）
-- ---------------------------------------------------------------------------
SET @has_semantic_project := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'dinky_ai_chat_log'
      AND COLUMN_NAME = 'semantic_project');

SET @ddl_semantic_project := IF(
    @has_semantic_project = 0,
    'ALTER TABLE dinky_ai_chat_log ADD COLUMN semantic_project VARCHAR(64) NULL COMMENT ''本轮注入的语义项目（M5 口径）''',
    'DO 0');

PREPARE stmt_semantic_project FROM @ddl_semantic_project;
EXECUTE stmt_semantic_project;
DEALLOCATE PREPARE stmt_semantic_project;

SET @has_context_fingerprint := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'dinky_ai_chat_log'
      AND COLUMN_NAME = 'context_fingerprint');

SET @ddl_context_fingerprint := IF(
    @has_context_fingerprint = 0,
    'ALTER TABLE dinky_ai_chat_log ADD COLUMN context_fingerprint VARCHAR(64) NULL COMMENT ''本轮上下文指纹（判断重注入是否生效）''',
    'DO 0');

PREPARE stmt_context_fingerprint FROM @ddl_context_fingerprint;
EXECUTE stmt_context_fingerprint;
DEALLOCATE PREPARE stmt_context_fingerprint;

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
