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
import org.dinky.data.dto.SemanticEntryOpDTO;
import org.dinky.data.dto.SemanticFeedbackDTO;
import org.dinky.data.dto.SemanticFeedbackResolveDTO;
import org.dinky.data.dto.SemanticFileSaveDTO;
import org.dinky.data.dto.SemanticRenameDTO;
import org.dinky.data.enums.BusinessType;
import org.dinky.data.model.SemanticFeedback;
import org.dinky.data.result.Result;
import org.dinky.data.vo.SemanticFeedbackSummaryVO;
import org.dinky.data.vo.SemanticFileContentVO;
import org.dinky.data.vo.SemanticFileNode;
import org.dinky.data.vo.SemanticProjectVO;
import org.dinky.data.vo.SemanticPublishedAssetVO;
import org.dinky.data.vo.SemanticPublishedProjectVO;
import org.dinky.service.SemanticFeedbackService;
import org.dinky.service.SemanticFileService;

import java.util.List;

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
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * dbt 语义项目在线编辑（P2）。
 *
 * <p><b>边界</b>：只提供「看项目 / 看文件树 / 读文件 / 存文件」四个接口。建项目、渲染骨架、
 * 校验元数据、发布与版本回滚仍归 JupyterLab 侧（{@code /semantic/_published}），本批不重复实现，
 * 也不新增数据库表。
 *
 * <p><b>双写者语义</b>：本接口直接写 HDFS 编辑区（{@code /semantic/<project>/}），写入后
 * HDFS mtime 变新，JupyterLab 侧 {@code push_semantic.py} 会据此拦截覆盖（避免把 Dinky 的改动
 * 用本地旧内容盖掉）。反向操作请用户自行执行「从 HDFS 拉取」。
 *
 * @since 2026/10/21
 */
@Slf4j
@RestController
@Api(tags = "Semantic Controller")
@RequestMapping("/api/semantic")
@RequiredArgsConstructor
@SaCheckLogin
public class SemanticController {

    private final SemanticFileService semanticFileService;

    /** P4 一期：语义资产反馈（口径错了有地方说、有人处理） */
    private final SemanticFeedbackService semanticFeedbackService;

    @GetMapping("/projects")
    @ApiOperation("List Semantic Projects (HDFS)")
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<List<SemanticProjectVO>> listProjects() {
        return Result.succeed(semanticFileService.listProjects());
    }

    /**
     * P3b：{@code @semantic/} 一级候选（只列**已发布**项目）。
     *
     * <p>与 {@link #listProjects()} 的区别是读 `_published`（不可变快照）而不是编辑区：
     * AI 只能引用已发布版本，否则「今天引用的口径明天变了」无法复现。
     */
    @GetMapping("/published/projects")
    @ApiOperation("List Published Semantic Projects (candidates of @semantic/)")
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<List<SemanticPublishedProjectVO>> listPublishedProjects() {
        return Result.succeed(semanticFileService.listPublishedProjects());
    }

    /** P3b：{@code @semantic/<project>/} 二级候选（来自索引 {@code names[]}，索引未就绪时降级） */
    @GetMapping("/published/assets")
    @ApiOperation("List Referable Assets of a Published Semantic Project")
    @ApiImplicitParam(
            name = "project",
            value = "Project Name",
            required = true,
            dataType = "String",
            paramType = "query")
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<List<SemanticPublishedAssetVO>> listPublishedAssets(@RequestParam String project) {
        return Result.succeed(semanticFileService.listPublishedAssets(project));
    }

    @GetMapping("/files")
    @ApiOperation("List Files of a Semantic Project")
    @ApiImplicitParam(
            name = "project",
            value = "Project Name",
            required = true,
            dataType = "String",
            paramType = "query")
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<List<SemanticFileNode>> listFiles(@RequestParam String project) {
        return Result.succeed(semanticFileService.listFiles(project));
    }

    @GetMapping("/file")
    @ApiOperation("Read a Semantic File")
    @ApiImplicitParams({
        @ApiImplicitParam(
                name = "project",
                value = "Project Name",
                required = true,
                dataType = "String",
                paramType = "query"),
        @ApiImplicitParam(
                name = "path",
                value = "Relative Path",
                required = true,
                dataType = "String",
                paramType = "query")
    })
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<SemanticFileContentVO> readFile(@RequestParam String project, @RequestParam String path) {
        return Result.succeed(semanticFileService.readFile(project, path));
    }

