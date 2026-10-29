-- =====================================================================================
-- 知识库可见性模型收敛（2026-10-28，UAT 反馈：两处都能授权 → 以哪张为准？）
--
-- 【问题】原设计中 `dinky_kb_collection.visibility(private|tenant|public)` 与
--        `dinky_kb_collection_grant` 都能表达"谁能看这个库"，形成双真相源：
--          private ≈ 无任何授权
--          tenant  ≈ 授权给某租户（与 grant(subject_type='tenant') 完全重叠）
--          public  = 全局可见（grant 表达不了）
--        实现时两套逻辑各算一遍，且"以哪张为准"无从回答。
--
-- 【收敛】collection 只描述"这是谁的库"，授权只走 grant：
--          is_public = 1            → 全体登录用户可见（唯一非授权通道）
--          owner_id = 我            → 属主可见（并拥有管理权）
--          命中 grant               → 按 租户/角色/用户 授权（含 write）
--        删除 visibility 列；tenant_id 语义降级为"组织归属"，
--        **不再自动赋予可见性**（要按租户可见就必须显式插 grant）。
--
-- 【映射】public → is_public=1；private → is_public=0（无 grant 即"仅属主"）；
--        tenant  → is_public=0 + 补一条 grant(tenant, tenant_id, view)
-- =====================================================================================

ALTER TABLE dinky_kb_collection
    ADD COLUMN is_public tinyint(4) NOT NULL DEFAULT 0 COMMENT '是否公开（唯一的非授权可见通道）' AFTER tenant_id;

-- ① public → is_public = 1
UPDATE dinky_kb_collection
SET is_public = 1
WHERE visibility = 'public';

-- ② tenant → 补 grant（幂等：唯一键冲突由 INSERT IGNORE 吃掉）
INSERT IGNORE INTO dinky_kb_collection_grant (collection_id, subject_type, subject_id, permission, create_by,
                                              create_time, update_time)
SELECT c.id, 'tenant', c.tenant_id, 'view', c.owner_id, NOW(), NOW()
FROM dinky_kb_collection c
WHERE c.visibility = 'tenant'
  AND c.tenant_id IS NOT NULL;

-- ③ private → 无需动作（is_public 默认 0，且無 grant 即仅属主可见）

-- ④ 删除旧列（此后"授权"只有一个表）
ALTER TABLE dinky_kb_collection
    DROP COLUMN visibility;
