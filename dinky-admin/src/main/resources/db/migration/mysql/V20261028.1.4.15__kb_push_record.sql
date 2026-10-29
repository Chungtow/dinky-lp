-- 知识库（@kb）接入 · 批次 C：推送台账
--
-- 为什么必须有这张表（2026-10-28 用户提问引出的缺口）：
--   三种来源里只有 skills 有业务表可挂 kb_doc_id（V20261028.1.4.14）——
--     · 语义层：内容真相源是 HDFS（/semantic/<project>/...），Dinky 元数据库**没有任何表**；
--     · 资源中心：dinky_resources 是"文件管理"语义，硬加一列会让资源表承担知识库状态；
--     · 编辑器（未来）：同样没有业务表。
--   没有台账时，推送完的 doc_id 只活在响应里：文件一关，"这个语义文件推过哪几版、
--   要清旧版该删哪个 id"就无从查起（A 侧没有"按 source 反查"的接口）。
--
-- 设计取舍：
--   · 一条 (source, biz_key, doc_id)：同一文件重推**同内容**时 dedup，不新增行；
--     内容改过则是新 doc_id，**新增一行**——正好是"版本历史"，也是将来清旧版的依据。
--   · collections 存逗号串而非子表：它只是 A 侧合并结果的回声，没有按库查询的需求。
--   · 语义层的 biz_key 用 `项目/路径`：文件重命名/移动会断链，这是**刻意接受**的——
--     重命名后本来就是另一个文件，旧记录留着还能查上一版是谁推的。
--   · 不做外键：biz_key 是多态列（skill id / 资源 id / 路径），无法指向单表。

CREATE TABLE IF NOT EXISTS dinky_kb_push_record
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    source      VARCHAR(16)  NOT NULL COMMENT '来源：semantic / skill / resource / editor',
    biz_key     VARCHAR(512) NOT NULL COMMENT '业务键：skill id / 资源 id / 语义层 项目/路径',
    doc_id      VARCHAR(64)  NOT NULL COMMENT 'kb-service 的 doc_id（sha256 正文）',
    collections VARCHAR(512) NULL COMMENT '推送后的最终归属（逗号分隔，A 侧只加不减）',
    filename    VARCHAR(255) NULL COMMENT '推送时的文件名（区分同一 bizKey 的版本）',
    pushed_by   INT          NULL COMMENT '推送人 user id',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次推送时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次推送时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_push_source_biz_doc (source(16), biz_key(200), doc_id),
    KEY idx_kb_push_biz (biz_key(200), update_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '知识库推送台账：哪个来源的哪份内容推成了哪个 doc_id';