    @PostMapping("/file")
    @ApiOperation("Save a Semantic File (optimistic lock on HDFS mtime)")
    @Log(title = "Save Semantic File", businessType = BusinessType.UPDATE)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_EDIT)
    public Result<Long> saveFile(@RequestBody SemanticFileSaveDTO dto) {
        return Result.succeed(semanticFileService.saveFile(dto));
    }

    @PostMapping("/dir")
    @ApiOperation("Create a Directory in a Semantic Project")
    @Log(title = "Create Semantic Dir", businessType = BusinessType.INSERT)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_EDIT)
    public Result<String> createDir(@RequestBody SemanticEntryOpDTO dto) {
        return Result.succeed(semanticFileService.createDir(dto));
    }

    @PostMapping("/rename")
    @ApiOperation("Rename an Entry of a Semantic Project (target must not exist)")
    @Log(title = "Rename Semantic Entry", businessType = BusinessType.UPDATE)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_EDIT)
    public Result<Boolean> rename(@RequestBody SemanticRenameDTO dto) {
        semanticFileService.rename(dto);
        return Result.succeed(true);
    }

    @PostMapping("/delete")
    @ApiOperation("Move an Entry of a Semantic Project to Trash (soft delete, restorable)")
    @Log(title = "Delete Semantic Entry", businessType = BusinessType.DELETE)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_EDIT)
    public Result<String> delete(@RequestBody SemanticEntryOpDTO dto) {
        return Result.succeed(semanticFileService.deleteEntry(dto));
    }

    // ------------------------------------------------------------------ P4 一期：语义资产反馈

    /**
     * 提交一条语义资产反馈（「这条口径不对」的出口）。
     *
     * <p>权限用 {@code semantic:view} 而不是新增的处理权限——<b>能看的人都能提</b>，
     * 否则反馈收不上来，闭环没有输入。版本与 YAML 指纹由后端解析，见
     * {@code SemanticFeedbackServiceImpl#submit}。
     */
    @PostMapping("/feedback")
    @ApiOperation("Submit Feedback on a Semantic Asset")
    @Log(title = "Submit Semantic Feedback", businessType = BusinessType.INSERT)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<Long> submitFeedback(@RequestBody SemanticFeedbackDTO dto) {
        return Result.succeed(semanticFeedbackService.submit(dto));
    }

    /**
     * 反馈列表（待办视图）。
     *
     * <p>三个过滤条件都可空：{@code status=OPEN} 即「待处理」。列表上限由服务层兜底
     * （默认 100 / 最大 500），前端无需翻页。
     */
    @GetMapping("/feedback/list")
    @ApiOperation("List Semantic Feedbacks (newest first)")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "project", value = "Project Name", dataType = "String", paramType = "query"),
        @ApiImplicitParam(name = "asset", value = "Asset Name", dataType = "String", paramType = "query"),
        @ApiImplicitParam(
                name = "status",
                value = "OPEN | RESOLVED | IGNORED",
                dataType = "String",
                paramType = "query"),
        @ApiImplicitParam(name = "limit", value = "Max Rows", dataType = "Integer", paramType = "query")
    })
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<List<SemanticFeedback>> listFeedback(
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return Result.succeed(semanticFeedbackService.listFeedback(project, asset, status, limit));
    }

    /**
     * 处理反馈：{@code OPEN → RESOLVED / IGNORED}。
     *
     * <p>需要新增的 {@code datastudio:semantic:feedback}（无权限者 403，前端亦隐藏处理按钮）。
     */
    @PutMapping("/feedback/{id}/resolve")
    @ApiOperation("Resolve or Ignore a Semantic Feedback")
    @Log(title = "Resolve Semantic Feedback", businessType = BusinessType.UPDATE)
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_FEEDBACK)
    public Result<Boolean> resolveFeedback(@PathVariable Long id, @RequestBody SemanticFeedbackResolveDTO dto) {
        semanticFeedbackService.resolve(id, dto);
        return Result.succeed(true);
    }

    /** 反馈计数（待办角标 + 运营视图）：可按项目过滤 */
    @GetMapping("/feedback/summary")
    @ApiOperation("Semantic Feedback Counters")
    @ApiImplicitParam(name = "project", value = "Project Name", dataType = "String", paramType = "query")
    @SaCheckPermission(PermissionConstants.DATASTUDIO_SEMANTIC_VIEW)
    public Result<SemanticFeedbackSummaryVO> feedbackSummary(@RequestParam(required = false) String project) {
        return Result.succeed(semanticFeedbackService.summary(project));
    }
}
