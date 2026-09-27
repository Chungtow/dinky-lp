-- AI Chat 阶段 0（正确性闭环）：对话审计日志表
-- 记录「谁在什么时候问了什么、消耗多少 token、生成的 SQL 是否可执行」，并为按天配额提供统计来源。
CREATE TABLE IF NOT EXISTS dinky_ai_chat_log
(
    id                BIGSERIAL PRIMARY KEY,
    user_id           INT         NULL,
    session_id        VARCHAR(64) NULL,
    action            VARCHAR(32) NULL,
    model             VARCHAR(64) NULL,
    database_id       INT         NULL,
    schema_name       VARCHAR(128) NULL,
    question          TEXT        NULL,
    sql_text          TEXT        NULL,
    exec_status       VARCHAR(16) NULL,
    exec_error        VARCHAR(2000) NULL,
    retry_count       INT         DEFAULT 0,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    duration_ms       BIGINT      NULL,
    success           SMALLINT    NULL,
    create_time       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_log_user_time ON dinky_ai_chat_log (user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_ai_chat_log_create_time ON dinky_ai_chat_log (create_time);

COMMENT ON TABLE dinky_ai_chat_log IS 'AI 对话审计日志';
