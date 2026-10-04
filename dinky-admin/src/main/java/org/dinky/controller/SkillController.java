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
import org.dinky.data.dto.SkillSaveDTO;
import org.dinky.data.enums.BusinessType;
import org.dinky.data.model.Skill;
import org.dinky.data.result.Result;
import org.dinky.data.vo.SkillDetailVO;
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
        return Result.succeed(skillService.create(skillCreateDTO.getName(), skillCreateDTO.getDescription()));
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
        skillService.removeSkill(id);
        return Result.succeed();
    }
}
