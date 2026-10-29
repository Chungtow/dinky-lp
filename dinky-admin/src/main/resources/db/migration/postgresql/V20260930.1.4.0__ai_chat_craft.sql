-- AI Chat 阶段 2（Craft 写能力）：审计表增加「写入审计」列
-- write_task_id     ：被 AI 整块改写的作业 id
-- write_before_hash ：改写前内容 hash（只存 hash 不存正文，避免业务代码正文撑大审计表）
-- write_after_hash  ：改写后内容 hash
-- write_chars       ：内容字符数变化（正数为增加、负数为删减）
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN IF NOT EXISTS write_task_id BIGINT NULL;
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN IF NOT EXISTS write_before_hash VARCHAR(64) NULL;
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN IF NOT EXISTS write_after_hash VARCHAR(64) NULL;
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN IF NOT EXISTS write_chars INT NULL;

COMMENT ON COLUMN dinky_ai_chat_log.write_task_id IS '被 AI 改写的作业 id';
COMMENT ON COLUMN dinky_ai_chat_log.write_before_hash IS '改写前内容 hash';
COMMENT ON COLUMN dinky_ai_chat_log.write_after_hash IS '改写后内容 hash';
COMMENT ON COLUMN dinky_ai_chat_log.write_chars IS '改写字符数变化（正增负减）';
