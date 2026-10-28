-- 知识库（@kb）接入 · 批次 E：对话审计记录「默认绑定的知识库」
--
-- 为什么 kb_collections 不够：
--   kb_collections 记的是「本轮实际检索了哪些库」（工具层事实）。批次 E 之后还有另一个事实——
--   「左侧栏当前选中的库是哪个」（前端绑定、注入层校验过的默认范围）。两者经常不一致：
--     · 用户绑了 A，但手打 @kb/B → 绑定值 A 被显式覆盖（D-K16）
--     · 用户绑了 A，但 A 被停用/撤权 → 绑定被拒并写 kb_denied（D-K17）
--     · 模型自己传了 collection=C → 检索 C，绑定值仍是 A
--   只有把默认值单独记下来，排障时才能回答"他到底以为自己在查哪个库"。
--
-- NULL = 本轮未绑定（未打开知识库 tab / 主动解绑 / kb 开关关闭）。
ALTER TABLE dinky_ai_chat_log
    ADD COLUMN kb_default VARCHAR(64) NULL COMMENT 'default bound collection of this turn (left toolbar selection, batch E)';
