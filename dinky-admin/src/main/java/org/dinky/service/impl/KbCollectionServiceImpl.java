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

import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.model.KbCollection;
import org.dinky.data.model.KbCollectionGrant;
import org.dinky.data.model.rbac.UserTenant;
import org.dinky.mapper.KbCollectionGrantMapper;
import org.dinky.mapper.KbCollectionMapper;
import org.dinky.mapper.UserTenantMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.service.KbCollectionService;
import org.dinky.service.RoleService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库（{@code @kb}）服务实现（知识库 @kb 接入 v1.0）。
 *
 * <p><b>可见性口径</b>（照 {@code SkillServiceImpl#visibleWrapper}）：{@code enabled=1} 且满足
 * 「本人所有 <b>或</b> 全体公开 <b>或</b> 同租户共享」。
 *
 * <p><b>为什么不复用 SkillService 的表</b>：库与 skill 是 1:N 的语义差异（一个库含多篇文档）、
 * 必填列也完全不同（库没有内容目录 / 版本 / 哈希），合并会让两边的列互相污染（先行记录 §3.3）。
 *
 * @since 2026/10/27
 */
@Slf4j
@Service
public class KbCollectionServiceImpl extends SuperServiceImpl<KbCollectionMapper, KbCollection>
        implements KbCollectionService {

    /** 授权表（D-K15：唯一授权入口，按租户/角色/用户） */
    @Autowired
    private KbCollectionGrantMapper grantMapper;

    /** 用户-租户多对多（一人可属多个租户，"按租户授权"的判定依据） */
    @Autowired
    private UserTenantMapper userTenantMapper;

    /** 角色（按角色授权时用） */
    @Autowired
    private RoleService roleService;

    /**
     * 编码规范：{@code c_} + 8 位小写 hex。
     *
     * <p><b>只约束"Dinky 自建库"</b>（批次 D 的建库接口按此生成）。<b>不校验</b>外部导入的既有库：
     * 这类库在 kb-service 里已有自己的 collection 标识，重编码会让 {@code filter.collection}
     * 与 kb-service 对不上、检出一片空——所以这里只定义格式，不做强制断言。
     */
    public static final String CODE_PATTERN = "^c_[0-9a-f]{8}$";

    @Override
    public List<KbCollection> listVisible() {
        return listVisible(currentUserId(), currentTenantId());
    }

    @Override
    public List<KbCollection> listVisible(Integer actorId, Integer tenantId) {
        return list(visibleWrapper(actorId, tenantId));
    }

    @Override
    public List<KbCollectionBrief> listVisibleBriefs() {
        return listVisibleBriefs(currentUserId(), currentTenantId());
    }

    @Override
    public List<KbCollectionBrief> listVisibleBriefs(Integer actorId, Integer tenantId) {
        List<KbCollection> collections = listVisible(actorId, tenantId);
        List<KbCollectionBrief> briefs = new ArrayList<>(collections.size());
        for (KbCollection collection : collections) {
            briefs.add(toBrief(collection));
        }
        return briefs;
    }

    @Override
    public KbCollection getByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<KbCollection>().eq(KbCollection::getCode, StrUtil.trim(code)), false);
    }

    @Override
    public KbCollection getVisibleByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return getOne(
                visibleWrapper(currentUserId(), currentTenantId()).eq(KbCollection::getCode, StrUtil.trim(code)),
                false);
    }

    @Override
    public boolean isVisible(String code) {
        return getVisibleByCode(code) != null;
    }

    /**
     * 可见性条件（<b>唯一</b>的过滤入口，代理层与工具都靠它）。
     *
     * <p>口径（v1.4 收敛后）：{@code enabled=1} 且满足下列<b>任一</b>——
     * <ol>
     *   <li>{@code is_public = 1}（全体登录用户）——<b>唯一</b>的"非授权"可见通道；</li>
     *   <li>{@code owner_id = 我}（属主天然可见，且拥有管理权）；</li>
     *   <li>命中 {@code dinky_kb_collection_grant}（按 <b>租户</b>/角色/用户授权，见
     *       {@link #listGrantedCollectionIds}）。</li>
     * </ol>
     *
     * <p><b>为什么不再有 visibility 三档</b>：{@code private} ≈ "无任何 grant"、
     * {@code tenant} ≈ "grant 给该租户"，两者都能被 grant 表达，留着就是第二套授权入口；
     * 收敛后"授权只看 grant 表"，不会出现"两处都能配、以哪张为准"的困惑（D-K15 修订）。
     *
     * <p>{@code tenantId} 参数保留只为<b>向后兼容方法签名</b>，<b>不参与判定</b>——
     * 归属租户不再自动获得可见性，必须显式授权（这也避免了"人在 A 租户却看到 B 租户库"的隐性扩散）。
     */
    private LambdaQueryWrapper<KbCollection> visibleWrapper(Integer actorId, Integer tenantId) {
        LambdaQueryWrapper<KbCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbCollection::getEnabled, true);

        // 授权命中（唯一的授权来源）
        List<Integer> myTenantIds = listTenantIdsOf(actorId);
        List<Long> grantedIds = listGrantedCollectionIds(actorId, myTenantIds);

        wrapper.and(w -> {
            if (actorId != null) {
                w.eq(KbCollection::getOwnerId, actorId);
            } else {
                // 没有操作者就没有"本人资产"这一支：留一个恒假条件，避免 and() 段为空导致条件被吞
                w.eq(KbCollection::getId, -1L);
            }
            w.or(x -> x.eq(KbCollection::getIsPublic, true));
            if (!grantedIds.isEmpty()) {
                w.or(x -> x.in(KbCollection::getId, grantedIds));
            }
        });
        wrapper.orderByDesc(KbCollection::getUpdateTime);
        return wrapper;
    }

    /** 当前用户所属租户集合（查 {@code dinky_user_tenant}；失败一律降级为空，宁少不多）。 */
    private List<Integer> listTenantIdsOf(Integer actorId) {
        if (actorId == null) {
            return Collections.emptyList();
        }
        try {
            List<UserTenant> relations = userTenantMapper.selectList(
                    new LambdaQueryWrapper<UserTenant>().eq(UserTenant::getUserId, actorId));
            Set<Integer> ids = new LinkedHashSet<>();
            for (UserTenant relation : relations) {
                if (relation != null && relation.getTenantId() != null) {
                    ids.add(relation.getTenantId());
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception e) {
            log.warn("Failed to resolve tenant ids for user {}, fallback to empty set", actorId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 被显式授权的知识库 id（{@code permission=view}）。
     *
     * <p>三类主体一次查完：用户 / 用户所属角色 / 用户所属租户。注意主体条件必须整体包在
     * 一个 {@code and()} 组内用 {@code or()} 连接——否则会生成
     * {@code permission=? AND a OR b}，因 AND 优先级更高而把 write 权限的行也带出来。
     */
    private List<Long> listGrantedCollectionIds(Integer actorId, List<Integer> myTenantIds) {
        // 三类主体各查一次再合并：比在一个 wrapper 里拼 or()/and() 嵌套可靠得多——
        // MyBatis-Plus 的 `or()` 是"直接追加 OR 连接符"，紧跟 `and(consumer)` 会拼出
        // `OR AND (...)` 这种非法片段，且异常会被下面的 catch 静默吞成"无授权"
        // （UAT 2026-10-28 实际踩到：ta_dev1 明明被租户授权却看不到库）。
        List<Integer> roleIds = listRoleIdsOf(actorId);
        Set<Long> ids = new LinkedHashSet<>();
        if (actorId != null) {
            ids.addAll(selectGrantedIds(KbCollectionGrant.SUBJECT_USER, Collections.singletonList(actorId)));
        }
        if (!roleIds.isEmpty()) {
            ids.addAll(selectGrantedIds(KbCollectionGrant.SUBJECT_ROLE, roleIds));
        }
        if (!myTenantIds.isEmpty()) {
            ids.addAll(selectGrantedIds(KbCollectionGrant.SUBJECT_TENANT, myTenantIds));
        }
        return new ArrayList<>(ids);
    }

    /** 按单一主体类型查有效授权（{@code permission=view}）对应的知识库 id。 */
    private Set<Long> selectGrantedIds(String subjectType, List<Integer> subjectIds) {
        return selectGrantedIds(subjectType, subjectIds, KbCollectionGrant.PERMISSION_VIEW);
    }

    /**
     * 按单一主体类型 + 权限档查授权对应的知识库 id。
     *
     * <p>{@code permission} 必须显式传入：view 决定"能不能看"，write 决定"能不能往里推"，
     * 两者混用会让"只读授权"变成"可写授权"（把 view 行当 write 用就是提权）。
     */
    private Set<Long> selectGrantedIds(String subjectType, List<Integer> subjectIds, String permission) {
        Set<Long> ids = new LinkedHashSet<>();
        if (subjectIds == null || subjectIds.isEmpty()) {
            return ids;
        }
        try {
            LambdaQueryWrapper<KbCollectionGrant> query = new LambdaQueryWrapper<>();
            query.eq(KbCollectionGrant::getPermission, permission);
            query.eq(KbCollectionGrant::getSubjectType, subjectType);
            query.in(KbCollectionGrant::getSubjectId, subjectIds);
            query.select(KbCollectionGrant::getCollectionId);
            for (KbCollectionGrant grant : grantMapper.selectList(query)) {
                if (grant != null && grant.getCollectionId() != null) {
                    ids.add(grant.getCollectionId());
                }
            }
        } catch (Exception e) {
            // 不静默：授权解析失败必须能被看见，否则表现为"配了授权却没生效"
            log.error(
                    "Failed to resolve {} grants: subjectType={}, subjectIds={}",
                    permission,
                    subjectType,
                    subjectIds,
                    e);
        }
        return ids;
    }

    @Override
    public boolean canWrite(String code) {
        KbCollection collection = getByCode(code);
        if (collection == null || !Boolean.TRUE.equals(collection.getEnabled())) {
            return false;
        }
        Integer actorId = currentUserId();
        if (actorId == null) {
            return false;
        }
        if (actorId.equals(collection.getOwnerId())) {
            return true;
        }
        return listWritableCollectionIds(actorId).contains(collection.getId());
    }

    @Override
    public List<KbCollectionBrief> listWritableBriefs() {
        Integer actorId = currentUserId();
        if (actorId == null) {
            return Collections.emptyList();
        }
        Set<Long> writableIds = listWritableCollectionIds(actorId);
        // 用可变副本：下面要 addAll，直接改 mapper 返回的集合不可靠
        List<KbCollection> all = new ArrayList<>(list(new LambdaQueryWrapper<KbCollection>()
                .eq(KbCollection::getEnabled, true)
                .eq(KbCollection::getOwnerId, actorId)));
        // 属主的库 + 被授 write 的库：两段合并后按更新时间倒序
        if (!writableIds.isEmpty()) {
            all.addAll(list(new LambdaQueryWrapper<KbCollection>()
                    .eq(KbCollection::getEnabled, true)
                    .in(KbCollection::getId, writableIds)));
        }
        List<KbCollectionBrief> briefs = new ArrayList<>(all.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (KbCollection collection : all) {
            if (seen.add(collection.getId())) {
                briefs.add(toBrief(collection));
            }
        }
        return briefs;
    }

    /** 被显式授予 {@code permission=write} 的库 id（三类主体各查一次后合并，理由同 view） */
    private Set<Long> listWritableCollectionIds(Integer actorId) {
        Set<Long> ids = new LinkedHashSet<>();
        List<Integer> roleIds = listRoleIdsOf(actorId);
        List<Integer> myTenantIds = listTenantIdsOf(actorId);
        ids.addAll(selectGrantedIds(
                KbCollectionGrant.SUBJECT_USER, Collections.singletonList(actorId), KbCollectionGrant.PERMISSION_WRITE));
        if (!roleIds.isEmpty()) {
            ids.addAll(selectGrantedIds(
                    KbCollectionGrant.SUBJECT_ROLE, roleIds, KbCollectionGrant.PERMISSION_WRITE));
        }
        if (!myTenantIds.isEmpty()) {
            ids.addAll(selectGrantedIds(
                    KbCollectionGrant.SUBJECT_TENANT, myTenantIds, KbCollectionGrant.PERMISSION_WRITE));
        }
        return ids;
    }

    /** 用户所属角色 id（按角色授权用；失败降级为空）。 */
    private List<Integer> listRoleIdsOf(Integer actorId) {
        if (actorId == null) {
            return Collections.emptyList();
        }
        try {
            List<Integer> roleIds = roleService.selectRoleListByUserId(actorId);
            if (roleIds == null || roleIds.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(new LinkedHashSet<>(roleIds));
        } catch (Exception e) {
            log.warn("Failed to resolve roles for user {}, fallback to empty", actorId, e);
            return Collections.emptyList();
        }
    }

    private KbCollectionBrief toBrief(KbCollection collection) {
        return KbCollectionBrief.builder()
                .code(collection.getCode())
                .name(collection.getName())
                .description(collection.getDescription())
                .build();
    }

    /** 请求线程内取当前用户；非请求线程（异步池）会抛异常，调用方须用显式参数版本 */
    private Integer currentUserId() {
        try {
            return StpUtil.getLoginIdAsInt();
        } catch (Exception e) {
            log.debug("No login context when resolving kb collections (async thread?)", e);
            return null;
        }
    }

    /** 请求线程内取租户；为 null 表示未带 tenantId cookie / 非请求线程 */
    private Integer currentTenantId() {
        try {
            return Convert.toInt(TenantContextHolder.get(), null);
        } catch (Exception e) {
            return null;
        }
    }
}
