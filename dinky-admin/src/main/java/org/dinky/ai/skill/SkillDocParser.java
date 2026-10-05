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

import org.dinky.data.exception.BusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

/**
 * {@code SKILL.md} 的轻量解析与校验（阶段 4a）。
 *
 * <p><b>刻意不引入 YAML 依赖</b>（延续零依赖路线）：只支持 frontmatter 里的<b>单层</b>
 * {@code key: value}——本项目 skill 的元数据只有 {@code name} / {@code description}，用不着完整 YAML。
 *
 * <p><b>「合法 skill」的定义（决策 D10）</b>：目录 + 必含 {@code SKILL.md} + frontmatter 含
 * {@code name} / {@code description} 且校验通过；{@code name} 必须与<b>目录名一致</b>。
 *
 * @since 2026/10/05
 */
public final class SkillDocParser {

    /** skill 名规范（Agent Skills 惯例：小写字母 / 数字 / 连字符，且与目录名一致） */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    /** frontmatter 分隔符 */
    private static final String DELIMITER = "---";

    /** description 最大长度（与 {@code dinky_skill.description} 列宽一致） */
    public static final int MAX_DESCRIPTION_LENGTH = 512;

    /** SKILL.md 单文件最大字节数 */
    public static final int MAX_DOC_SIZE = 256 * 1024;

    /**
     * 资产类型：skill（阶段 4a）——「人写的流程知识」，注入方式为**清单常驻 + 显式引用全文**。
     *
     * <p>注意：{@code dinky_skill} 表<b>同时承载 doc</b>（见 {@link #ASSET_TYPE_DOC}），
     * 该表实际语义是「<b>知识资产元数据表</b>」，{@code asset_type} 用于区分。
     */
    public static final String ASSET_TYPE_SKILL = "skill";

    /**
     * 资产类型：doc（阶段 4b）——「业务背景知识」，与 skill **同构**（Markdown 散文、人写、
     * 权限模型一致），故复用同一张表与同一套存储/权限/引用机制（总体计划 §4.9 决策 A3）。
     *
     * <p>它与语义层（Apache Ossie，强结构 JSON、需机器校验）<b>不同</b>——后者按 §4.9 A1 走独立上层。
     */
    public static final String ASSET_TYPE_DOC = "doc";

    /** skill 根目录名（资源存储中的固定前缀） */
    public static final String SKILLS_DIR = "skills";

    /** doc 根目录名（与 {@code skills/} 并列，见总体计划 §4.8.3-④「目录不写死格式」） */
    public static final String DOCS_DIR = "docs";

    /** skill 主文件名 */
    public static final String SKILL_MAIN_FILE = "SKILL.md";

    /** doc 主文件名 */
    public static final String DOC_MAIN_FILE = "DOC.md";

    /** 按资产类型取根目录名 */
    public static String rootDirOf(String assetType) {
        return ASSET_TYPE_DOC.equals(assetType) ? DOCS_DIR : SKILLS_DIR;
    }

    /** 按资产类型取主文件名 */
    public static String mainFileOf(String assetType) {
        return ASSET_TYPE_DOC.equals(assetType) ? DOC_MAIN_FILE : SKILL_MAIN_FILE;
    }

    private SkillDocParser() {}

    /**
     * 解析 {@code SKILL.md}（YAML frontmatter + Markdown 正文）。
     *
     * @param raw 文件全文
     * @return 解析结果；<b>无 frontmatter 或格式不可识别时返回 {@code null}</b>（调用方据此判定「不是合法 skill」）
     */
    public static SkillDoc parse(String raw) {
        String text = StrUtil.nullToEmpty(raw);
        String fromStart = StrUtil.trimStart(text);
        if (!fromStart.startsWith(DELIMITER)) {
            return null;
        }
        int firstLineEnd = fromStart.indexOf('\n');
        if (firstLineEnd < 0) {
            return null;
        }
        int end = fromStart.indexOf(DELIMITER, firstLineEnd);
        if (end < 0) {
            return null;
        }
        String header = fromStart.substring(firstLineEnd + 1, end);
        String body = fromStart.substring(end + DELIMITER.length());

        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            String trimmedLine = StrUtil.trim(line);
            if (StrUtil.isBlank(trimmedLine) || trimmedLine.startsWith("#")) {
                continue;
            }
            int idx = trimmedLine.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = StrUtil.trim(trimmedLine.substring(0, idx)).toLowerCase();
            frontmatter.put(key, unquote(StrUtil.trim(trimmedLine.substring(idx + 1))));
        }

        SkillDoc doc = new SkillDoc();
        doc.setName(StrUtil.trimToNull(frontmatter.get("name")));
        doc.setDescription(StrUtil.trimToNull(frontmatter.get("description")));
        doc.setBody(StrUtil.trim(body));
        return doc;
    }

    /**
     * 校验是否为「合法 skill」（决策 D10）。
     *
     * @param doc 解析结果（可为 null）
     * @param dirName skill 目录名（用于一致性校验；传 null / 空则跳过该检查）
     * @throws BusException 校验不通过
     */
    public static void validate(SkillDoc doc, String dirName) {
        if (doc == null || StrUtil.isBlank(doc.getName())) {
            throw new BusException("主文件缺少 frontmatter 的 name（合法 skill / doc 必须含主文件，且声明 name 与 description）");
        }
        if (!NAME_PATTERN.matcher(doc.getName()).matches()) {
            throw new BusException("名称不合法：" + doc.getName() + "（要求 ^[a-z0-9][a-z0-9-]{1,63}$）");
        }
        if (StrUtil.isNotBlank(dirName) && !doc.getName().equals(dirName)) {
            throw new BusException("名称与目录名不一致：name=" + doc.getName() + "，dir=" + dirName);
        }
        if (StrUtil.isBlank(doc.getDescription())) {
            throw new BusException("主文件缺少 frontmatter 的 description");
        }
        if (doc.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusException("description 过长（最多 " + MAX_DESCRIPTION_LENGTH + " 字符）");
        }
    }

    /** 新建 skill 时生成的 {@code SKILL.md} 模板 */
    public static String buildTemplate(String name, String description) {
        String safeDescription = StrUtil.replace(StrUtil.nullToEmpty(description), "\n", " ");
        return DELIMITER + "\nname: " + name + "\ndescription: " + safeDescription + "\n" + DELIMITER + "\n\n# " + name
                + "\n\n（在此填写该 skill 的规范 / SOP / 业务流程说明；可点「文件」按钮新建 references/ 目录放补充文档）\n";
    }

    /**
     * 新建 doc 时生成的 {@code DOC.md} 模板（阶段 4b）。
     *
     * <p>doc 与 skill 只差「定位」：skill 讲<b>怎么做</b>（流程 / SOP），doc 讲<b>是什么</b>
     * （业务背景、口径由来、历史上的坑）。模板据此给出不同提示，避免两者写混。
     */
    public static String buildDocTemplate(String name, String description) {
        String safeDescription = StrUtil.replace(StrUtil.nullToEmpty(description), "\n", " ");
        return DELIMITER + "\nname: " + name + "\ndescription: " + safeDescription + "\n" + DELIMITER + "\n\n# " + name
                + "\n\n（在此填写业务背景知识：指标口径及其由来、字段含义、历史上的坑等；"
                + "可点「文件」按钮新建 references/ 目录放补充材料）\n";
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
