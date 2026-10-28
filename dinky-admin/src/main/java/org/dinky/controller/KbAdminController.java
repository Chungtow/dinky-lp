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
import org.dinky.data.dto.KbCollectionSaveDTO;
import org.dinky.data.dto.KbGrantSaveDTO;
import org.dinky.data.enums.BusinessType;
import org.dinky.data.model.KbCollection;
import org.dinky.data.model.KbCollectionGrant;
import org.dinky.data.result.Result;
import org.dinky.data.vo.KbOrphanVO;
import org.dinky.service.KbAdminService;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库管理接口（批次 D）：库管理、未纳管库发现、授权。
 *
 * <p><b>与 {@link KbController} 分开</b>：那个是<b>检索与推送</b>的运行期接口（AiChat 与左侧栏 tab 用）；
 * 这里是<b>管理</b>接口，权限点、校验强度、失败语义都不同。混在一个类里，迟早出现
 * "给 tab 加了 @SaCheckPermission(view)，结果管理接口也被放松成 view"。
 *
 * <p>权限点分工：
 * <ul>
 *   <li>{@code registration:kb:save} → 建库/改名/启停；</li>
 *   <li>{@code registration:kb:share} → 授权增删。</li>
 * </ul>
 * 二者都还要求"属主或超管"，权限点只是第一道门。
 *
 * @since 2026/10/28
 */
@Slf4j
@RestController
@RequestMapping("/api/kb/admin")
@Api(tags = "Knowledge Base Admin")
@RequiredArgsConstructor
public class KbAdminController {

    private final KbAdminService kbAdminService;

    /** 我管理的库（列表页） */
    @Log(title = "KB Manage List", businessType = BusinessType.QUERY)
    @GetMapping("/collections")
    @ApiOperation("List collections I can manage (owner, or all for super admin)")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SAVE)
    @SaCheckLogin
    public Result<List<KbCollection>> listManageable() {
        return Result.succeed(kbAdminService.listManageable());
    }

    /** 新建 / 编辑。{@code code} 由服务端管理，DTO 里没有这个字段 */
    @Log(title = "KB Collection Save", businessType = BusinessType.INSERT)
    @PostMapping("/collections")
    @ApiOperation("Create or update a collection (code is server side only)")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SAVE)
    @SaCheckLogin
    public Result<KbCollection> saveCollection(@RequestBody KbCollectionSaveDTO dto) {
        return Result.succeed(kbAdminService.saveCollection(dto));
    }

    /**
     * kb-service 有、Dinky 未纳管的库（超管）。
     *
     * <p>为什么单独一个路径而不是塞进列表：它要<b>实时打 RAG</b>，RAG 慢或挂时列表不该跟着转圈。
     */
    @Log(title = "KB Orphans", businessType = BusinessType.QUERY)
    @GetMapping("/orphans")
    @ApiOperation("List collections existing in kb-service but not registered here")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SAVE)
    @SaCheckLogin
    public Result<List<KbOrphanVO>> listOrphans() {
        return Result.succeed(kbAdminService.listOrphans());
    }

    /** 一键纳管：{@code code} 原样沿用 RAG 标识 */
    @Log(title = "KB Adopt", businessType = BusinessType.INSERT)
    @PostMapping("/collections/adopt")
    @ApiOperation("Adopt an existing kb-service collection (code kept as-is)")
    @ApiImplicitParam(name = "code", value = "Collection code in kb-service", required = true, dataType = "String")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SAVE)
    @SaCheckLogin
    public Result<KbCollection> adopt(
            @RequestParam String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String description,
            @RequestParam(required = false, defaultValue = "false") Boolean isPublic) {
        return Result.succeed(kbAdminService.adopt(code, name, description, isPublic));
    }

    /** 某库的授权清单 */
    @Log(title = "KB Grant List", businessType = BusinessType.QUERY)
    @GetMapping("/collections/{id}/grants")
    @ApiOperation("List grants of a collection")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SHARE)
    @SaCheckLogin
    public Result<List<KbCollectionGrant>> listGrants(@PathVariable Long id) {
        return Result.succeed(kbAdminService.listGrants(id));
    }

    /** 批量授权（按租户/角色/用户） */
    @Log(title = "KB Grant Add", businessType = BusinessType.INSERT)
    @PostMapping("/collections/{id}/grants")
    @ApiOperation("Add grants in batch")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SHARE)
    @SaCheckLogin
    public Result<Integer> addGrants(@PathVariable Long id, @RequestBody KbGrantSaveDTO dto) {
        return Result.succeed(kbAdminService.addGrants(id, dto));
    }

    /** 撤销一条授权 */
    @Log(title = "KB Grant Delete", businessType = BusinessType.DELETE)
    @DeleteMapping("/grants/{grantId}")
    @ApiOperation("Revoke a grant")
    @SaCheckPermission(PermissionConstants.REGISTRATION_KB_SHARE)
    @SaCheckLogin
    public Result<String> deleteGrant(@PathVariable Long grantId) {
        kbAdminService.deleteGrant(grantId);
        return Result.succeed("撤销成功");
    }
}
