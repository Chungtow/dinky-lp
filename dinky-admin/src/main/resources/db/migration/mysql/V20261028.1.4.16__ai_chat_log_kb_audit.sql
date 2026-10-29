-- 知识库（@kb）接入 · 批次 D：对话审计补三列（D3/D4）
--
-- 为什么不能只靠现有 tool_calls 文本：
--   ① tool_calls 是自由文本（截断到 200 字/次），"哪些库被检索过"要靠 grep，统计不了；
--   ② 越权请求在工具侧只表现为一句失败原因，没有结构化留痕——出问题时没法回答
--      "这个账号最近有没有反复试别的库"；
--   ③ kbHits 记 0 是有意义的结论：说明"检索了，但库里确实没有"，与"根本没检索"必须能区分。
--
-- 三个字段都允许 NULL：绝大多数对话不涉及知识库，NULL = 与知识库无关；
-- 而空串与 NULL 混用会让统计失真，故一律 NULL。

ALTER TABLE dinky_ai_chat_log
    ADD COLUMN kb_collections VARCHAR(512) NULL COMMENT 'collections actually searched in this turn (comma separated)',
    ADD COLUMN kb_hits        INT          NULL COMMENT 'total hits returned by kb_search (0 is meaningful)',
    ADD COLUMN kb_denied      VARCHAR(512) NULL COMMENT 'collections explicitly denied (permission violation attempts)';
