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

import org.dinky.ai.skill.MarkdownSkillRenderer;
import org.dinky.ai.skill.SkillDoc;
import org.dinky.ai.skill.SkillDocParser;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.TreeNodeDTO;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Resources;
import org.dinky.data.model.Skill;
import org.dinky.mapper.SkillMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.resource.BaseResourceManager;
import org.dinky.service.SkillService;
import org.dinky.service.resource.ResourcesService;

import java.util.List;

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
        return readMainFile(skill.getDirFullName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill create(String name, String description) {
        String skillName = StrUtil.trimToEmpty(name);
        if (!SkillDocParser.NAME_PATTERN.matcher(skillName).matches()) {
            throw new BusException("skill 名不合法：" + skillName + "（要求 ^[a-z0-9][a-z0-9-]{1,63}$）");
        }
        String desc = StrUtil.trimToEmpty(description);
        if (StrUtil.isBlank(desc)) {
            throw new BusException("description 不能为空");
        }
        if (desc.length() > SkillDocParser.MAX_DESCRIPTION_LENGTH) {
            throw new BusException("description 过长（最多 " + SkillDocParser.MAX_DESCRIPTION_LENGTH + " 字符）");
        }
        Integer me = currentUserId();
        long exists = count(
                new LambdaQueryWrapper<Skill>().eq(Skill::getName, skillName).eq(Skill::getOwnerId, me));
        if (exists > 0) {
            throw new BusException("同名 skill 已存在：" + skillName);
        }

        // ① 目录：/skills/<name>/（createFolderOrGet 幂等，父目录不存在会一并创建）
        TreeNodeDTO skillsDir = resourcesService.createFolderOrGet(-1, SkillDocParser.SKILLS_DIR, "AI Skills 根目录");
        TreeNodeDTO skillDir =
                resourcesService.createFolderOrGet(Convert.toInt(skillsDir.getId(), -1), skillName, desc);
        String dirFullName = StrUtil.nullToEmpty(Convert.toStr(skillDir.getPath()));

        // ② SKILL.md：先建资源记录，再写内容（writeContent 负责写文件 + 维护 size + 失效缓存）
        String content = SkillDocParser.buildTemplate(skillName, desc);
        Resources mainFile = new Resources();
        mainFile.setPid(Convert.toInt(skillDir.getId(), -1));
        mainFile.setFileName(SkillDocParser.SKILL_MAIN_FILE);
        mainFile.setIsDirectory(false);
        mainFile.setType(0);
        mainFile.setFullName(mainFilePath(dirFullName));
        mainFile.setSize(0L);
        mainFile.setDescription("skill 主文件（frontmatter + 正文）");
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
        skill.setAssetType(MarkdownSkillRenderer.ASSET_TYPE_SKILL);
        skill.setVersion(1);
        skill.setContentHash(SecureUtil.sha256(content));
        save(skill);
        log.info("Skill created: name={}, dir={}, owner={}", skillName, dirFullName, me);
        return skill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Skill save(Long id, String content) {
        Skill skill = getById(id);
        if (skill == null) {
            throw new BusException("skill 不存在");
        }
        checkOwner(skill);
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
    public void removeSkill(Long id) {
        Skill skill = getById(id);
        if (skill == null) {
            return;
        }
        checkOwner(skill);
        Resources dir = resourcesService.getOne(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getFullName, skill.getDirFullName())
                .last("limit 1"));
        if (dir != null) {
            // 资源侧会递归删除子项（含 SKILL.md 与 references/）
            resourcesService.remove(dir.getId());
        }
        removeById(id);
        log.info("Skill removed: name={}, dir={}", skill.getName(), skill.getDirFullName());
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
        wrapper.eq(Skill::getEnabled, true).eq(Skill::getAssetType, MarkdownSkillRenderer.ASSET_TYPE_SKILL);
        wrapper.and(w -> {
            w.eq(Skill::getOwnerId, me);
            if (tenantId != null) {
                w.or(x -> x.eq(Skill::getVisibility, VISIBILITY_TENANT).eq(Skill::getTenantId, tenantId));
            }
        });
        return wrapper;
    }

    private void checkOwner(Skill skill) {
        if (!currentUserId().equals(skill.getOwnerId())) {
            throw new BusException("只有创建者可以修改或删除该 skill");
        }
    }

    private Resources findMainFileResource(Skill skill) {
        return resourcesService.getOne(new LambdaQueryWrapper<Resources>()
                .eq(Resources::getFullName, mainFilePath(skill.getDirFullName()))
                .last("limit 1"));
    }

    /** 按路径直读文件内容（不经资源表；失败返回 null 并告警） */
    private String readMainFile(String dirFullName) {
        String path = mainFilePath(dirFullName);
        try {
            return BaseResourceManager.getInstance().getFileContent(path);
        } catch (Exception e) {
            log.warn("Read skill main file failed: path={}, msg={}", path, e.getMessage());
            return null;
        }
    }

    private String mainFilePath(String dirFullName) {
        String dir = StrUtil.nullToEmpty(dirFullName);
        if (!dir.startsWith("/")) {
            dir = "/" + dir;
        }
        return StrUtil.removeSuffix(dir, "/") + "/" + SkillDocParser.SKILL_MAIN_FILE;
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
