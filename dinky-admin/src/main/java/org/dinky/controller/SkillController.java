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

package org.dinky.controller;

import org.dinky.data.annotations.Log;
import org.dinky.data.constant.PermissionConstants;
import org.dinky.data.dto.SkillCreateDTO;
import org.dinky.data.dto.SkillFileRemoveDTO;
import org.dinky.data.dto.SkillFileWriteDTO;
import org.dinky.data.dto.SkillSaveDTO;
import org.dinky.data.enums.BusinessType;
import org.dinky.data.model.Skill;
import org.dinky.data.result.Result;
import org.dinky.data.vo.SkillDetailVO;
import org.dinky.data.vo.SkillFileNode;
import org.dinky.service.SkillService;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill Controller（阶段 4a：Skills 基础与引用）。
 *
 * <p>「谁能看 / 谁能改」在 {@link SkillService} 内以<b>显式条件</b>判定（属主 + 同租户共享）；
 * 这里的权限注解负责「功能权限」这一层，两者互补。
 *
 * <p>2026-10-14 菜单与权限登记缺口修复：
 *
 * <ul>
 *   <li>此前写接口复用 {@code registration:resource:upload/delete}，语义不符且导致"配了资源中心就等于
 *       配了 Skills 写权限"；现改用独立权限点 {@code registration:skill:*}；
 *   <li>过渡期内写接口以 {@link SaMode#OR} 同时接受新旧权限点，避免已按资源中心授权的角色"升级即失权"；
 *   <li>读接口 {@code /list} 需同时兼容 {@code aiChat:use}：AI Chat 的 {@code @skill/} 候选会调用该接口
 *       （{@code DataStudio/Toolbar/AiChat/service.tsx}），若只认 {@code registration:skill:view}，
 *       仅有 AI Chat 权限的用户将无法引用 skill（功能回归）。
 * </ul>
 *
 * @since 2026/10/05
 */
@Slf4j
@RestController
@Api(tags = "Skill Controller")
@RequestMapping("/api/skill")
@RequiredArgsConstructor
@SaCheckLogin
public class SkillController {

    private final SkillService skillService;

    @GetMapping("/list")
    @ApiOperation("List Visible Skills")
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_VIEW, PermissionConstants.AI_CHAT_USE},
            mode = SaMode.OR)
    public Result<List<Skill>> list() {
        return Result.succeed(skillService.listVisible());
    }

    @GetMapping("/detail")
    @ApiOperation("Skill Detail (with content)")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
    @SaCheckPermission(PermissionConstants.REGISTRATION_SKILL_VIEW)
    public Result<SkillDetailVO> detail(@RequestParam Long id) {
        Skill skill = skillService.getVisibleById(id);
        SkillDetailVO vo = new SkillDetailVO();
        vo.setSkill(skill);
        vo.setContent(skillService.readContent(skill));
        Integer currentUserId = StpUtil.getLoginIdAsInt();
        vo.setEditable(currentUserId.equals(skill.getOwnerId()));
        return Result.succeed(vo);
    }

    @PostMapping("/create")
    @ApiOperation("Create Skill")
    @Log(title = "Create Skill", businessType = BusinessType.INSERT)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_ADD, PermissionConstants.REGISTRATION_RESOURCE_UPLOAD},
            mode = SaMode.OR)
    public Result<Skill> create(@RequestBody SkillCreateDTO skillCreateDTO) {
        return Result.succeed(skillService.create(
                skillCreateDTO.getName(),
                skillCreateDTO.getDescription(),
                // 阶段 4b 补（UAT R2）：assetType 为空按 skill 处理，兼容旧前端
                skillCreateDTO.getAssetType(),
                StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/save")
    @ApiOperation("Save Skill Content")
    @Log(title = "Save Skill Content", businessType = BusinessType.UPDATE)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_SAVE, PermissionConstants.REGISTRATION_RESOURCE_UPLOAD},
            mode = SaMode.OR)
    public Result<Skill> save(@RequestBody SkillSaveDTO skillSaveDTO) {
        return Result.succeed(skillService.save(skillSaveDTO.getId(), skillSaveDTO.getContent()));
    }

    @DeleteMapping("/remove")
    @ApiOperation("Remove Skill")
    @Log(title = "Remove Skill", businessType = BusinessType.DELETE)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_DELETE, PermissionConstants.REGISTRATION_RESOURCE_DELETE},
            mode = SaMode.OR)
    public Result<Void> remove(@RequestParam Long id) {
        skillService.removeSkill(id, StpUtil.getLoginIdAsInt());
        return Result.succeed();
    }

    // ==================== 文件管理（阶段 4b：目录树 / references 子文件；人与 AI 共用同一组 Service） ====================

    @GetMapping("/files")
    @ApiOperation("List Files Inside a Skill")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
    @SaCheckPermission(PermissionConstants.REGISTRATION_SKILL_VIEW)
    public Result<List<SkillFileNode>> files(@RequestParam Long id) {
        return Result.succeed(skillService.listFiles(id));
    }

    @GetMapping("/file/read")
    @ApiOperation("Read a File Inside a Skill")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
    @SaCheckPermission(PermissionConstants.REGISTRATION_SKILL_VIEW)
    public Result<Object> readFile(@RequestParam Long id, @RequestParam(required = false) String relativePath) {
        // ⚠️ 不能写成 Result.succeed(skillService.readFile(...))：readFile 返回 String 时，
        // Java 重载决议会优先匹配 Result.succeed(String msg)，把正文塞进 msg 而 data 为 null
        // （前端只读 data，表现为"文件内容显示为空"，且服务端无任何异常日志）。
        // 先提升为 Object，强制走泛型重载 Result.succeed(T data)。
        Object content = skillService.readFile(id, relativePath);
        return Result.succeed(content);
    }

    @PostMapping("/file/write")
    @ApiOperation("Write a File Inside a Skill")
    @Log(title = "Write Skill File", businessType = BusinessType.UPDATE)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_FILE, PermissionConstants.REGISTRATION_RESOURCE_UPLOAD},
            mode = SaMode.OR)
    public Result<SkillFileNode> writeFile(@RequestBody SkillFileWriteDTO skillFileWriteDTO) {
        return Result.succeed(skillService.writeFile(
                skillFileWriteDTO.getSkillId(),
                skillFileWriteDTO.getRelativePath(),
                skillFileWriteDTO.getContent(),
                StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/file/mkdir")
    @ApiOperation("Create a Directory Inside a Skill")
    @Log(title = "Create Skill Directory", businessType = BusinessType.INSERT)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_FILE, PermissionConstants.REGISTRATION_RESOURCE_UPLOAD},
            mode = SaMode.OR)
    public Result<SkillFileNode> mkdir(@RequestBody SkillFileRemoveDTO skillFileRemoveDTO) {
        return Result.succeed(skillService.mkdir(
                skillFileRemoveDTO.getSkillId(), skillFileRemoveDTO.getRelativePath(), StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/file/remove")
    @ApiOperation("Remove a File or Directory Inside a Skill")
    @Log(title = "Remove Skill File", businessType = BusinessType.DELETE)
    @SaCheckPermission(
            value = {PermissionConstants.REGISTRATION_SKILL_DELETE, PermissionConstants.REGISTRATION_RESOURCE_DELETE},
            mode = SaMode.OR)
    public Result<Void> removeFile(@RequestBody SkillFileRemoveDTO skillFileRemoveDTO) {
        skillService.removeFile(
                skillFileRemoveDTO.getSkillId(), skillFileRemoveDTO.getRelativePath(), StpUtil.getLoginIdAsInt());
        return Result.succeed();
    }
}
