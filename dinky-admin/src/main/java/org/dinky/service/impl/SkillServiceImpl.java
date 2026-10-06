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

import org.dinky.ai.skill.SkillDoc;
import org.dinky.ai.skill.SkillDocParser;
import org.dinky.ai.skill.SkillPathGuard;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.TreeNodeDTO;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Resources;
import org.dinky.data.model.Skill;
import org.dinky.data.vo.SkillFileNode;
import org.dinky.mapper.SkillMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.resource.BaseResourceManager;
import org.dinky.service.SkillService;
import org.dinky.service.resource.ResourcesService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 服务实现（阶段 4a）。
 *
 * <p><b>文件落点</b>：{@code {resourcesUploadBasePath}/skills/<name>/SKILL.md}（沿用资源存储的
 * LOCAL / HDFS / OSS 可插拔后端）；<b>语义与权限</b>在本表。
 *
 * <p><b>权限</b>：所有查询走<a>显式条件</a>（见 {@link #visibleWrapper()}），写入前校验属主；
 * 不依赖租户插件与 ThreadLocal 过滤（原因见接口注释）。
 *
 * @since 2026/10/05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl extends SuperServiceImpl<SkillMapper, Skill> implements SkillService {

    /** 可见性：同租户共享 */
    public static final String VISIBILITY_TENANT = "tenant";

    /** 可见性：私有（默认） */
    public static final String VISIBILITY_PRIVATE = "private";

    /** 来源：本地创建 */
    public static final String SOURCE_LOCAL = "local";

    /** 单个资产（skill / doc）的<b>总大小上限</b>（决策 D4b-3：防止把资产当网盘用） */
    public static final long MAX_TOTAL_SIZE = 2L * 1024 * 1024;

    private final ResourcesService resourcesService;

    @Override
    public List<Skill> listVisible() {
        return list(visibleWrapper().orderByDesc(Skill::getUpdateTime));
    }

    @Override
    public Skill findVisibleByName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        return getOne(visibleWrapper().eq(Skill::getName, StrUtil.trim(name)).last("limit 1"), false);
    }

    @Override
    public Skill getVisibleById(Long id) {
        Skill skill = getById(id);
        if (skill == null) {
            throw new BusException("skill 不存在");
        }
        Integer me = currentUserId();
        if (me.equals(skill.getOwnerId())) {
            return skill;
        }
        Integer tenantId = currentTenantId();
        if (Boolean.TRUE.equals(skill.getEnabled())
                && VISIBILITY_TENANT.equals(skill.getVisibility())
                && tenantId != null
                && tenantId.equals(skill.getTenantId())) {
            return skill;
        }
        throw new BusException("无权访问该 skill");
    }

    @Override
    public String readContent(Skill skill) {
        if (skill == null) {
            return null;
        }
        return readMainFile(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill create(String name, String description, Integer actorId) {
        return create(name, description, SkillDocParser.ASSET_TYPE_SKILL, actorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill create(String name, String description, String assetType, Integer actorId) {
        String type = SkillDocParser.ASSET_TYPE_DOC.equalsIgnoreCase(StrUtil.trimToEmpty(assetType))
                ? SkillDocParser.ASSET_TYPE_DOC
                : SkillDocParser.ASSET_TYPE_SKILL;
        boolean isDoc = SkillDocParser.ASSET_TYPE_DOC.equals(type);
        String label = isDoc ? "知识文档" : "skill";

        String skillName = StrUtil.trimToEmpty(name);
        if (!SkillDocParser.NAME_PATTERN.matcher(skillName).matches()) {
            throw new BusException(label + " 名不合法：" + skillName + "（要求 ^[a-z0-9][a-z0-9-]{1,63}$）");
        }
        String desc = StrUtil.trimToEmpty(description);
        if (StrUtil.isBlank(desc)) {
            throw new BusException("description 不能为空");
        }
        if (desc.length() > SkillDocParser.MAX_DESCRIPTION_LENGTH) {
            throw new BusException("description 过长（最多 " + SkillDocParser.MAX_DESCRIPTION_LENGTH + " 字符）");
        }
        Integer me = resolveActor(actorId);
        long exists = count(
                new LambdaQueryWrapper<Skill>().eq(Skill::getName, skillName).eq(Skill::getOwnerId, me));
        if (exists > 0) {
            throw new BusException("同名" + label + "已存在：" + skillName);
        }

        // ① 目录：/skills/<name>/ 或 /docs/<name>/（两类资产目录分离，故同名不冲突；幂等创建父目录）
        String rootDir = SkillDocParser.rootDirOf(type);
        String mainFileName = SkillDocParser.mainFileOf(type);
        TreeNodeDTO root = resourcesService.createFolderOrGet(-1, rootDir, isDoc ? "AI 知识文档根目录" : "AI Skills 根目录");
        TreeNodeDTO skillDir = resourcesService.createFolderOrGet(Convert.toInt(root.getId(), -1), skillName, desc);
        String dirFullName = StrUtil.nullToEmpty(Convert.toStr(skillDir.getPath()));

        // ② 主文件（SKILL.md / DOC.md）：先建资源记录，再写内容（writeContent 负责写文件 + 维护 size + 失效缓存）
        String content = isDoc
                ? SkillDocParser.buildDocTemplate(skillName, desc)
                : SkillDocParser.buildTemplate(skillName, desc);
        Resources mainFile = new Resources();
        mainFile.setPid(Convert.toInt(skillDir.getId(), -1));
        mainFile.setFileName(mainFileName);
        mainFile.setIsDirectory(false);
        mainFile.setType(0);
        mainFile.setFullName(mainFilePath(dirFullName, mainFileName));
        mainFile.setSize(0L);
        mainFile.setDescription(label + "主文件（frontmatter + 正文）");
        resourcesService.save(mainFile);
        resourcesService.writeContent(mainFile.getId(), content);

        // ③ 元数据
        Skill skill = new Skill();
        skill.setName(skillName);
        skill.setDescription(desc);
        skill.setDirFullName(dirFullName);
        skill.setOwnerId(me);
        skill.setTenantId(currentTenantId());
        skill.setVisibility(VISIBILITY_PRIVATE);
        skill.setEnabled(true);
        skill.setSource(SOURCE_LOCAL);
        skill.setAssetType(type);
        skill.setVersion(1);
        skill.setContentHash(SecureUtil.sha256(content));
        save(skill);
        log.info("Asset created: name={}, dir={}, owner={}, assetType={}", skillName, dirFullName, me, type);
        return skill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill save(Long id, String content) {
        Skill skill = getById(id);
        if (skill == null) {
            throw new BusException("skill 不存在");
        }
        checkOwner(skill, null);
        String text = StrUtil.nullToEmpty(content);
        if (StrUtil.utf8Bytes(text).length > SkillDocParser.MAX_DOC_SIZE) {
            throw new BusException("SKILL.md 过大（最多 " + SkillDocParser.MAX_DOC_SIZE + " 字节）");
        }
        // 先校验再落盘：非法 frontmatter 不允许写入（避免产生「无效 skill」）
        SkillDoc doc = SkillDocParser.parse(text);
        SkillDocParser.validate(doc, skill.getName());

        Resources mainFile = findMainFileResource(skill);
        if (mainFile == null) {
            throw new BusException("SKILL.md 资源记录不存在：" + mainFilePath(skill.getDirFullName()));
        }
        resourcesService.writeContent(mainFile.getId(), text);

        skill.setDescription(doc.getDescription());
        skill.setVersion((skill.getVersion() == null ? 0 : skill.getVersion()) + 1);
        skill.setContentHash(SecureUtil.sha256(text));
        updateById(skill);
        log.info("Skill saved: name={}, version={}", skill.getName(), skill.getVersion());
        return skill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSkill(Long id, Integer actorId) {
        Skill skill = getById(id);
        if (skill == null) {
            return;
        }
        checkOwner(skill, actorId);
        Resources dir = resourcesService.getOne(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getFullName, skill.getDirFullName())
                .last("limit 1"));
        if (dir != null) {
            // 资源侧会递归删除子项（含主文件与 references/）——但默认只删数据库记录
            resourcesService.remove(dir.getId());
        }
        // 阶段 4b 修复：删除整个资产时同步清掉物理目录，否则 HDFS 上会残留（与单个文件删除同因）
        removePhysical(skill, skill.getDirFullName());
        removeById(id);
        log.info("Skill removed: name={}, dir={}", skill.getName(), skill.getDirFullName());
    }

    // ==================== 文件管理（阶段 4b：人与 AI 共用同一组能力） ====================

    @Override
    public List<SkillFileNode> listFiles(Long id) {
        Skill skill = getVisibleById(id);
        String base = SkillPathGuard.normalizeDir(skill.getDirFullName()) + "/";
        String mainFile = mainFileOf(skill);
        List<Resources> resources =
                resourcesService.list(new LambdaQueryWrapper<Resources>().likeRight(Resources::getFullName, base));
        List<SkillFileNode> flat = new ArrayList<>();
        for (Resources resource : resources) {
            String full = StrUtil.nullToEmpty(resource.getFullName());
            if (!full.startsWith(base) || full.length() <= base.length()) {
                continue;
            }
            String relative = full.substring(base.length());
            SkillFileNode node = new SkillFileNode();
            node.setName(StrUtil.nullToEmpty(resource.getFileName()));
            node.setRelativePath(relative);
            node.setDirectory(Boolean.TRUE.equals(resource.getIsDirectory()));
            node.setMainFile(relative.equals(mainFile));
            node.setSize(resource.getSize());
            node.setUpdateTime(resource.getUpdateTime());
            flat.add(node);
        }
        return buildFileTree(flat);
    }

    @Override
    public String readFile(Long id, String relativePath) {
        Skill skill = getVisibleById(id);
        String relative = StrUtil.trimToEmpty(relativePath);
        String fullName = StrUtil.isBlank(relative)
                ? mainFilePath(skill)
                : SkillPathGuard.resolve(skill.getDirFullName(), relative);
        // 目录不是文件：明确拒绝，避免前端误选目录时抛出晦涩的「Path is not a file」
        Resources resource = findResource(fullName);
        if (resource != null && Boolean.TRUE.equals(resource.getIsDirectory())) {
            throw new BusException("该路径是目录，不能作为文件读取：" + relative);
        }
        try {
            return BaseResourceManager.getInstance().getFileContent(fullName);
        } catch (Exception e) {
            log.warn("Read skill file failed: path={}, msg={}", fullName, e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillFileNode writeFile(Long id, String relativePath, String content, Integer actorId) {
        Skill skill = requireOwnedSkill(id, actorId);
        String mainFile = mainFileOf(skill);
        String relative = StrUtil.trimToEmpty(relativePath);
        String text = StrUtil.nullToEmpty(content);

        // ① 先校验后落盘：任一项不通过都不产生半成品
        SkillPathGuard.checkExtension(relative, mainFile);
        SkillPathGuard.checkSize(text);
        String fullName = SkillPathGuard.resolve(skill.getDirFullName(), relative);
        boolean main = relative.equals(mainFile);
        if (main) {
            SkillDoc doc = SkillDocParser.parse(text);
            SkillDocParser.validate(doc, skill.getName());
        }
        checkTotalSize(skill, fullName, text);

        // ② 惰性建父目录 → 复用 / 新建资源记录 → 写内容
        Integer pid = ensureDirChain(skill, SkillPathGuard.parentOf(relative));
        Resources resource = findResource(fullName);
        if (resource == null) {
            resource = new Resources();
            resource.setPid(pid);
            resource.setFileName(SkillPathGuard.nameOf(relative));
            resource.setIsDirectory(false);
            resource.setType(0);
            resource.setFullName(fullName);
            resource.setSize(0L);
            resource.setDescription(skill.getName() + " 文件");
            resourcesService.save(resource);
        }
        resourcesService.writeContent(resource.getId(), text);

        // ③ 主文件：回写元数据（与 save() 口径一致）；附件不递增版本
        if (main) {
            SkillDoc doc = SkillDocParser.parse(text);
            if (doc != null && StrUtil.isNotBlank(doc.getDescription())) {
                skill.setDescription(doc.getDescription());
            }
            skill.setVersion((skill.getVersion() == null ? 0 : skill.getVersion()) + 1);
            skill.setContentHash(SecureUtil.sha256(text));
            updateById(skill);
            log.info("Skill main file written: name={}, version={}", skill.getName(), skill.getVersion());
        } else {
            log.info("Skill file written: name={}, relative={}", skill.getName(), relative);
        }
        return toFileNode(resource, relative, main);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillFileNode mkdir(Long id, String relativePath, Integer actorId) {
        Skill skill = requireOwnedSkill(id, actorId);
        String relative = StrUtil.trimToEmpty(relativePath);
        String fullName = SkillPathGuard.resolve(skill.getDirFullName(), relative);
        Resources existing = findResource(fullName);
        if (existing != null) {
            if (Boolean.TRUE.equals(existing.getIsDirectory())) {
                return toFileNode(existing, relative, false); // 幂等
            }
            throw new BusException("同名文件已存在：" + relative);
        }
        Integer pid = ensureDirChain(skill, SkillPathGuard.parentOf(relative));
        resourcesService.createFolder(pid, SkillPathGuard.nameOf(relative), skill.getName() + " 子目录");
        Resources created = findResource(fullName);
        if (created == null) {
            SkillFileNode node = new SkillFileNode();
            node.setName(SkillPathGuard.nameOf(relative));
            node.setRelativePath(relative);
            node.setDirectory(true);
            return node;
        }
        return toFileNode(created, relative, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFile(Long id, String relativePath, Integer actorId) {
        Skill skill = requireOwnedSkill(id, actorId);
        String relative = StrUtil.trimToEmpty(relativePath);
        if (relative.equals(mainFileOf(skill))) {
            throw new BusException("主文件不允许单独删除，请直接删除该 " + assetTypeOf(skill));
        }
        String fullName = SkillPathGuard.resolve(skill.getDirFullName(), relative);
        Resources resource = findResource(fullName);
        if (resource == null) {
            throw new BusException("文件或目录不存在：" + relative);
        }
        resourcesService.remove(resource.getId()); // 删数据库记录（目录会递归删除子项）
        // 阶段 4b 修复：Dinky 的 remove 在 physicalDeletion=false（默认）时只删记录、保留物理文件；
        // 但「删除 skill 文件」在语义上必须真删，否则 HDFS 上会永久残留。
        removePhysical(skill, fullName);
        log.info("Skill file removed: name={}, relative={}", skill.getName(), relative);
    }

    /**
     * 删除资产的<b>物理文件</b>（阶段 4b 修复：解决「UI 删了、HDFS 上还在」）。
     *
     * <p><b>为什么单独做</b>：{@code ResourcesService#remove} 受配置
     * {@code sys.resource.settings.base.physicalDeletion} 控制，默认为 {@code false}——那是资源页的
     * 既有设计（只删记录）。但 skill / doc 的文件删除是<b>显式的人工动作</b>（UI 二次确认、AI 侧还要
     * 输入名称），语义上应该真删。
     *
     * <p><b>安全</b>：只允许删除<b>本资产根目录之下</b>的路径（含根目录自身）。调用方已用
     * {@link SkillPathGuard} 校验过相对路径，这里再做一次断言——删除是不可逆操作，宁可多一道。
     *
     * <p>物理删除失败<b>不回滚</b>数据库删除（记录已移除），只记 error 便于运维清理——避免为了一个
     * 残留文件把用户的删除操作整体回退。
     */
    private void removePhysical(Skill skill, String fullName) {
        String base = SkillPathGuard.normalizeDir(skill.getDirFullName());
        String target = SkillPathGuard.normalizeDir(fullName);
        if (!target.equals(base) && !StrUtil.startWith(target, base + "/")) {
            log.warn("Refuse to delete physical path outside asset dir: base={}, path={}", base, target);
            return;
        }
        try {
            BaseResourceManager.getInstance().remove(target);
        } catch (Exception e) {
            log.error("Remove physical skill file failed: path={}, msg={}", target, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 可见性条件（<b>显式拼接，不依赖插件 / ThreadLocal 过滤</b>）：
     * {@code enabled = 1 AND asset_type = 'skill' AND (owner_id = :me OR (visibility = 'tenant' AND tenant_id = :myTenant))}
     */
    private LambdaQueryWrapper<Skill> visibleWrapper() {
        Integer me = currentUserId();
        Integer tenantId = currentTenantId();
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        // 阶段 4b：可见资产同时包含 skill（流程知识）与 doc（业务背景知识）——同表、不同 asset_type。
        // 语义层（Apache Ossie）是**独立上层**、不落此表（总体计划 §4.9 A1），故这里只放这两个类型。
        wrapper.eq(Skill::getEnabled, true)
                .in(Skill::getAssetType, Arrays.asList(SkillDocParser.ASSET_TYPE_SKILL, SkillDocParser.ASSET_TYPE_DOC));
        wrapper.and(w -> {
            w.eq(Skill::getOwnerId, me);
            if (tenantId != null) {
                w.or(x -> x.eq(Skill::getVisibility, VISIBILITY_TENANT).eq(Skill::getTenantId, tenantId));
            }
        });
        return wrapper;
    }

    private void checkOwner(Skill skill, Integer actorId) {
        if (!resolveActor(actorId).equals(skill.getOwnerId())) {
            throw new BusException("只有创建者可以修改或删除该 skill");
        }
    }

    /**
     * 解析「操作者」：显式传入优先，否则取当前登录用户。
     *
     * <p><b>为什么需要显式传入</b>：AI 工具跑在异步线程池，Sa-Token 的上下文（基于 ThreadLocal /
     * RequestContextHolder）已丢失，{@code StpUtil.getLoginIdAsInt()} 会直接抛异常。因此工具侧必须把
     * 请求线程解析好的 {@code AiToolContext#getUserId()} 显式传进来。
     */
    private Integer resolveActor(Integer actorId) {
        return actorId != null ? actorId : currentUserId();
    }

    private Resources findMainFileResource(Skill skill) {
        return resourcesService.getOne(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getFullName, mainFilePath(skill))
                .last("limit 1"));
    }

    /** 按路径直读文件内容（不经资源表；失败返回 null 并告警） */
    private String readMainFile(Skill skill) {
        String path = mainFilePath(skill);
        try {
            return BaseResourceManager.getInstance().getFileContent(path);
        } catch (Exception e) {
            log.warn("Read skill main file failed: path={}, msg={}", path, e.getMessage());
            return null;
        }
    }

    /** 主文件完整路径（<b>按资产类型</b>取主文件名：{@code SKILL.md} / {@code DOC.md}） */
    private String mainFilePath(Skill skill) {
        return mainFilePath(skill.getDirFullName(), SkillDocParser.mainFileOf(assetTypeOf(skill)));
    }

    private String mainFilePath(String dirFullName) {
        return mainFilePath(dirFullName, SkillDocParser.SKILL_MAIN_FILE);
    }

    private String mainFilePath(String dirFullName, String mainFileName) {
        String dir = StrUtil.nullToEmpty(dirFullName);
        if (!dir.startsWith("/")) {
            dir = "/" + dir;
        }
        return StrUtil.removeSuffix(dir, "/") + "/" + mainFileName;
    }

    // ---------- 文件管理辅助（阶段 4b） ----------

    /** 取「<b>仅属主可写</b>」的资产（不存在 / 非属主均抛错） */
    private Skill requireOwnedSkill(Long id, Integer actorId) {
        Skill skill = getById(id);
        if (skill == null) {
            throw new BusException("skill / doc 不存在");
        }
        checkOwner(skill, actorId);
        return skill;
    }

    /** 资产类型（缺省按 skill，兼容 4a 期间写入的老数据） */
    private String assetTypeOf(Skill skill) {
        return StrUtil.blankToDefault(skill.getAssetType(), SkillDocParser.ASSET_TYPE_SKILL);
    }

    private String mainFileOf(Skill skill) {
        return SkillDocParser.mainFileOf(assetTypeOf(skill));
    }

    private Resources findResource(String fullName) {
        return resourcesService.getOne(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getFullName, fullName)
                .last("limit 1"));
    }

    /**
     * 逐段确保相对目录存在（自资产根目录起），返回最深一层目录的资源 id。
     *
     * @param relativeDirPath 相对目录路径；为空则直接返回资产根目录 id
     */
    private Integer ensureDirChain(Skill skill, String relativeDirPath) {
        String base = SkillPathGuard.normalizeDir(skill.getDirFullName());
        Resources root = findResource(base);
        if (root == null) {
            throw new BusException("资产目录资源记录不存在：" + base);
        }
        Integer pid = root.getId();
        String rel = StrUtil.trimToEmpty(relativeDirPath);
        if (StrUtil.isBlank(rel)) {
            return pid;
        }
        String acc = base;
        for (String segment : rel.split("/")) {
            acc = acc + "/" + segment;
            Resources found = findResource(acc);
            if (found == null) {
                resourcesService.createFolder(pid, segment, skill.getName() + " 子目录");
                Resources created = findResource(acc);
                if (created == null) {
                    throw new BusException("创建子目录失败：" + acc);
                }
                pid = created.getId();
            } else {
                pid = found.getId();
            }
        }
        return pid;
    }

    /** 单资产总量校验（防止把 skill / doc 当网盘用；覆盖写时先扣除旧大小） */
    private void checkTotalSize(Skill skill, String targetFullName, String content) {
        String base = SkillPathGuard.normalizeDir(skill.getDirFullName()) + "/";
        List<Resources> resources =
                resourcesService.list(new LambdaQueryWrapper<Resources>().likeRight(Resources::getFullName, base));
        long total = 0L;
        for (Resources resource : resources) {
            if (!Boolean.TRUE.equals(resource.getIsDirectory())) {
                total += (resource.getSize() == null ? 0L : resource.getSize());
            }
        }
        Resources old = findResource(targetFullName);
        if (old != null && !Boolean.TRUE.equals(old.getIsDirectory()) && old.getSize() != null) {
            total -= old.getSize();
        }
        total += StrUtil.utf8Bytes(content).length;
        if (total > MAX_TOTAL_SIZE) {
            throw new BusException("该资产总大小超出上限（" + MAX_TOTAL_SIZE + " 字节），请精简 references/ 内容");
        }
    }

    private SkillFileNode toFileNode(Resources resource, String relativePath, boolean mainFile) {
        SkillFileNode node = new SkillFileNode();
        node.setName(StrUtil.nullToEmpty(resource.getFileName()));
        node.setRelativePath(relativePath);
        node.setDirectory(Boolean.TRUE.equals(resource.getIsDirectory()));
        node.setMainFile(mainFile);
        node.setSize(resource.getSize());
        node.setUpdateTime(resource.getUpdateTime());
        return node;
    }

    /** 扁平列表 → 树（目录优先，再按名称不区分大小写排序） */
    private List<SkillFileNode> buildFileTree(List<SkillFileNode> flat) {
        Map<String, SkillFileNode> byPath = new LinkedHashMap<>();
        for (SkillFileNode node : flat) {
            byPath.put(node.getRelativePath(), node);
        }
        List<SkillFileNode> roots = new ArrayList<>();
        for (SkillFileNode node : flat) {
            String parent = SkillPathGuard.parentOf(node.getRelativePath());
            SkillFileNode parentNode = StrUtil.isBlank(parent) ? null : byPath.get(parent);
            if (parentNode == null) {
                roots.add(node);
            } else {
                parentNode.getChildren().add(node);
            }
        }
        sortFileNodes(roots);
        return roots;
    }

    private void sortFileNodes(List<SkillFileNode> nodes) {
        nodes.sort(Comparator.comparingInt((SkillFileNode n) -> n.isDirectory() ? 0 : 1)
                .thenComparing(SkillFileNode::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        for (SkillFileNode node : nodes) {
            sortFileNodes(node.getChildren());
        }
    }

    private Integer currentUserId() {
        return StpUtil.getLoginIdAsInt();
    }

    /** 请求线程内取租户（为 null 表示未带 tenantId cookie / 非请求线程）；仅用于<b>写入</b>，不用于过滤 */
    private Integer currentTenantId() {
        try {
            return Convert.toInt(TenantContextHolder.get(), null);
        } catch (Exception e) {
            return null;
        }
    }
}
