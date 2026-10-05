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

package org.dinky.ai.tools;

import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.ai.skill.SkillBrief;
import org.dinky.ai.skill.SkillDocParser;
import org.dinky.ai.skill.SkillPathGuard;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.resource.BaseResourceManager;

import java.util.Collections;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 读取 skill / 知识文档的正文（🟢 只读；支持 {@code references/} 子文件）。
 *
 * <p><b>这是 4a 埋下的 {@code references/} 消费通道</b>：4a 明确规定「{@code references/} 不自动注入，
 * 由模型在 4b 用工具按需读取」——即本工具。它让「按需加载」不必把所有附件塞进 system prompt。
 *
 * <p>读取直接走资源存储（不经资源表、不查库）：路径由 {@link SkillPathGuard} 校验，
 * 资产可见性由快照（{@link AiToolContext#getVisibleSkills()}）判定。
 *
 * @since 2026/10/05
 */
@Slf4j
@Component
public class ReadSkillTool implements AiTool {

    public static final String NAME = "read_skill";

    /** 单次读取回灌给模型的字符上限（正文过长时截断，避免挤占上下文） */
    private static final int MAX_CHARS = 20000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config != null && config.isLlmSkillEnable() && config.isLlmToolSkillEnable();
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("name", AiToolSpec.stringProperty("skill / 知识文档的名称；请先用 list_skills 确认准确名称"));
        properties.set(
                "relativePath",
                AiToolSpec.stringProperty("可选：要读的文件相对路径（如 references/conventions.md）；" + "留空则读主文件（SKILL.md / DOC.md）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("读取某个 skill / 知识文档的正文（默认读主文件，也可读 references/ 下的补充文档）。"
                        + "当用户提到某个 skill / 规范 / 业务口径，或需要按其内容作答时使用。"
                        + "只能读取当前用户可见的资产；name 必须与 list_skills 返回的完全一致。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("name")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        String name = args == null ? null : StrUtil.trimToNull(args.getStr("name"));
        if (name == null) {
            return AiToolResult.failure("缺少 name 参数", System.currentTimeMillis() - start);
        }
        SkillBrief brief = SkillToolSupport.find(context, name);
        if (brief == null) {
            return AiToolResult.failure(
                    SkillToolSupport.notVisibleMessage(name) + " 可选：" + SkillToolSupport.visibleNames(context),
                    System.currentTimeMillis() - start);
        }
        String relativePath = StrUtil.trimToNull(args.getStr("relativePath"));
        String fullName;
        try {
            fullName = relativePath == null
                    ? SkillPathGuard.normalizeDir(brief.getDirFullName()) + "/"
                            + SkillDocParser.mainFileOf(brief.getAssetType())
                    : SkillPathGuard.resolve(brief.getDirFullName(), relativePath);
        } catch (BusException e) {
            return AiToolResult.failure(
                    "路径不合法：" + e.getMessage() + "（只允许 skill 目录内的相对路径）", System.currentTimeMillis() - start);
        }
        try {
            String content = BaseResourceManager.getInstance().getFileContent(fullName);
            if (content == null) {
                return AiToolResult.failure(
                        "文件不存在或内容为空：" + (relativePath == null ? "主文件" : relativePath),
                        System.currentTimeMillis() - start);
            }
            String text = content.length() > MAX_CHARS
                    ? content.substring(0, MAX_CHARS) + "\n…（内容过长已截断，如需完整内容请让用户查看该 skill 文件）"
                    : content;
            return AiToolResult.success(text, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("read_skill failed, name={}, path={}, msg={}", name, fullName, e.getMessage());
            return AiToolResult.failure("读取失败：请确认该文件存在，或稍后重试", System.currentTimeMillis() - start);
        }
    }
}
