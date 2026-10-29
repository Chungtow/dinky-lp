-- AI Chat 阶段 3（多 LLM 实例配置）：审计表增加「实际使用的 LLM 实例 id」列
-- 背景：model 只记模型名，同一模型跑在不同网关/实例（baseUrl 不同）时无法区分，
--       也无法按实例做用量与追责分析，故补 profile_id。
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN IF NOT EXISTS profile_id VARCHAR(64) NULL;

COMMENT ON COLUMN dinky_ai_chat_log.profile_id IS '实际使用的 LLM 实例 id（阶段 3）';
