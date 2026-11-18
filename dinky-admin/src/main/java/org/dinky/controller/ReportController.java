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
import org.dinky.data.enums.BusinessType;
import org.dinky.data.model.Report;
import org.dinky.data.model.Resources;
import org.dinky.data.result.Result;
import org.dinky.service.ReportService;
import org.dinky.service.resource.ResourcesService;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.util.StreamUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 自定义报表（HTML 静态页托管）
 *
 * <p>与 {@link DashboardController}（Flink 指标看板）互不影响：独立表、独立权限点、独立前端页面。
 *
 * @since 2026-11-18
 */
@Slf4j
@RestController
@Api(tags = "Custom Report API")
@SaCheckLogin
@RequiredArgsConstructor
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;
    private final ResourcesService resourcesService;

    @GetMapping("/list")
    @ApiOperation("Query visible report list")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT})
    public Result<List<Report>> list() {
        return Result.data(reportService.listVisibleReports());
    }

    @GetMapping("/detail")
    @ApiOperation("Query report detail")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT_VIEW})
    public Result<Report> detail(@RequestParam Integer id) {
        return Result.data(reportService.getVisibleDetail(id));
    }

    @PutMapping("/saveOrUpdate")
    @ApiOperation("Insert or update report")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT_MANAGE})
    @Log(title = "Insert Or Update Report", businessType = BusinessType.INSERT_OR_UPDATE)
    public Result<Void> saveOrUpdate(@RequestBody @Validated Report report) {
        return reportService.saveOrUpdateReport(report) ? Result.succeed() : Result.failed("保存报表失败");
    }

    @DeleteMapping("/delete")
    @ApiOperation("Delete report")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT_MANAGE})
    @Log(title = "Delete Report", businessType = BusinessType.DELETE)
    public Result<Void> delete(@RequestParam Integer id) {
        return reportService.deleteReport(id) ? Result.succeed() : Result.failed("删除报表失败");
    }

    /**
     * 生成带签名的预览地址：由已登录页面调用，iframe 只用返回的地址。
     *
     * <p>这样 iframe 无需依赖 Cookie（沙箱 iframe 的 Cookie 行为跨浏览器不一致），
     * 同时避免把完全免登录的端点暴露出去。
     */
    @GetMapping("/previewUrl")
    @ApiOperation("Build signed preview url")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT_VIEW})
    public Result<String> previewUrl(@RequestParam Integer id) {
        reportService.getVisibleDetail(id);
        return Result.data(reportService.buildPreviewUrl(id));
    }

    /**
     * 免登录预览：仅凭短期签名放行（供 iframe 使用）。
     *
     * <p>返回 text/html：不能复用 /download/downloadFromRs —— 那个端点无 Content-Type、无鉴权，
     * 是给 Flink 作业下发资源用的。
     */
    @SaIgnore
    @GetMapping("/preview")
    @ApiOperation("Preview report html (signed)")
    public void preview(
            @RequestParam Integer id,
            @RequestParam(required = false) Long exp,
            @RequestParam(required = false) String sig,
            HttpServletResponse response)
            throws Exception {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (!reportService.verifyPreviewSignature(id, exp, sig)) {
            writeHtmlError(response, HttpServletResponse.SC_FORBIDDEN, "预览链接已失效，请刷新页面重试");
            return;
        }
        Report report = reportService.getById(id);
        if (report == null) {
            writeHtmlError(response, HttpServletResponse.SC_NOT_FOUND, "报表不存在或已被删除");
            return;
        }
        Resources resource = reportService.checkResource(report);
        if (resource == null) {
            writeHtmlError(response, HttpServletResponse.SC_NOT_FOUND, "报表依赖的资源已被删除或改名，请管理员重新绑定");
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
        response.setHeader("Cache-Control", "no-store");
        try (InputStream inputStream = reportService.readHtml(report.getResourcePath())) {
            OutputStream outputStream = response.getOutputStream();
            StreamUtils.copy(inputStream, outputStream);
            outputStream.flush();
        }
    }

    /**
     * 供报表管理弹窗选择资源：只返回 HTML 文件（目录结构保留，便于定位）
     */
    @GetMapping("/htmlResources")
    @ApiOperation("Query html resources tree")
    @SaCheckPermission(value = {PermissionConstants.DASHBOARD_REPORT})
    public Result<List<Resources>> htmlResources() {
        List<Resources> tree = resourcesService.getResourcesTreeByFilter(res -> {
            if (Boolean.TRUE.equals(res.getIsDirectory())) {
                return Boolean.TRUE;
            }
            String fullName = res.getFullName() == null ? "" : res.getFullName().toLowerCase();
            return fullName.endsWith(".html") || fullName.endsWith(".htm");
        });
        return Result.data(tree == null ? new ArrayList<>() : tree);
    }

    private void writeHtmlError(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            response.getWriter()
                    .write("<!doctype html><html><head><meta charset=\"utf-8\"/><title>报表不可用</title></head>"
                            + "<body style=\"font:14px/1.6 -apple-system,Segoe UI,sans-serif;color:#666;"
                            + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                            + "<div>" + message + "</div></body></html>");
            response.getWriter().flush();
        } catch (Exception e) {
            log.warn("write report preview error page failed: {}", e.getMessage());
        }
    }
}
