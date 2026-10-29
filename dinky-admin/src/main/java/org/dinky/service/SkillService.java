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

    /**
     * 按 name 取当前用户<b>可见</b>的 skill（{@code @skill/<name>} 引用时用）；不可见 / 不存在返回 null。
     *
     * <p>2026-10-14：存储路径按属主隔离后，同名资产可跨属主共存，本方法按
     * 「自己的 &gt; 全体公开 &gt; 同租户共享 &gt; 最新」优先级取一条。
     */
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
     * 新建资产并指定<b>可见性</b>（2026-10-14 三档可见性）。
     *
     * @param visibility {@code private}（默认）/ {@code tenant}（同租户共享）/ {@code public}（全体公开）；
     *     空值按 {@code private}，非法值抛 {@code BusException}
     */
    Skill create(String name, String description, String assetType, Integer actorId, String visibility);

    /**
     * 修改资产<b>可见性</b>（<b>仅属主</b>；2026-10-14 三档可见性）。
     *
     * <p>改为 {@code tenant} 前会确保 {@code tenant_id} 有值（否则同租户成员看不到）；
     * {@code public} 不限租户。
     */
    Skill updateVisibility(Long id, String visibility, Integer actorId);

    /**
     * 保存 {@code SKILL.md} 正文（<b>仅属主</b>）：写文件 → 重新解析 frontmatter → 回写
     * {@code description} / {@code version+1} / {@code content_hash}。
     */
    Skill save(Long id, String content);

    /**
     * 保存 {@code SKILL.md} 正文（批次 F：带乐观锁）。
     *
     * <p>批次 F 让 skill 也能在数据开发左侧栏的「技能」tab 里编辑后，同一份 skill 存在两条编辑入口
     * （注册中心弹窗 + 数据开发编辑器），甚至可能两个浏览器标签页同时开着。<b>不校验版本就是静默
     * 后写覆盖前写</b>——skill 没有 git 历史、也没有语义层那样的 {@code .trash}，覆盖后无处找回。
     *
     * @param baseVersion 编辑起点的 {@code version}；{@code null} = 不校验（AI 工具与旧前端沿用）
     * @throws org.dinky.data.exception.BusException 版本已前进（存在他人修改）
     */
    Skill save(Long id, String content, Integer baseVersion);

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

    /**
     * 写相对路径文件（批次 F：主文件带乐观锁）。
     *
     * <p>与 {@link #writeFile(Long, String, String, Integer)} 的唯一差别是校验 {@code baseVersion}，
     * 且<b>只对主文件生效</b>：附件（{@code references/} 下）不递增 {@code version}，拿它做冲突判定
     * 只会误报。附件并发覆盖目前靠 {@code SkillPathGuard} 的路径与大小校验兜底，不在此处拦。
     */
    SkillFileNode writeFile(Long id, String relativePath, String content, Integer actorId, Integer baseVersion);

    /** 新建子目录（<b>仅属主</b>；幂等——已存在则直接返回） */
    SkillFileNode mkdir(Long id, String relativePath, Integer actorId);

    /** 删除文件或子目录（<b>仅属主</b>；目录会被<b>递归删除</b>） */
    void removeFile(Long id, String relativePath, Integer actorId);

    /**
     * 重命名 skill 内的<b>文件</b>（批次 F 补：与语义树右键菜单对齐；<b>仅属主</b>）。
     *
     * <p><b>只改文件名，不换目录</b>：{@code newName} 必须是纯文件名（不允许含 {@code /}），
     * 目标目录沿用原文件的父目录——跨目录移动会牵动父目录的递归建/删，一期不做。
     *
     * <p><b>主文件不允许重命名</b>：{@code SKILL.md} / {@code DOC.md} 是资产本体（元数据、版本号、
     * frontmatter 校验都挂在它上面），改名会让资产变成"没有主文件的孤儿"。
     *
     * <p><b>原子性</b>：先写新文件、再删旧文件；删除失败则把新文件删掉并抛出，避免留下两份。
     * 不是真正的文件系统 rename（资源存储无该原语），但失败路径可控且不产生脏数据。
     */
    SkillFileNode renameFile(Long id, String relativePath, String newName, Integer actorId);
}
