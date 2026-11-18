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

import org.dinky.context.TenantContextHolder;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Report;
import org.dinky.data.model.ReportRole;
import org.dinky.data.model.Resources;
import org.dinky.data.model.rbac.UserRole;
import org.dinky.mapper.ReportMapper;
import org.dinky.mapper.ReportRoleMapper;
import org.dinky.resource.BaseResourceManager;
import org.dinky.service.ReportService;
import org.dinky.service.UserRoleService;
import org.dinky.service.UserService;
import org.dinky.service.resource.ResourcesService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 自定义报表服务实现。
 *
 * <p>要点：
 *
 * <ul>
 *   <li>租户：写库时**显式赋值**（历史踩过"落 DB 默认值 1"的坑），上下文缺失直接报错
 *   <li>可见性：超管 / 租户管理员看全部；其余人看"命中授权角色"与"未配置授权（公共）"的报表
 *   <li>预览：以短期 HMAC 签名换取免登录的 iframe 地址，规避 sandbox 下 Cookie 不确定行为
 * </ul>
 *
 * @since 2026-11-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements ReportService {

    /** 报表文件大小上限（字节）：20MB */
    private static final long MAX_REPORT_SIZE = 20L * 1024 * 1024;

    /** 预览签名有效期（秒） */
    private static final long PREVIEW_TTL_SECONDS = 300L;

    private final ReportRoleMapper reportRoleMapper;
    private final ResourcesService resourcesService;
    private final UserRoleService userRoleService;
    private final UserService userService;

    @Value("${sa-token.jwt-secret-key:}")
    private String jwtSecretKey;

    @Override
    public List<Report> listVisibleReports() {
        // 租户条件显式拼：dinky_report 未进 MybatisPlusConfig 的租户表白名单，插件不会自动注入
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        Integer tenantId = currentTenantId();
        if (tenantId != null) {
            wrapper.eq(Report::getTenantId, tenantId);
        }
        List<Report> reports = this.list(wrapper.orderByAsc(Report::getSortNum).orderByDesc(Report::getId));
        if (reports.isEmpty()) {
            return reports;
        }
        Integer userId = currentUserId();
        if (!isPrivileged(userId)) {
            Set<Integer> roleIds = currentRoleIds(userId);
            Map<Integer, Set<Integer>> authorized = loadAuthorizedRoles(reports);
            reports.removeIf(report -> {
                Set<Integer> reportRoles = authorized.get(report.getId());
                if (reportRoles == null || reportRoles.isEmpty()) {
                    return false; // 未配置授权 = 公共
                }
                return reportRoles.stream().noneMatch(roleIds::contains);
            });
        }
        fillResourceName(reports);
        return reports;
    }

    @Override
    public Report getVisibleDetail(Integer id) {
        Report report = getById(id);
        if (report == null) {
            throw new BusException("报表不存在或已被删除");
        }
        // 跨租户防护：dinky_report 未进租户插件白名单，这里显式比对（不存在的报错口径与 tenant 无关，避免探测）
        Integer tenantId = currentTenantId();
        if (tenantId != null && report.getTenantId() != null && !tenantId.equals(report.getTenantId())) {
            throw new BusException("报表不存在或已被删除");
        }
        Integer userId = currentUserId();
        if (!isPrivileged(userId)) {
            Set<Integer> reportRoles =
                    loadAuthorizedRoles(Collections.singletonList(report)).getOrDefault(id, Collections.emptySet());
            if (!reportRoles.isEmpty()) {
                Set<Integer> roleIds = currentRoleIds(userId);
                if (reportRoles.stream().noneMatch(roleIds::contains)) {
                    throw new BusException("无权访问该报表");
                }
            }
        }
        report.setRoleIds(
                reportRoleMapper
                        .selectList(new LambdaQueryWrapper<ReportRole>().eq(ReportRole::getReportId, id))
                        .stream()
                        .map(ReportRole::getRoleId)
                        .collect(Collectors.toList()));
        fillResourceName(Collections.singletonList(report));
        return report;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveOrUpdateReport(Report report) {
        String name = report.getName() == null ? "" : report.getName().trim();
        if (name.isEmpty()) {
            throw new BusException("报表名称不能为空");
        }
        // 名称唯一性按「同租户」判定：跨租户重名是允许的（与 DB 唯一键 uk_report_tenant_name 一致）
        LambdaQueryWrapper<Report> sameNameWrapper = new LambdaQueryWrapper<Report>()
                .eq(Report::getName, name)
                .ne(report.getId() != null, Report::getId, report.getId());
        Integer currentTenant = currentTenantId();
        if (currentTenant != null) {
            sameNameWrapper.eq(Report::getTenantId, currentTenant);
        }
        if (this.count(sameNameWrapper) > 0) {
            throw new BusException("报表名已存在，请换一个名称");
        }

        Resources resource = validateReportResource(report.getResourceId());
        report.setName(name);
        report.setResourcePath(resource.getFullName());
        report.setResourceName(null);
        if (report.getSortNum() == null) {
            report.setSortNum(0);
        }
        if (report.getEnabled() == null) {
            report.setEnabled(true);
        }
        // 租户显式赋值：绝不依赖 DB 列默认值（1）
        if (report.getTenantId() == null) {
            Object tenantId = TenantContextHolder.get();
            if (!(tenantId instanceof Integer)) {
                throw new BusException("无法确定当前租户上下文，请刷新页面后重试");
            }
            report.setTenantId((Integer) tenantId);
        }

        List<Integer> roleIds = report.getRoleIds();
        report.setRoleIds(null);
        boolean saved = this.saveOrUpdate(report);
        if (!saved) {
            return false;
        }
        if (roleIds != null) {
            replaceAuthorizedRoles(report.getId(), roleIds);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteReport(Integer id) {
        reportRoleMapper.delete(new LambdaQueryWrapper<ReportRole>().eq(ReportRole::getReportId, id));
        return this.removeById(id);
    }

    @Override
    public InputStream readHtml(String resourcePath) {
        return BaseResourceManager.getInstance().readFile(resourcePath);
    }

    @Override
    public Resources checkResource(Report report) {
        if (report.getResourceId() == null) {
            return null;
        }
        Resources resource = resourcesService.getById(report.getResourceId());
        if (resource == null || Boolean.TRUE.equals(resource.getIsDirectory())) {
            return null;
        }
        if (report.getResourcePath() != null && !report.getResourcePath().equals(resource.getFullName())) {
            log.warn(
                    "report resource changed: report={}, expect={}, actual={}",
                    report.getId(),
                    report.getResourcePath(),
                    resource.getFullName());
            return null;
        }
        return resource;
    }

    @Override
    public String buildPreviewUrl(Integer id) {
        long exp = System.currentTimeMillis() / 1000 + PREVIEW_TTL_SECONDS;
        return "/api/report/preview?id=" + id + "&exp=" + exp + "&sig=" + sign(id + ":" + exp);
    }

    /**
     * 校验预览签名（供控制器的免登录预览端点使用）
     *
     * @param id 报表ID
     * @param exp 过期时间戳（秒）
     * @param sig 签名
     * @return 是否合法
     */
    @Override
    public boolean verifyPreviewSignature(Integer id, Long exp, String sig) {
        if (id == null || exp == null || sig == null || sig.isEmpty()) {
            return false;
        }
        if (exp < System.currentTimeMillis() / 1000) {
            return false;
        }
        return sign(id + ":" + exp).equals(sig);
    }

    // ------------------------------------------------------------------ private

    private String sign(String raw) {
        String key = (jwtSecretKey == null || jwtSecretKey.isEmpty()) ? "dinky-report-preview" : jwtSecretKey;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusException("预览签名计算失败：" + e.getMessage());
        }
    }

    private boolean isPrivileged(Integer userId) {
        if (userId == null) {
            return false;
        }
        if (Boolean.TRUE.equals(userService.checkSuperAdmin(userId))) {
            return true;
        }
        return Boolean.TRUE.equals(userService.checkTenantAdmin(userId));
    }

    private Integer currentUserId() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsInt() : null;
    }

    /** 当前租户上下文（缺失返回 null，调用方需自行决定降级口径） */
    private Integer currentTenantId() {
        Object tenantId = TenantContextHolder.get();
        return tenantId instanceof Integer ? (Integer) tenantId : null;
    }

    private Set<Integer> currentRoleIds(Integer userId) {
        if (userId == null) {
            return new HashSet<>();
        }
        List<UserRole> userRoles = userRoleService.getUserRoleByUserId(userId);
        if (userRoles == null) {
            return new HashSet<>();
        }
        return userRoles.stream()
                .map(UserRole::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Map<Integer, Set<Integer>> loadAuthorizedRoles(List<Report> reports) {
        List<Integer> ids = reports.stream().map(Report::getId).collect(Collectors.toList());
        Map<Integer, Set<Integer>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        List<ReportRole> rows =
                reportRoleMapper.selectList(new LambdaQueryWrapper<ReportRole>().in(ReportRole::getReportId, ids));
        for (ReportRole row : rows) {
            result.computeIfAbsent(row.getReportId(), k -> new HashSet<>()).add(row.getRoleId());
        }
        return result;
    }

    private void replaceAuthorizedRoles(Integer reportId, List<Integer> roleIds) {
        reportRoleMapper.delete(new LambdaQueryWrapper<ReportRole>().eq(ReportRole::getReportId, reportId));
        List<Integer> distinct =
                roleIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        for (Integer roleId : distinct) {
            ReportRole row = new ReportRole();
            row.setReportId(reportId);
            row.setRoleId(roleId);
            reportRoleMapper.insert(row);
        }
    }

    private Resources validateReportResource(Integer resourceId) {
        if (resourceId == null) {
            throw new BusException("请选择要展示的 HTML 资源");
        }
        Resources resource = resourcesService.getById(resourceId);
        if (resource == null) {
            throw new BusException("所选资源不存在，请刷新资源列表后重试");
        }
        if (Boolean.TRUE.equals(resource.getIsDirectory())) {
            throw new BusException("请选择一个 HTML 文件，而不是目录");
        }
        String fullName =
                resource.getFullName() == null ? "" : resource.getFullName().toLowerCase();
        if (!fullName.endsWith(".html") && !fullName.endsWith(".htm")) {
            throw new BusException("仅支持 .html / .htm 资源");
        }
        Long size = resource.getSize();
        if (size != null && size > MAX_REPORT_SIZE) {
            throw new BusException(String.format("报表文件不能超过 20MB（当前 %.1f MB）", size / 1024.0 / 1024.0));
        }
        return resource;
    }

    private void fillResourceName(List<Report> reports) {
        List<Integer> resourceIds = reports.stream()
                .map(Report::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (resourceIds.isEmpty()) {
            return;
        }
        Map<Integer, Resources> resources =
                resourcesService.listByIds(resourceIds).stream().collect(Collectors.toMap(Resources::getId, r -> r));
        for (Report report : reports) {
            Resources resource = resources.get(report.getResourceId());
            if (resource != null) {
                report.setResourceName(resource.getFileName());
            }
        }
    }
}
