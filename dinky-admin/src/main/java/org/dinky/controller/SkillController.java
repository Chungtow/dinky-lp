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
 * 这里的权限注解只保证「有资源操作权限的人才能进入对应动作」。
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
    public Result<List<Skill>> list() {
        return Result.succeed(skillService.listVisible());
    }

    @GetMapping("/detail")
    @ApiOperation("Skill Detail (with content)")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
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
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_UPLOAD)
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
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_UPLOAD)
    public Result<Skill> save(@RequestBody SkillSaveDTO skillSaveDTO) {
        return Result.succeed(skillService.save(skillSaveDTO.getId(), skillSaveDTO.getContent()));
    }

    @DeleteMapping("/remove")
    @ApiOperation("Remove Skill")
    @Log(title = "Remove Skill", businessType = BusinessType.DELETE)
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_DELETE)
    public Result<Void> remove(@RequestParam Long id) {
        skillService.removeSkill(id, StpUtil.getLoginIdAsInt());
        return Result.succeed();
    }

    // ==================== 文件管理（阶段 4b：目录树 / references 子文件；人与 AI 共用同一组 Service） ====================

    @GetMapping("/files")
    @ApiOperation("List Files Inside a Skill")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
    public Result<List<SkillFileNode>> files(@RequestParam Long id) {
        return Result.succeed(skillService.listFiles(id));
    }

    @GetMapping("/file/read")
    @ApiOperation("Read a File Inside a Skill")
    @ApiImplicitParam(name = "id", value = "Skill ID", required = true, dataType = "Long", paramType = "query")
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
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_UPLOAD)
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
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_UPLOAD)
    public Result<SkillFileNode> mkdir(@RequestBody SkillFileRemoveDTO skillFileRemoveDTO) {
        return Result.succeed(skillService.mkdir(
                skillFileRemoveDTO.getSkillId(), skillFileRemoveDTO.getRelativePath(), StpUtil.getLoginIdAsInt()));
    }

    @PostMapping("/file/remove")
    @ApiOperation("Remove a File or Directory Inside a Skill")
    @Log(title = "Remove Skill File", businessType = BusinessType.DELETE)
    @SaCheckPermission(PermissionConstants.REGISTRATION_RESOURCE_DELETE)
    public Result<Void> removeFile(@RequestBody SkillFileRemoveDTO skillFileRemoveDTO) {
        skillService.removeFile(
                skillFileRemoveDTO.getSkillId(), skillFileRemoveDTO.getRelativePath(), StpUtil.getLoginIdAsInt());
        return Result.succeed();
    }
}
