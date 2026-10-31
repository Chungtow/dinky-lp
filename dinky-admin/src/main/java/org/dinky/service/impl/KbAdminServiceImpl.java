/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.service.impl;

import org.dinky.ai.asset.MentionCandidateService;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.KbCollectionSaveDTO;
import org.dinky.data.dto.KbGrantSaveDTO;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.KbCollection;
import org.dinky.data.model.KbCollectionGrant;
import org.dinky.data.vo.KbOrphanVO;
import org.dinky.mapper.KbCollectionGrantMapper;
import org.dinky.mapper.KbCollectionMapper;
import org.dinky.service.KbAdminService;
import org.dinky.service.KbCollectionService;
import org.dinky.service.UserService;
import org.dinky.service.kb.KbClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库管理实现（批次 D）。
 *
 * @since 2026/10/28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbAdminServiceImpl implements KbAdminService {

    /** 未纳管库最多带回 50 个：再多说明 RAG 侧已经乱到该先做清理，而不是一次性全量纳管 */
    private static final int MAX_ORPHANS = 50;

    private final KbCollectionMapper kbCollectionMapper;

    private final KbCollectionGrantMapper kbCollectionGrantMapper;

    private final KbCollectionService kbCollectionService;

    private final UserService userService;

    private final KbClient kbClient;

    /**
     * {@code @} 候选快照失效器。
     *
     * <p><b>为什么用 {@code ObjectProvider} 而不是直接注入</b>：存在构造器循环依赖
     * {@code KbAdminServiceImpl → MentionCandidateService → KbAssetProvider → KbCollectionService}
     * （Spring Boot 2.6+ 默认禁止循环引用，直接注入会在启动期抛
     * {@code BeanCurrentlyInCreationException}）。{@code ObjectProvider} 延迟到<b>调用时</b>解析，
     * 打破构造期闭环；失效只是"锦上添花"的旁路，取不到就不做（60s TTL 仍会兜底）。
     */
    private final ObjectProvider<MentionCandidateService> mentionCandidateService;

    /**
     * 资产写操作后失效 {@code @} 候选快照，让库名 / 描述 / 可见性的变更<b>立即</b>反映到候选。
     *
     * <p>未接此钩子时只能等后端 60s TTL，叠加浏览器缓存会表现为"改名后永远看不到新名"
     * （见 `docs/plan/20261031-Dinky @候选快照ETag缓存陈旧修复计划.md`）。
     */
    private void invalidateMentionIndex() {
        MentionCandidateService service = mentionCandidateService.getIfAvailable();
        if (service != null) {
            service.invalidate(Convert.toInt(TenantContextHolder.get(), null));
        }
    }

    @Override
    public List<KbCollection> listManageable() {
        Integer actorId = currentUserId();
        if (actorId == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<KbCollection> query = new LambdaQueryWrapper<KbCollection>()
                .orderByDesc(KbCollection::getUpdateTime)
                .orderByDesc(KbCollection::getId);
        if (!isSuperAdmin(actorId)) {
            // 只看自己属主的库：仅被授权 view/write 的库不出现（配置不归他管）
            query.eq(KbCollection::getOwnerId, actorId);
        }
        return kbCollectionMapper.selectList(query);
    }

    @Override
    public KbCollection saveCollection(KbCollectionSaveDTO dto) {
        Integer actorId = currentUserId();
        if (actorId == null) {
            throw BusException.of("未登录");
        }
        String name = StrUtil.trimToEmpty(dto.getName());
        if (StrUtil.isBlank(name)) {
            throw BusException.of("知识库名称不能为空");
        }
        if (name.length() > 64) {
            throw BusException.of("知识库名称过长（>64）");
        }

        if (dto.getId() == null) {
            // ---------- 新建：code 服务端生成，前端无权指定 ----------
            assertNameUnique(name, null, currentTenantId());
            KbCollection collection = new KbCollection();
            collection.setCode(nextCode());
            collection.setName(name);
            collection.setDescription(StrUtil.sub(dto.getDescription(), 0, 255));
            collection.setOwnerId(actorId);
            collection.setTenantId(currentTenantId());
            // 新建默认不公开：先内部验证，确认内容可用再放开（与纳管同一风险观）
            collection.setIsPublic(Boolean.TRUE.equals(dto.getIsPublic()));
            // 新建默认启用：草稿态是给"定时探测发现的新库"用的，人手动建的就是要用
            collection.setEnabled(dto.getEnabled() == null || Boolean.TRUE.equals(dto.getEnabled()));
            kbCollectionMapper.insert(collection);
            log.info("KB collection created: code={}, name={}, owner={}", collection.getCode(), name, actorId);
            // 新增库 → 候选多一条，必须立即失效（否则要等 60s，叠加浏览器缓存会被误判为"没保存"）
            invalidateMentionIndex();
            return collection;
        }

        // ---------- 编辑：只允许 name / description / isPublic / enabled ----------
        KbCollection existing = requireManageable(dto.getId());
        assertNameUnique(name, existing.getId(), existing.getTenantId());
        existing.setName(name);
        existing.setDescription(StrUtil.sub(dto.getDescription(), 0, 255));
        if (dto.getIsPublic() != null) {
            existing.setIsPublic(dto.getIsPublic());
        }
        if (dto.getEnabled() != null) {
            // enabled=0 是"停用/草稿"，不是删除：历史出处仍要能追溯（立项项 #3/#4）
            existing.setEnabled(dto.getEnabled());
        }
        existing.setUpdateTime(LocalDateTime.now());
        kbCollectionMapper.updateById(existing);
        // 改名 / 改描述 / 启停 / 改公开性都会改变候选的展示内容或可见集合
        invalidateMentionIndex();
        return existing;
    }

    @Override
    public List<KbOrphanVO> listOrphans() {
        requireSuperAdmin();

        Set<String> registered =
                kbCollectionMapper
                        .selectList(new LambdaQueryWrapper<KbCollection>().select(KbCollection::getCode))
                        .stream()
                        .map(KbCollection::getCode)
                        .collect(Collectors.toSet());

        JSONObject body = kbClient.listCollections();
        JSONArray items = body.getJSONArray("items");
        List<KbOrphanVO> orphans = new ArrayList<>();
        if (items == null) {
            return orphans;
        }
        for (Object item : items) {
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject json = (JSONObject) item;
            String code = json.getStr("collection");
            if (StrUtil.isBlank(code) || registered.contains(code)) {
                continue;
            }
            KbOrphanVO vo = new KbOrphanVO();
            vo.setCode(code);
            vo.setDocs(json.getLong("docs", 0L));
            vo.setChunks(json.getLong("chunks", 0L));
            vo.setLastTs(json.getLong("last_ts", 0L));
            orphans.add(vo);
            if (orphans.size() >= MAX_ORPHANS) {
                break;
            }
        }
        // 抽一篇样本文件名：管理员据此判断"这是谁的数据、要不要纳管"
        for (KbOrphanVO orphan : orphans) {
            try {
                JSONObject docs = kbClient.documents(orphan.getCode(), null, "updated_desc", 1, 0);
                JSONArray docItems = docs.getJSONArray("items");
                if (docItems != null && !docItems.isEmpty() && docItems.get(0) instanceof JSONObject) {
                    orphan.setSampleFilename(((JSONObject) docItems.get(0)).getStr("filename"));
                }
            } catch (RuntimeException e) {
                // 抽样失败不影响列表本身（可能这个库刚被清空）
                log.debug("KB orphan sample failed: {}", orphan.getCode());
            }
        }
        return orphans;
    }

    @Override
    public KbCollection adopt(String code, String name, String description, Boolean isPublic) {
        requireSuperAdmin();
        String trimmed = StrUtil.trimToEmpty(code);
        if (StrUtil.isBlank(trimmed)) {
            throw BusException.of("未指定要纳管的库标识");
        }
        if (kbCollectionService.getByCode(trimmed) != null) {
            throw BusException.of("该库已纳管：" + trimmed);
        }
        Integer actorId = currentUserId();
        KbCollection collection = new KbCollection();
        // code 原样沿用 RAG 标识：重编码 = filter.collection 对不上 = 检出一片空（D-K12）
        collection.setCode(trimmed);
        collection.setName(StrUtil.blankToDefault(StrUtil.trimToEmpty(name), trimmed));
        collection.setDescription(StrUtil.sub(description, 0, 255));
        collection.setOwnerId(actorId);
        collection.setTenantId(currentTenantId());
        collection.setIsPublic(Boolean.TRUE.equals(isPublic));
        collection.setEnabled(true);
        kbCollectionMapper.insert(collection);
        log.info("KB collection adopted: code={}, by={}, public={}", trimmed, actorId, collection.getIsPublic());
        // 纳管即新增一条可见候选
        invalidateMentionIndex();
        return collection;
    }

    @Override
    public List<KbCollectionGrant> listGrants(Long collectionId) {
        requireManageable(collectionId);
        return kbCollectionGrantMapper.selectList(new LambdaQueryWrapper<KbCollectionGrant>()
                .eq(KbCollectionGrant::getCollectionId, collectionId)
                .orderByAsc(KbCollectionGrant::getSubjectType)
                .orderByAsc(KbCollectionGrant::getSubjectId));
    }

    @Override
    public int addGrants(Long collectionId, KbGrantSaveDTO dto) {
        requireManageable(collectionId);
        String subjectType = StrUtil.trimToEmpty(dto.getSubjectType()).toLowerCase();
        if (!KbCollectionGrant.SUBJECT_USER.equals(subjectType)
                && !KbCollectionGrant.SUBJECT_ROLE.equals(subjectType)
                && !KbCollectionGrant.SUBJECT_TENANT.equals(subjectType)) {
            throw BusException.of("主体类型不合法：" + dto.getSubjectType() + "（只能是 tenant / role / user）");
        }
        String permission = StrUtil.blankToDefault(dto.getPermission(), KbCollectionGrant.PERMISSION_VIEW)
                .toLowerCase();
        if (!KbCollectionGrant.PERMISSION_VIEW.equals(permission)
                && !KbCollectionGrant.PERMISSION_WRITE.equals(permission)) {
            throw BusException.of("权限档不合法：" + dto.getPermission() + "（只能是 view / write）");
        }
        if (dto.getSubjectIds() == null || dto.getSubjectIds().isEmpty()) {
            throw BusException.of("请选择要授权的主体");
        }

        int added = 0;
        Integer actorId = currentUserId();
        for (Integer subjectId : new HashSet<>(dto.getSubjectIds())) {
            if (subjectId == null) {
                continue;
            }
            // 已存在则跳过（管理页点两次不该报错）；升级 view → write 是显式操作，不在这里默默做
            Long exists = kbCollectionGrantMapper.selectCount(new LambdaQueryWrapper<KbCollectionGrant>()
                    .eq(KbCollectionGrant::getCollectionId, collectionId)
                    .eq(KbCollectionGrant::getSubjectType, subjectType)
                    .eq(KbCollectionGrant::getSubjectId, subjectId));
            if (exists != null && exists > 0) {
                continue;
            }
            kbCollectionGrantMapper.insert(KbCollectionGrant.builder()
                    .collectionId(collectionId)
                    .subjectType(subjectType)
                    .subjectId(subjectId)
                    .permission(permission)
                    .createBy(actorId)
                    // 授权表的时间列是 java.util.Date（与 KbCollection 的 LocalDateTime 不同）
                    .createTime(new Date())
                    .updateTime(new Date())
                    .build());
            added++;
        }
        // 授权本身不缓存，但 @ 候选快照缓存了"谁能看到哪些库"——新增授权后必须失效，
        // 否则被授权者最长 60s 内在 @ 候选里看不到该库（D-K15 的"即时生效"对检索成立，对候选需显式失效）
        log.info("KB grants added: collection={}, type={}, count={}, by={}", collectionId, subjectType, added, actorId);
        invalidateMentionIndex();
        return added;
    }

    @Override
    public void deleteGrant(Long grantId) {
        if (grantId == null) {
            throw BusException.of("未指定授权记录");
        }
        KbCollectionGrant grant = kbCollectionGrantMapper.selectById(grantId);
        if (grant == null) {
            throw BusException.of("授权记录不存在（可能已被撤销）");
        }
        requireManageable(grant.getCollectionId());
        kbCollectionGrantMapper.deleteById(grantId);
        // 撤权同样改变候选可见集合
        invalidateMentionIndex();
    }

    // ------------------------------------------------------------------ 内部

    /** 属主或超管才能改配置/授权 */
    private KbCollection requireManageable(Long collectionId) {
        KbCollection collection = collectionId == null ? null : kbCollectionMapper.selectById(collectionId);
        if (collection == null) {
            throw BusException.of("知识库不存在（可能已被停用或删除）");
        }
        Integer actorId = currentUserId();
        if (!isSuperAdmin(actorId) && !actorId.equals(collection.getOwnerId())) {
            log.warn(
                    "KB manage denied: user={}, collection={}, owner={}",
                    actorId,
                    collectionId,
                    collection.getOwnerId());
            throw BusException.of("只有属主或超级管理员可以修改该知识库");
        }
        return collection;
    }

    private void requireSuperAdmin() {
        if (!isSuperAdmin(currentUserId())) {
            throw BusException.of("只有超级管理员可以查看未纳管的库");
        }
    }

    /** 同租户内名称唯一：@ 候选里靠名字认库，重名等于让人选错 */
    private void assertNameUnique(String name, Long excludeId, Integer tenantId) {
        LambdaQueryWrapper<KbCollection> query = new LambdaQueryWrapper<KbCollection>()
                .eq(KbCollection::getName, name)
                .ne(excludeId != null, KbCollection::getId, excludeId);
        if (tenantId == null) {
            query.isNull(KbCollection::getTenantId);
        } else {
            query.eq(KbCollection::getTenantId, tenantId);
        }
        Long count = kbCollectionMapper.selectCount(query);
        if (count != null && count > 0) {
            throw BusException.of("同租户下已存在同名知识库：" + name);
        }
    }

    /** {@code c_} + 8 位小写 hex，与 {@code KbCollectionServiceImpl.CODE_PATTERN} 一致 */
    private String nextCode() {
        for (int i = 0; i < 10; i++) {
            String code = "c_" + RandomUtil.randomString("0123456789abcdef", 8);
            if (kbCollectionService.getByCode(code) == null) {
                return code;
            }
        }
        // 撞 10 次几乎不可能；真撞上说明 code 空间被污染，宁可失败也不要静默覆盖
        throw BusException.of("生成知识库编码失败，请重试");
    }

    private boolean isSuperAdmin(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(userService.checkSuperAdmin(userId));
        } catch (Exception e) {
            // 查不到就当不是超管：管理类判断只能"从严"
            log.warn("checkSuperAdmin failed for {}", userId, e);
            return false;
        }
    }

    private Integer currentUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId instanceof Number) {
                return ((Number) loginId).intValue();
            }
            return StrUtil.isBlank(String.valueOf(loginId)) ? null : Integer.valueOf(String.valueOf(loginId));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer currentTenantId() {
        try {
            return Convert.toInt(TenantContextHolder.get(), null);
        } catch (Exception e) {
            return null;
        }
    }
}
