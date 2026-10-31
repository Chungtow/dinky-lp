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

package org.dinky.ai.skill;

import org.dinky.ai.mention.SourceRefResolver;
import org.dinky.ai.semantic.SemanticAssetProvider;
import org.dinky.ai.semantic.SemanticAssetRef;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.vo.SkillHandleValidationVO;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.DataBaseService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 正文 {@code @} 句柄的<b>确定性装配器</b>（S1 装配，2026-10-31）。
 *
 * <p><b>解决什么问题</b>：装配前 skill 正文里的 {@code @} 只是纯文本——模型看到
 * {@code @semantic/dw_sem/daily_gmv} 既不知道要不要查、也不知道查什么，于是要么反问、
 * 要么自己猜一个工具去试。本类在<b>渲染 skill 正文之后</b>追加一段「本 skill 声明的数据上下文」，
 * 把句柄指向的资源按需读出来拼进上下文（纲领 §7.4 的装配形态）。
 *
 * <p><b>三条硬约束</b>（纲领 §7.5，违反其一就会从"能力"变成"事故"）：
 *
 * <ol>
 *   <li><b>深度 1</b>：只展开 skill 直接声明的句柄，装配出来的资源正文里若还有 {@code @}，
 *       <b>不再展开</b>——否则 {@code skill A → skill B → skill A} 直接成环，且预算必然爆炸；
 *   <li><b>独立预算</b>：本块走 {@code sys.llm.settings.skillAssetMaxChars}，与正文的
 *       {@code llmSkillMaxChars} 互不挤占（"正文写满"不应导致装配为空，反之亦然）；
 *   <li><b>显式失败</b>：句柄解析不到（不存在 / 无权限 / 读取异常）时，<b>必须</b>在上下文里
 *       留下一行 {@code [未解析]}——静默 {@code log.info} 会让模型以为"这块没有依据"变成
 *       "这块不存在"，然后开始编（这正是 {@code lp.49} 之前 {@code appendAssetDetail} 的老毛病）。
 * </ol>
 *
 * <p><b>为什么复用 {@link SkillHandleValidator}</b>：句柄的解析（双语法 explicit / bare）、
 * 类型白名单、存在性与<b>可见性</b>判定在编辑器保存校验里已经实现过一次。装配若另写一套，
 * 两条链路对"什么算可见"的口径就会漂——最危险的漂移是"校验说不可见、装配却读了"。
 * 因此装配<b>只做渲染</b>，判定全部交给校验器（唯一真相）。
 *
 * <p><b>只认 explicit</b>：反引号形式（`` `@type/code` ``）才是"显式引用"，裸写（{@code @type/code}）
 * 可能只是举例，故 bare 一律不装配（实施计划 H3，与 {@code lp.49} K5 同口径）。
 *
 * <p><b>只读</b>：本类不产生任何写操作（纲领 B1），也不改写用户正文——装配块是<b>追加</b>的。
 *
 * @since 2026/10/31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAssetAssembler {

    /** 装配块标题（模型与人都能一眼看出"以下是自动装配，不是用户写的"） */
    private static final String BLOCK_HEADER = "--- 本 skill 声明的数据上下文（自动装配，深度 1） ---";

    /** 单条语义资产最多渲染多少字符：多条句柄要能共存于同一预算内 */
    private static final int SINGLE_ASSET_CHARS = 800;

    /** 表结构最多列多少列：宽表（40+ 列）按预算裁掉，保留前 N 列 */
    private static final int MAX_COLUMNS = 20;

    /**
     * 单次装配最多读多少张表的<b>真实结构</b>（每次都要连库，属于慢操作）。
     *
     * <p>超出的表只记"已声明"——宁可少给结构，也不能让一次 {@code @skill/} 阻塞首帧十几秒。
     */
    private static final int MAX_STRUCTURE_TABLES = 5;

    private final SkillHandleValidator handleValidator;

    private final SemanticAssetProvider semanticAssetProvider;

    private final DataBaseService dataBaseService;

    /**
     * 装配一段 SKILL.md 正文里的显式句柄。
     *
     * @param content SKILL.md 全文（未渲染的原始文本）
     * @return 要追加到上下文的装配块；没有可用句柄 / 内容为空 / 装配全失败时返回空串
     */
    public String assemble(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        SkillHandleValidationVO validation;
        try {
            validation = handleValidator.validate(content);
        } catch (BusException e) {
            // 正文过大等可修正问题：不装配，但不阻断对话（正文本身仍会注入）
            log.info("Skill asset assembly skipped: {}", e.getMessage());
            return "";
        } catch (RuntimeException e) {
            log.warn("Skill asset assembly failed to parse handles: {}", e.getMessage());
            return "";
        }
        if (validation == null || CollUtil.isEmpty(validation.getHandles())) {
            return "";
        }
        List<SkillHandleValidationVO.Handle> handles = explicitUnique(validation.getHandles());
        if (handles.isEmpty()) {
            return "";
        }
        int budget = SystemConfiguration.getInstances().getSkillAssetMaxChars();
        StringBuilder body = new StringBuilder();
        int used = 0;
        int ok = 0;
        int unresolved = 0;
        int omitted = 0;
        int structures = 0;
        for (SkillHandleValidationVO.Handle handle : handles) {
            String line = render(handle, structures);
            if (StrUtil.isEmpty(line)) {
                continue;
            }
            if (used + line.length() > budget) {
                omitted++;
                continue;
            }
            if (SkillHandleValidator.STATUS_OK.equals(handle.getStatus())) {
                ok++;
                if (isStructureRead(handle)) {
                    structures++;
                }
            } else {
                unresolved++;
            }
            body.append(line);
            used += line.length();
        }
        if (omitted > 0) {
            body.append("…（另有 ")
                    .append(omitted)
                    .append(" 条句柄因超出装配预算 ")
                    .append(budget)
                    .append(" 字符未装配）\n");
        }
        if (body.length() == 0) {
            return "";
        }
        log.info(
                "Skill asset assembled: handles={}, ok={}, unresolved={}, omitted={}, chars={}/{}",
                handles.size(),
                ok,
                unresolved,
                omitted,
                used,
                budget);
        return BLOCK_HEADER + "\n" + body + "\n";
    }

    /**
     * 只保留 explicit 句柄，并按 {@code type|code} 去重。
     *
     * <p>去重是必需的：同一份 skill 里同一个句柄常出现多次（正文讲解 + 示例），
     * 每条都读一次语义资产会把预算和 IO 都翻倍。
     */
    private List<SkillHandleValidationVO.Handle> explicitUnique(List<SkillHandleValidationVO.Handle> handles) {
        List<SkillHandleValidationVO.Handle> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SkillHandleValidationVO.Handle handle : handles) {
            if (handle == null || !SkillHandleValidator.SYNTAX_EXPLICIT.equals(handle.getSyntax())) {
                continue;
            }
            String key = StrUtil.nullToEmpty(handle.getType()) + "|" + StrUtil.nullToEmpty(handle.getCode());
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(handle);
        }
        return result;
    }

    /** 按类型渲染一条句柄；返回 {@code null} 表示"这一类不参与装配"（历史类型）。 */
    private String render(SkillHandleValidationVO.Handle handle, int structuresAlreadyRead) {
        String type = StrUtil.nullToEmpty(handle.getType()).toLowerCase(Locale.ROOT);
        String code = StrUtil.nullToEmpty(handle.getCode());
        if (!SkillHandleValidator.STATUS_OK.equals(handle.getStatus())) {
            return "[未解析] @" + type + "/" + code + "  " + statusReason(handle) + "\n";
        }
        switch (type) {
            case "semantic":
                return renderSemantic(code);
            case "source":
                return renderSource(code, structuresAlreadyRead);
            case "kb":
                // 范围句柄：只声明检索范围，正文由 kb_search 召回（沿用 @kb 的既有语义，不预注入）
                return "[知识] " + code + "  范围已声明（正文由 kb_search 召回）\n";
            case "skill":
            case "doc":
                return "[" + type + "] " + code + "  已引用（深度 1，不再展开；模型可用 read_skill 按需读取）\n";
            case "topic":
            case "env":
                return "[" + type + "] " + code + "  已声明（名册引用）\n";
            case "table":
            case "column":
                // 历史类型：编辑器已不注册候选，装配不参与（校验器也只给"建议改写法"）
                return null;
            default:
                return null;
        }
    }

    /** 语义资产：按句柄解析后读取正文（读不到就显式失败，不让模型猜）。 */
    private String renderSemantic(String code) {
        try {
            SemanticAssetRef ref = semanticAssetProvider.resolve(code);
            if (ref == null) {
                return "[未解析] @semantic/" + code + "  语义资产不存在或当前用户不可见\n";
            }
            String detail = semanticAssetProvider.read(ref, SINGLE_ASSET_CHARS);
            String meta = StrUtil.blankToDefault(ref.getType(), "ASSET");
            return "[语义] "
                    + code
                    + "  已装配（"
                    + meta
                    + " · v"
                    + StrUtil.nullToEmpty(ref.getVersion())
                    + "）\n"
                    + indent(detail);
        } catch (BusException e) {
            // 句柄歧义 / 项目未发布：属"用户可修正"，原因如实回显
            return "[未解析] @semantic/" + code + "  " + StrUtil.blankToDefault(e.getMessage(), "语义资产解析失败") + "\n";
        } catch (RuntimeException e) {
            log.warn("Semantic asset assembly failed: code={}, msg={}", code, e.getMessage());
            return "[未解析] @semantic/" + code + "  语义资产读取失败，已跳过\n";
        }
    }

    /** 物理表：表名 + 列清单（裁剪到 {@link #MAX_COLUMNS} 列）。 */
    private String renderSource(String code, int structuresAlreadyRead) {
        SourceRefResolver.Ref ref = SourceRefResolver.parse(code);
        if (ref == null) {
            return "[未解析] @source/" + code + "  路径不合法（应为 数据源[/库[/表[.字段]]]）\n";
        }
        if (ref.level() == 1) {
            return "[表] " + code + "  已声明数据源范围\n";
        }
        if (ref.level() == 2) {
            return "[表] " + code + "  已声明库范围\n";
        }
        if (structuresAlreadyRead >= MAX_STRUCTURE_TABLES) {
            return "[表] " + code + "  已声明（本次装配的表结构读取已达上限 " + MAX_STRUCTURE_TABLES + " 张，结构省略）\n";
        }
        DataBase dataBase = findDataSource(ref.getDatasource());
        if (dataBase == null) {
            return "[未解析] @source/" + code + "  本租户下没有启用中的同名数据源\n";
        }
        try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
            List<Column> columns = driver.listColumns(ref.getDatabase(), ref.getTable());
            if (CollUtil.isEmpty(columns)) {
                return "[未解析] @source/" + code + "  表结构为空（或该账号无权读取表结构）\n";
            }
            List<String> names = new ArrayList<>();
            for (Column column : columns) {
                if (column == null || StrUtil.isBlank(column.getName())) {
                    continue;
                }
                if (names.size() >= MAX_COLUMNS) {
                    break;
                }
                String name = column.getName();
                // 字段级句柄：把目标列标出来，模型才知道要看哪一列
                if (StrUtil.equalsIgnoreCase(name, ref.getColumn())) {
                    name = name + "★";
                }
                names.add(name);
            }
            String suffix = columns.size() > names.size() ? " …（共 " + columns.size() + " 列）" : "";
            return "[表] "
                    + code
                    + "  已装配（列 "
                    + names.size()
                    + "/"
                    + columns.size()
                    + "："
                    + String.join(" / ", names)
                    + suffix
                    + "）\n";
        } catch (Exception e) {
            // 连接失败 ≠ 资源不存在：如实写"未能读取"，避免把网络问题渲染成"不存在"
            log.warn("@source 装配读取失败: code={}, msg={}", code, e.getMessage());
            return "[未解析] @source/" + code + "  未能读取（" + StrUtil.blankToDefault(e.getMessage(), "未知错误") + "）\n";
        }
    }

    private boolean isStructureRead(SkillHandleValidationVO.Handle handle) {
        if (!"source".equalsIgnoreCase(StrUtil.nullToEmpty(handle.getType()))) {
            return false;
        }
        SourceRefResolver.Ref ref = SourceRefResolver.parse(StrUtil.nullToEmpty(handle.getCode()));
        return ref != null && ref.level() >= 3;
    }

    private DataBase findDataSource(String name) {
        String wanted = StrUtil.nullToEmpty(name).toLowerCase(Locale.ROOT);
        List<DataBase> sources = dataBaseService.listEnabledAll();
        if (CollUtil.isEmpty(sources)) {
            return null;
        }
        for (DataBase dataBase : sources) {
            if (dataBase != null && StrUtil.equalsIgnoreCase(dataBase.getName(), wanted)) {
                return dataBase;
            }
        }
        return null;
    }

    /** 校验器给的状态说明；缺失时按状态兜一句，保证「未解析」永远有原因。 */
    private String statusReason(SkillHandleValidationVO.Handle handle) {
        String message = handle.getMessage();
        if (StrUtil.isNotBlank(message)) {
            return message;
        }
        String status = StrUtil.nullToEmpty(handle.getStatus());
        switch (status) {
            case SkillHandleValidator.STATUS_DENIED:
                return "当前用户无访问权限";
            case SkillHandleValidator.STATUS_NOT_FOUND:
                return "资源不存在";
            case SkillHandleValidator.STATUS_UNKNOWN_TYPE:
                return "未知的引用类型";
            default:
                return "未能解析";
        }
    }

    /** 缩进多行正文，让装配块的层级在 prompt 里可读（不影响模型理解）。 */
    private String indent(String text) {
        if (StrUtil.isEmpty(text)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }
}
