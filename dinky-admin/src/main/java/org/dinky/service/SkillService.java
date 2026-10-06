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
import org.dinky.data.vo.SkillFileNode;
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
     * @param actorId <b>显式操作者</b>：请求线程传 {@code StpUtil.getLoginIdAsInt()}；AI 工具在<b>异步线程</b>
     *     调用时传 {@code AiToolContext#getUserId()}——那里 Sa-Token 上下文已丢失，不能依赖 {@code StpUtil}
     * @return 新建的 skill
     */
    /**
     * 新建资产（默认 {@code asset_type='skill'}）。
     */
    Skill create(String name, String description, Integer actorId);

    /**
     * 新建资产（阶段 4b 补：支持 {@code asset_type='doc'}，即业务背景知识文档）。
     *
     * <p>两类资产共用 {@code dinky_skill} 表与同一套可见性 / 权限模型，仅目录（{@code skills/} 与
     * {@code docs/}）和主文件名（{@code SKILL.md} 与 {@code DOC.md}）不同——映射见
     * {@code SkillDocParser#renderDirName} 与 {@code #renderMainFileName}。
     *
     * @param assetType {@code skill} 或 {@code doc}；空值按 {@code skill} 处理（兼容旧调用方）
     */
    Skill create(String name, String description, String assetType, Integer actorId);

    /**
     * 保存 {@code SKILL.md} 正文（<b>仅属主</b>）：写文件 → 重新解析 frontmatter → 回写
     * {@code description} / {@code version+1} / {@code content_hash}。
     */
    Skill save(Long id, String content);

    /** 删除 skill（<b>仅属主</b>）：删资源目录（含文件） + 删元数据 */
    void removeSkill(Long id, Integer actorId);

    // ==================== 文件管理（阶段 4b：人与 AI 共用同一组能力） ====================
    //
    // 设计约束（见 4b 实施计划 §3.1）：
    //   1. 「谁能看 / 谁能写」的判定**只在本层**（可见性 / 属主），Controller 与 AiTool 都不重复实现；
    //   2. 路径安全统一走 SkillPathGuard，**不接受**调用方传入的绝对路径；
    //   3. 返回值只含**相对路径**，不泄漏资源存储布局。

    /**
     * 列出该资产目录下的<b>文件树</b>（读权限：<b>可见即可</b>）。
     *
     * @param id 资产 id（skill 或 doc）
     * @return 目录树（含目录与文件；不含资产根节点自身）
     */
    List<SkillFileNode> listFiles(Long id);

    /**
     * 读取资产内某个文件的<b>文本内容</b>（读权限：<b>可见即可</b>）。
     *
     * @param id 资产 id
     * @param relativePath 相对路径；<b>为空表示读主文件</b>（{@code SKILL.md} / {@code DOC.md}）
     * @return 文件内容；路径非法或读取失败返回 {@code null}
     */
    String readFile(Long id, String relativePath);

    /**
     * 写<b>相对路径文件</b>（写权限：<b>仅属主</b>）。
     *
     * <p>路径经 {@link org.dinky.ai.skill.SkillPathGuard} 三层校验；父目录不存在时<b>惰性创建</b>。
     * 若写入的是<b>主文件</b>（{@code SKILL.md} / {@code DOC.md}），会重新解析 frontmatter 并递增
     * {@code version}、刷新 {@code content_hash}（与 {@link #save} 行为一致）；写 {@code references/}
     * 下的附件<b>不</b>递增版本（避免噪声）。
     *
     * @return 写入后的节点信息
     */
    SkillFileNode writeFile(Long id, String relativePath, String content, Integer actorId);

    /** 新建子目录（<b>仅属主</b>；幂等——已存在则直接返回） */
    SkillFileNode mkdir(Long id, String relativePath, Integer actorId);

    /** 删除文件或子目录（<b>仅属主</b>；目录会被<b>递归删除</b>） */
    void removeFile(Long id, String relativePath, Integer actorId);
}
