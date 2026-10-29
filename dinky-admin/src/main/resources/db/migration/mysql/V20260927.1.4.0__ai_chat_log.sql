-- AI Chat 阶段 0（正确性闭环）：对话审计日志表
-- 记录「谁在什么时候问了什么、消耗多少 token、生成的 SQL 是否可执行」，并为按天配额提供统计来源。
CREATE TABLE IF NOT EXISTS dinky_ai_chat_log
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           INT         NULL COMMENT '操作用户',
    session_id        VARCHAR(64) NULL COMMENT '会话 id',
    action            VARCHAR(32) NULL COMMENT 'TEXT_TO_SQL / EXPLAIN',
    model             VARCHAR(64) NULL COMMENT '模型名称',
    database_id       INT         NULL COMMENT '数据源 id',
    schema_name       VARCHAR(128) NULL COMMENT 'schema',
    question          TEXT        NULL COMMENT '用户输入',
    sql_text          MEDIUMTEXT  NULL COMMENT '模型生成并被校验的 SQL',
    exec_status       VARCHAR(16) NULL COMMENT 'none / verified / failed / rejected',
    exec_error        VARCHAR(2000) NULL COMMENT '数据源返回的原始错误',
    retry_count       INT         DEFAULT 0 COMMENT '自动修复重试次数',
    prompt_tokens     INT         NULL COMMENT '提示词 token',
    completion_tokens INT         NULL COMMENT '生成 token',
    duration_ms       BIGINT      NULL COMMENT '端到端耗时（毫秒）',
    success           TINYINT     NULL COMMENT '1 成功 / 0 失败',
    create_time       DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_ai_chat_log_user_time (user_id, create_time),
    KEY idx_ai_chat_log_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 对话审计日志';
