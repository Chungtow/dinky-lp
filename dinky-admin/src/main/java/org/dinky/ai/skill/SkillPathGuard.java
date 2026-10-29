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

import java.nio.file.Paths;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

/**
 * skill 内「相对路径」的安全闸（阶段 4b）。
 *
 * <p><b>为什么单独抽一个类</b>：4a 只在 skill <b>名字</b>上做了白名单；4b 新增的「写任意相对路径文件」
 * 是本项目<b>第一个"路径型"接口</b>——路径由用户（或模型）给出，必须经过统一校验，
 * 否则可越权写出 {@code skills/} 根之外。Controller（人类 UI）与 AI 工具<b>共用</b>本类，
 * 保证「同一个路径，两处判定一致」。
 *
 * <p><b>三层校验</b>（缺一不可）：
 * <ol>
 *   <li><b>字符白名单</b>：只允许 {@code [A-Za-z0-9_-./]}——挡掉空白、反斜杠、盘符、通配符等；</li>
 *   <li><b>段级校验</b>：按 {@code /} 切段后，任一段为空 / {@code .} / {@code ..} 一律拒绝，且禁止以 {@code /} 开头；</li>
 *   <li><b>规范化断言</b>：拼出的绝对路径经 {@code normalize()} 后必须与原值相等（进一步兜住任何消解行为）。</li>
 * </ol>
 *
 * @since 2026/10/05
 */
public final class SkillPathGuard {

    /** 相对路径允许的字符集（注意：不含空白、不含 {@code \}、不含 {@code :}） */
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[A-Za-z0-9_\\-./]+$");

    /** 允许写入的文本类扩展名（决策：仅文本，且不执行任何脚本） */
    public static final String[] ALLOWED_EXTENSIONS = {".md", ".txt", ".json", ".yaml", ".yml"};

    private SkillPathGuard() {}

    /**
     * 把「skill 根目录 + 用户给出的相对路径」安全地拼成资源存储中的完整路径。
     *
     * @param dirFullName skill 的根目录（来自 {@code dinky_skill.dir_full_name}，是<b>服务端</b>值，非用户输入）
     * @param relativePath 相对路径（如 {@code references/conventions.md}）
     * @return 完整 fullName（如 {@code /skills/dw-sql-review/references/conventions.md}）
     * @throws BusException 校验不通过
     */
    public static String resolve(String dirFullName, String relativePath) {
        String rel = StrUtil.trimToEmpty(relativePath);
        if (StrUtil.isBlank(rel)) {
            throw new BusException("相对路径不能为空");
        }
        if (rel.length() > 512) {
            throw new BusException("相对路径过长（最多 512 字符）");
        }
        if (!ALLOWED_CHARS.matcher(rel).matches()) {
            throw new BusException("相对路径含非法字符：" + rel + "（仅允许字母 / 数字 / _ - . /）");
        }
        if (rel.startsWith("/")) {
            throw new BusException("不允许使用绝对路径：" + rel);
        }
        for (String segment : rel.split("/")) {
            if (StrUtil.isBlank(segment) || ".".equals(segment) || "..".equals(segment)) {
                throw new BusException("相对路径含非法片段：" + rel);
            }
        }
        String base = normalizeDir(dirFullName);
        if (StrUtil.isBlank(base) || "/".equals(base)) {
            throw new BusException("skill 根目录不合法，拒绝写入");
        }
        String full = base + "/" + rel;
        // 规范化断言：Linux 下 Paths 不会改写已合法的路径；若改写则说明存在逃逸行为
        String normalized = Paths.get(full).normalize().toString().replace('\\', '/');
        if (!normalized.equals(full)) {
            throw new BusException("路径不规范（疑似目录穿越）：" + relativePath);
        }
        return full;
    }

    /**
     * 校验扩展名（仅对<b>非主文件</b>生效；主文件如 {@code SKILL.md} 天然是 .md）。
     *
     * @param relativePath 相对路径
     * @param mainFileName 该资产类型的主文件名（如 {@code SKILL.md}）；命中则跳过校验
     */
    public static void checkExtension(String relativePath, String mainFileName) {
        String rel = StrUtil.nullToEmpty(relativePath);
        if (StrUtil.isNotBlank(mainFileName) && rel.equals(mainFileName)) {
            return;
        }
        String lower = rel.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return;
            }
        }
        throw new BusException("不允许的文件类型：" + rel + "（仅支持 .md / .txt / .json / .yaml）");
    }

    /** 单文件大小校验（沿用 4a 的 256K 上限） */
    public static void checkSize(String content) {
        int bytes = StrUtil.utf8Bytes(StrUtil.nullToEmpty(content)).length;
        if (bytes > SkillDocParser.MAX_DOC_SIZE) {
            throw new BusException("文件过大（最多 " + SkillDocParser.MAX_DOC_SIZE + " 字节）");
        }
    }

    /** 目录规范化：补前导 {@code /}、去尾随 {@code /} */
    public static String normalizeDir(String dirFullName) {
        String dir = StrUtil.nullToEmpty(dirFullName);
        if (!dir.startsWith("/")) {
            dir = "/" + dir;
        }
        return StrUtil.removeSuffix(dir, "/");
    }

    /**
     * 取相对路径中的<b>父目录</b>部分（用于惰性建目录）。
     *
     * @return 如 {@code references/conventions.md} → {@code references}；顶层文件返回空串
     */
    public static String parentOf(String relativePath) {
        String rel = StrUtil.nullToEmpty(relativePath);
        int idx = rel.lastIndexOf('/');
        return idx <= 0 ? "" : rel.substring(0, idx);
    }

    /** 取相对路径中的<b>文件名</b>部分 */
    public static String nameOf(String relativePath) {
        String rel = StrUtil.nullToEmpty(relativePath);
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? rel : rel.substring(idx + 1);
    }
}
