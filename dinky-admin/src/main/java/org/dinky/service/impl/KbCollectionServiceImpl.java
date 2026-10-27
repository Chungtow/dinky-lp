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
import org.dinky.mapper.KbCollectionMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.service.KbCollectionService;

import java.util.ArrayList;
import java.util.List;

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
     * <p>与 {@code SkillServiceImpl#visibleWrapper} 逐条对齐：{@code enabled=1} +
     * （本人 / public / 同租户）。{@code tenantId == null} 时<b>不</b>放行 tenant 档
     * ——宁可少给也不能多给（异步线程拿不到租户时宁缺勿滥）。
     */
    private LambdaQueryWrapper<KbCollection> visibleWrapper(Integer actorId, Integer tenantId) {
        LambdaQueryWrapper<KbCollection> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbCollection::getEnabled, true);
        wrapper.and(w -> {
            if (actorId != null) {
                w.eq(KbCollection::getOwnerId, actorId);
            } else {
                // 没有操作者就没有"本人资产"这一支：留一个恒假条件，避免 and() 段为空导致条件被吞
                w.eq(KbCollection::getId, -1L);
            }
            w.or(x -> x.eq(KbCollection::getVisibility, VISIBILITY_PUBLIC));
            if (tenantId != null) {
                w.or(x -> x.eq(KbCollection::getVisibility, VISIBILITY_TENANT).eq(KbCollection::getTenantId, tenantId));
            }
        });
        wrapper.orderByDesc(KbCollection::getUpdateTime);
        return wrapper;
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
