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
import org.dinky.data.enums.BusinessType;
import org.dinky.data.enums.Status;
import org.dinky.data.model.Configuration;
import org.dinky.data.model.DataBase;
import org.dinky.data.result.Result;
import org.dinky.service.DataBaseService;
import org.dinky.service.SysConfigService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.map.MapUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SysConfigController
 *
 * @since 2021/11/18
 */
@Slf4j
@RestController
@Api(tags = "System Config Controller")
@SaCheckLogin
@RequestMapping("/api/sysConfig")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    /** 校验「默认 sink 数据源」配置值需要按 id 查数据源 */
    private final DataBaseService dataBaseService;

    /**
     * modify system config
     *
     * @param params {@link Dict}
     * @return {@link Result}<{@link Void}>
     */
    @PostMapping("/modifyConfig")
    @ApiOperation("Modify System Config")
    @Log(title = "Modify System Config", businessType = BusinessType.UPDATE)
    @ApiImplicitParam(name = "params", value = "System Config", dataType = "Dict")
    public Result<Void> modifyConfig(@RequestBody Dict params) {
        String key = params.getStr("key");
        String value = params.getStr("value");
        String invalid = validateAiDefaultSink(key, value);
        if (invalid != null) {
            return Result.failed(invalid);
        }
        sysConfigService.updateSysConfigByKv(key, value);
        return Result.succeed(Status.MODIFY_SUCCESS);
    }

    /** 「AI 生成作业的默认 sink 数据源」配置键（**主通道**：按名称，2026-11-03 起） */
    private static final String AI_DEFAULT_SINK_NAME_KEY = "sys.flink.settings.aiDefaultSinkDataBaseName";

    /** 「AI 生成作业的默认 sink 数据源」旧键（**兼容通道**：按 id，租户内唯一 → 跨租户必然失效） */
    private static final String AI_DEFAULT_SINK_ID_KEY = "sys.flink.settings.aiDefaultSinkDataBaseId";

    /**
     * 保存前校验「默认 sink 数据源」。
     *
     * <p><b>为什么要保存时拦</b>：这两项都是<b>全局配置</b>（`dinky_sys_config` 没有 tenant_id，
     * 所有租户共用一份），而 {@code dinky_database} 的名字/id 只在租户内唯一、查询按租户过滤。
     * 填错的后果只是"AI 上下文里默认 sink 静默失效"，没人会想到去翻这个键——
     * 所以宁可保存时就明确拒绝，并把处置方式写进错误信息。
     *
     * <p>两条通道的校验口径：
     * <ul>
     *   <li><b>名称</b>（主通道）：非空时必须是**当前租户**内存在且已启用的数据源；
     *       别的租户没注册同名数据源不是"配置错"，而是该租户注册后即生效 —— 这点必须在校验信息里说清，
     *       否则管理员会以为配置有问题。
     *   <li><b>id</b>（兼容通道）：非空时必须是当前租户可见且启用中的 id（其它租户的 id 一律拒绝）。
     * </ul>
     */
    private String validateAiDefaultSink(String key, String value) {
        if (AI_DEFAULT_SINK_NAME_KEY.equals(key)) {
            return validateAiDefaultSinkName(value);
        }
        if (AI_DEFAULT_SINK_ID_KEY.equals(key)) {
            return validateAiDefaultSinkId(value);
        }
        return null;
    }

    /** 主通道：按名称。空 = 清空配置（此时回落到 id 通道） */
    private String validateAiDefaultSinkName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > 30) {
            // dinky_database.name 是 varchar(30)，超长只会在注册数据源时炸，这里提前给出可读提示
            return "数据源名称最长 30 个字符（与「注册中心 → 数据源」的名称一致，注意不要带空格）";
        }
        DataBase dataBase = null;
        try {
            dataBase = dataBaseService.getEnabledByName(name);
        } catch (Exception e) {
            log.warn("validate ai default sink name failed, name={}", name, e);
        }
        if (dataBase == null) {
            return "当前租户下没有名为「" + name + "」的**已启用**数据源。该配置是全局配置、所有租户共用："
                    + "每个租户各自注册一个同名数据源后即生效（可用各自凭证）；"
                    + "若只是本租户暂时不想给默认 sink，请把此项留空。";
        }
        return null;
    }

    /** 兼容通道：按 id（id 只在租户内唯一，别填其它租户的） */
    private String validateAiDefaultSinkId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || "0".equals(trimmed)) {
            return null;
        }
        Integer id;
        try {
            id = Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return "该配置项只接受数据源 ID（0 表示未配置）。推荐改用 " + "`sys.flink.settings.aiDefaultSinkDataBaseName`（按名称，对每个租户都能生效）";
        }
        DataBase dataBase = null;
        try {
            dataBase = dataBaseService.getById(id);
        } catch (Exception e) {
            log.warn("validate ai default sink failed, id={}", id, e);
        }
        if (dataBase == null) {
            return "数据源 id=" + id + " 在当前租户下不存在（可能已删除，或该 id 属于其它租户）。"
                    + "推荐改用按名称的 `aiDefaultSinkDataBaseName`，它是全局配置的主通道";
        }
        if (!Boolean.TRUE.equals(dataBase.getEnabled())) {
            return "数据源 id=" + id + "（" + dataBase.getName() + "）已停用，请先启用再设为默认 sink";
        }
        return null;
    }

    /**
     * query all system config
     *
     * @return {@link Result}<{@link Map}>
     */
    @GetMapping("/getAll")
    @ApiOperation("Query All System Config List")
    @SaIgnore
    public Result<Map<String, List<Configuration<?>>>> getAll() {
        Map<String, List<Configuration<?>>> all = sysConfigService.getAll();
        Map<String, List<Configuration<?>>> map =
                MapUtil.map(all, (k, v) -> v.stream().map(Configuration::show).collect(Collectors.toList()));
        return Result.succeed(map);
    }

    @GetMapping("/getConfigByType")
    @ApiOperation("Query One Type System Config List By Key")
    @ApiImplicitParam(
            name = "type",
            value = "System Config Type",
            dataType = "String",
            required = true,
            example = "sys")
    public Result<List<Configuration<?>>> getOneTypeByKey(@RequestParam("type") String type) {
        Map<String, List<Configuration<?>>> all = sysConfigService.getAll();
        // Filter out configurations starting with type and return a list
        List<Configuration<?>> configList = all.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(type))
                .map(Map.Entry::getValue)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        return Result.succeed(configList);
    }

    @GetMapping("/getNeededCfg")
    @ApiOperation("Get Needed Config")
    @SaIgnore
    public Result<Map<String, Object>> getNeededCfg() {
        return sysConfigService.getNeededCfg();
    }

    @PostMapping("/setInitConfig")
    @ApiOperation("Get Needed Config")
    @SaIgnore
    public Result<Void> setInitConfig(@RequestBody Map<String, Object> params) {
        return sysConfigService.setInitConfig(params);
    }
}
