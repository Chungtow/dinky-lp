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

    /** 可见性：同租户共享 */
    public static final String VISIBILITY_TENANT = "tenant";

    /** 授权表（D-K15：按租户/角色/用户额外授权） */
    @Autowired
    private KbCollectionGrantMapper grantMapper;

    /** 用户-租户多对多（一人可属多个租户，D-K15 的"按租户授权"依据此表） */
    @Autowired
    private UserTenantMapper userTenantMapper;

    /** 角色（按角色授权时用） */
    @Autowired
    private RoleService roleService;

    /** 可见性：私有（默认） */
    public static final String VISIBILITY_PRIVATE = "private";

    /** 可见性：全体公开（不限租户，所有登录用户可见） */
    public static final String VISIBILITY_PUBLIC = "public";

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
     * <p>口径（D-K15 起）：{@code enabled=1} 且满足下列<b>任一</b>——
     * <ol>
     *   <li>本人所有（{@code owner_id = 我}）；</li>
     *   <li>{@code visibility = public}（全体登录用户）；</li>
     *   <li>{@code visibility = tenant} 且<b>库的归属租户 ∈ 我的租户集合</b>（依据
     *       {@code dinky_user_tenant}，一人可属多租户——这比只看"当前会话租户"更符合直觉：
     *       人在 A 租户上下文里，依然看得到自己所属 B 租户的共享库）；</li>
     *   <li>或在 {@code dinky_kb_collection_grant} 里被显式授权（按 租户/角色/用户）。</li>
     * </ol>
     *
     * <p>{@code tenantId == null}（异步线程 / 未带租户）时仍能靠"我的租户集合"放行 tenant 档；
     * 若连 actorId 都没有，则只剩 public ——宁可少给也不能多给。
     */
    private LambdaQueryWrapper<KbCollection> visibleWrapper(Integer actorId, Integer tenantId) {
        LambdaQueryWrapper<KbCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbCollection::getEnabled, true);

        // D-K15：用户所属租户（dinky_user_tenant 是真多对多）与显式授权命中的库
        List<Integer> myTenantIds = listTenantIdsOf(actorId);
        List<Long> grantedIds = listGrantedCollectionIds(actorId, myTenantIds);

        wrapper.and(w -> {
            if (actorId != null) {
                w.eq(KbCollection::getOwnerId, actorId);
            } else {
                // 没有操作者就没有"本人资产"这一支：留一个恒假条件，避免 and() 段为空导致条件被吞
                w.eq(KbCollection::getId, -1L);
            }
            w.or(x -> x.eq(KbCollection::getVisibility, VISIBILITY_PUBLIC));
            if (!myTenantIds.isEmpty()) {
                w.or(x -> x.eq(KbCollection::getVisibility, VISIBILITY_TENANT)
                        .in(KbCollection::getTenantId, myTenantIds));
            }
            if (tenantId != null) {
                // 兼容：显式传入的会话租户也放行（与改造前行为一致，避免回退）
                w.or(x -> x.eq(KbCollection::getVisibility, VISIBILITY_TENANT).eq(KbCollection::getTenantId, tenantId));
            }
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
        List<Integer> roleIds = listRoleIdsOf(actorId);
        if (actorId == null && roleIds.isEmpty() && myTenantIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            LambdaQueryWrapper<KbCollectionGrant> query = new LambdaQueryWrapper<>();
            query.eq(KbCollectionGrant::getPermission, KbCollectionGrant.PERMISSION_VIEW);
            query.select(KbCollectionGrant::getCollectionId);
            query.and(w -> {
                boolean first = true;
                if (actorId != null) {
                    w.and(x -> x.eq(KbCollectionGrant::getSubjectType, KbCollectionGrant.SUBJECT_USER)
                            .eq(KbCollectionGrant::getSubjectId, actorId));
                    first = false;
                }
                if (!roleIds.isEmpty()) {
                    if (!first) {
                        w.or();
                    }
                    w.and(x -> x.eq(KbCollectionGrant::getSubjectType, KbCollectionGrant.SUBJECT_ROLE)
                            .in(KbCollectionGrant::getSubjectId, roleIds));
                    first = false;
                }
                if (!myTenantIds.isEmpty()) {
                    if (!first) {
                        w.or();
                    }
                    w.and(x -> x.eq(KbCollectionGrant::getSubjectType, KbCollectionGrant.SUBJECT_TENANT)
                            .in(KbCollectionGrant::getSubjectId, myTenantIds));
                    first = false;
                }
                if (first) {
                    w.eq(KbCollectionGrant::getId, -1L);
                }
            });
            Set<Long> ids = new LinkedHashSet<>();
            for (KbCollectionGrant grant : grantMapper.selectList(query)) {
                if (grant != null && grant.getCollectionId() != null) {
                    ids.add(grant.getCollectionId());
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception e) {
            log.warn("Failed to resolve granted collections for user {}, fallback to empty", actorId, e);
            return Collections.emptyList();
        }
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
