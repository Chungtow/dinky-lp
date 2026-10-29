-- 知识库（@kb）接入 · 批次 C：保存 skill 推送后得到的 doc_id（B11b）
--
-- 为什么值得单开一列（先行记录 §10.3）：
--   ① 将来"编辑后重推并清掉旧版本"必须有旧 id 才能 DELETE——A 侧明确不提供
--      "按 source 反查"的接口，没有这一列就无路可走；
--   ② 排障时能和 kb-service 的 meta.json / Qdrant payload 对上（doc_id 是唯一钥匙）；
--   ③ 界面可显示"已推送（前 8 位）"，并跳到知识库 tab 查看。
--
-- 为什么可为 NULL：绝大多数 skill 从未推送过；NULL 与空串语义相同，一律用 NULL。
--
-- 注意：语义层（HDFS 文件）与资源中心（HDFS 文件）没有"业务表"，其推送结果只回显不落库。
--      这不是遗漏——它们的内容真相源在 HDFS，多存一份 id 就会与文件内容漂移。

ALTER TABLE dinky_skill
    ADD COLUMN kb_doc_id VARCHAR(64) NULL COMMENT 'last pushed kb doc_id (sha256 of content)';
