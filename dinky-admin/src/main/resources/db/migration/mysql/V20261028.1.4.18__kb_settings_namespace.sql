-- 知识库（@kb）接入 · v1.9：配置项迁出 LLM 命名空间，成为配置中心的并列分组
--
-- 背景（2026-10-28 用户提出）：配置中心里找不到"我们的 Dinky 连的是哪个 RAG"。
--   ① 四项配置寄生在 sys.llm.settings.* 下，只能到「LLM」页签的 49 项里翻；
--   ② key 名 kbBaseUrl 与实际语义（连的是 kb-service，不是 LLM 提供商）不符；
--   ③ 配置项的中文名/说明词条（sys.llm.settings.kbBaseUrl / .note）从未补过，
--      接口返回的就是原始 key —— 界面上等于没有标签。
--
-- 处置：key 改到 sys.kb.settings.*（配置中心按 key 第二段分组，SystemConfiguration
--      .getAllConfiguration() 取 split(".")[1]），与 sys.llm.* / sys.semantic.* 并列。
--
-- ⚠️ 本迁移与代码里的 key 改名必须同批发布：
--   缺迁移 → 库里旧行成孤儿、新键按默认值（空 baseUrl）生效，知识库直接不可用（连不上 kb-service）；
--   缺代码 → 配置页看不到这几项（旧 key 仍能在 DB 查到，但无人读取）。
--
-- 幂等性：按旧名逐条 UPDATE，重复执行影响 0 行，不会覆盖已改名后的值。

UPDATE dinky_sys_config SET name = 'sys.kb.settings.enable', update_time = NOW()
WHERE name = 'sys.llm.settings.kbEnable';

UPDATE dinky_sys_config SET name = 'sys.kb.settings.maxChars', update_time = NOW()
WHERE name = 'sys.llm.settings.kbMaxChars';

UPDATE dinky_sys_config SET name = 'sys.kb.settings.baseUrl', update_time = NOW()
WHERE name = 'sys.llm.settings.kbBaseUrl';

UPDATE dinky_sys_config SET name = 'sys.kb.settings.apiKey', update_time = NOW()
WHERE name = 'sys.llm.settings.kbApiKey';

-- 旧命名的残留清理：若历史版本曾把 .note 键误写进表（当前没有，防将来脚本带进来）
DELETE FROM dinky_sys_config WHERE name LIKE 'sys.llm.settings.kb%';
