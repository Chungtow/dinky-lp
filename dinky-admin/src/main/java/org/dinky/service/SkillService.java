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

package org.dinky.service;

import org.dinky.data.model.Skill;
import org.dinky.mybatis.service.ISuperService;

import java.util.List;

/**
 * Skill 服务（阶段 4a：Skills 基础与引用）。
 *
 * <p><b>权限一律走显式条件</b>（{@code owner_id} / {@code tenant_id} / {@code visibility}），
 * <b>不</b>依赖 MyBatis-Plus 租户插件、也<b>不</b>依赖 {@code TenantContextHolder} 做过滤——
 * 本表未加入 {@code MybatisPlusConfig.IGNORE_TABLE_NAMES}（插件本就不管它），且 AI 对话跑在异步线程，
 * 那里租户上下文会丢（见调研 §4.6.1 事实 1/3）。
 *
 * @since 2026/10/05
 */
public interface SkillService extends ISuperService<Skill> {

    /** 当前用户<b>可见</b>的 skill 列表（仅 enabled；本人所有 + 同租户共享） */
    List<Skill> listVisible();

    /**
     * 按 id 取当前用户<b>可见</b>的 skill（详情页用）。
     *
     * @param id skill id
     * @return skill
     * @throws org.dinky.data.exception.BusException 不存在或不可见
     */
    Skill getVisibleById(Long id);

    /** 按 name 取当前用户<b>可见</b>的 skill（{@code @skill-<name>} 引用时用）；不可见 / 不存在返回 null */
    Skill findVisibleByName(String name);

    /** 读取 skill 的 {@code SKILL.md} 正文（可见性校验后按路径直读资源存储） */
    String readContent(Skill skill);

    /**
     * 新建 skill：建目录 {@code /skills/<name>/} → 写 {@code SKILL.md} 模板 → 落元数据。
     *
     * @param name skill 名（^[a-z0-9][a-z0-9-]{1,63}$，同时作为目录名）
     * @param description 一句话说明
     * @return 新建的 skill
     */
    Skill create(String name, String description);

    /**
     * 保存 {@code SKILL.md} 正文（<b>仅属主</b>）：写文件 → 重新解析 frontmatter → 回写
     * {@code description} / {@code version+1} / {@code content_hash}。
     */
    Skill save(Long id, String content);

    /** 删除 skill（<b>仅属主</b>）：删资源目录（含文件） + 删元数据 */
    void removeSkill(Long id);
}
