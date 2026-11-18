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

import org.dinky.data.model.Report;
import org.dinky.data.model.Resources;

import java.io.InputStream;
import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 自定义报表服务（HTML 静态页托管）
 *
 * @since 2026-11-18
 */
public interface ReportService extends IService<Report> {

    /**
     * 当前用户可见的报表列表：超管/租户管理员看全部；其余人看"被授权角色的" + "未配置任何授权的"
     *
     * @return 报表列表（含 resourceName）
     */
    List<Report> listVisibleReports();

    /**
     * 报表详情（含 roleIds），并校验当前用户可见性
     *
     * @param id 报表ID
     * @return 报表
     */
    Report getVisibleDetail(Integer id);

    /**
     * 新增/修改报表（含授权角色全量替换）
     *
     * @param report 报表（roleIds 可选）
     * @return 是否成功
     */
    boolean saveOrUpdateReport(Report report);

    /**
     * 删除报表（含授权关系）
     *
     * @param id 报表ID
     * @return 是否成功
     */
    boolean deleteReport(Integer id);

    /**
     * 读取报表依赖的 HTML 资源流（读取前校验资源有效性）
     *
     * @param resourcePath 资源 full_name
     * @return 输入流
     */
    InputStream readHtml(String resourcePath);

    /**
     * 校验报表依赖的资源是否仍然有效
     *
     * @param report 报表
     * @return 有效返回资源对象，否则返回 null
     */
    Resources checkResource(Report report);

    /**
     * 生成带签名的预览地址（供 iframe 直接使用，不依赖 Cookie）
     *
     * @param id 报表ID
     * @return 相对地址 /api/report/preview?id=x&exp=y&sig=z
     */
    String buildPreviewUrl(Integer id);

    /**
     * 校验预览签名（免登录预览端点使用）
     *
     * @param id 报表ID
     * @param exp 过期时间戳（秒）
     * @param sig 签名
     * @return 是否合法
     */
    boolean verifyPreviewSignature(Integer id, Long exp, String sig);
}
