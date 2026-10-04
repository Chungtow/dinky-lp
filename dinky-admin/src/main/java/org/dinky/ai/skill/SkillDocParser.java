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

    /** skill 根目录名（资源存储中的固定前缀） */
    public static final String SKILLS_DIR = "skills";

    /** skill 主文件名 */
    public static final String SKILL_MAIN_FILE = "SKILL.md";

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
            throw new BusException("SKILL.md 缺少 frontmatter 的 name（合法 skill 必须含 SKILL.md，且声明 name 与 description）");
        }
        if (!NAME_PATTERN.matcher(doc.getName()).matches()) {
            throw new BusException("skill 名不合法：" + doc.getName() + "（要求 ^[a-z0-9][a-z0-9-]{1,63}$）");
        }
        if (StrUtil.isNotBlank(dirName) && !doc.getName().equals(dirName)) {
            throw new BusException("skill 名与目录名不一致：name=" + doc.getName() + "，dir=" + dirName);
        }
        if (StrUtil.isBlank(doc.getDescription())) {
            throw new BusException("SKILL.md 缺少 frontmatter 的 description");
        }
        if (doc.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusException("description 过长（最多 " + MAX_DESCRIPTION_LENGTH + " 字符）");
        }
    }

    /** 新建 skill 时生成的 {@code SKILL.md} 模板 */
    public static String buildTemplate(String name, String description) {
        String safeDescription = StrUtil.replace(StrUtil.nullToEmpty(description), "\n", " ");
        return DELIMITER + "\nname: " + name + "\ndescription: " + safeDescription + "\n" + DELIMITER + "\n\n# " + name
                + "\n\n（在此填写该 skill 的规范 / SOP / 业务流程说明；可另建 references/ 目录放补充文档）\n";
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
