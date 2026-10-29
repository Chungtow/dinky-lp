-- AI Chat 阶段 1b（只读工具 / Agent Loop）：审计表增加工具调用列
-- tool_call_count：本次对话的工具调用次数（0 表示未使用工具）
-- tool_calls   ：工具调用摘要 JSON（工具名 / 参数 / 成败 / 耗时），已做字符限制
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN tool_call_count INT DEFAULT 0 COMMENT '工具调用次数';
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN tool_calls TEXT NULL COMMENT '工具调用明细摘要 JSON';
