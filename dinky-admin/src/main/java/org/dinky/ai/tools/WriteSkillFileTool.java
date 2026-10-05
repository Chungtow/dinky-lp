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

import org.dinky.ai.AiChatRunRegistry;
import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.ai.ConfirmPayload;
import org.dinky.ai.skill.SkillBrief;
import org.dinky.ai.skill.SkillDocParser;
import org.dinky.ai.skill.SkillPathGuard;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.resource.BaseResourceManager;
import org.dinky.service.SkillService;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 写入 skill / doc 内的文件（🔴 <b>写类</b>：必须先经用户二次确认）。
 *
 * <p>这是 4b 里「人机共用一套能力」最直接的体现：本工具与 Skills 页的「文件」面板调用
 * <b>同一个</b> {@link SkillService#writeFile}——因此路径校验、扩展名白名单、大小上限、
 * 属主判定**只有一份实现**，不会出现"UI 拦得住、AI 绕得过"的裂缝。
 *
 * <p><b>确认内容</b>：按设计（总体计划 §4.7-5）展示「目标 skill + 文件 + <b>变更前后内容</b>」——
 * 因此写前会静默读一次旧内容作为 {@code beforeContent}（读不到则视为新建）。
 *
 * @since 2026/10/05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteSkillFileTool implements AiTool {

    public static final String NAME = "write_skill_file";

    /** 单次写入的字符上限（与 Service 侧 256K 字节口径配套的粗略前置校验） */
    private static final int MAX_CONTENT_CHARS = SkillDocParser.MAX_DOC_SIZE;

    private final SkillService skillService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config != null && config.isLlmSkillEnable() && config.isLlmToolSkillWriteEnable();
    }

    @Override
    public int timeoutSeconds() {
        return AiChatRunRegistry.writeToolTimeoutSeconds(
                SystemConfiguration.getInstances().getLlmToolWriteTimeoutSeconds());
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("name", AiToolSpec.stringProperty("目标 skill / 知识文档名称（先用 list_skills 确认）"));
        properties.set(
                "relativePath",
                AiToolSpec.stringProperty(
                        "相对路径，如 references/checklist.md（父目录不存在会自动创建）；" + "也允许写主文件 SKILL.md（会同步递增版本号）"));
        properties.set("content", AiToolSpec.stringProperty("要写入的文本内容（UTF-8，仅支持 .md / .txt / .json / .yaml）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("向某个 skill / 知识文档写入一个文件（新建或覆盖），常用于补充 references/ 下的说明文档。"
                        + "会向用户展示变更前后内容并请求二次确认——被拒绝或未确认时不会写入，且不要重试。"
                        + "只能写入当前用户可见且**自己创建**的资产；路径不允许出现 .. 或绝对路径。")
                .parameters(AiToolSpec.objectSchema(null, properties, Arrays.asList("name", "relativePath", "content")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        String name = args == null ? null : StrUtil.trimToNull(args.getStr("name"));
        String relativePath = args == null ? null : StrUtil.trimToNull(args.getStr("relativePath"));
        String content = args == null ? null : args.getStr("content");
        if (name == null || relativePath == null || content == null) {
            return AiToolResult.failure("缺少 name / relativePath / content 参数", System.currentTimeMillis() - start);
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return AiToolResult.failure(
                    "content 过长（超过 " + MAX_CONTENT_CHARS + " 字符），已拒绝", System.currentTimeMillis() - start);
        }
        SkillBrief brief = SkillToolSupport.find(context, name);
        if (brief == null) {
            return AiToolResult.failure(
                    SkillToolSupport.notVisibleMessage(name) + " 可选：" + SkillToolSupport.visibleNames(context),
                    System.currentTimeMillis() - start);
        }

        // ① 前置校验（与 Service 同一套规则）：参数不合法就早失败，不必白弹一次确认
        String fullName;
        try {
            SkillPathGuard.checkExtension(relativePath, SkillDocParser.mainFileOf(brief.getAssetType()));
            SkillPathGuard.checkSize(content);
            fullName = SkillPathGuard.resolve(brief.getDirFullName(), relativePath);
        } catch (BusException e) {
            return AiToolResult.failure("参数不合法，已拒绝：" + e.getMessage(), System.currentTimeMillis() - start);
        }

        // ② 二次确认（fail-safe）
        AiToolContext.ConfirmRequester requester = context.getConfirmRequester();
        if (requester == null) {
            log.warn("write_skill_file rejected: no confirm channel, name={}, path={}", name, relativePath);
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("当前会话不支持写操作二次确认，已拒绝写入")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
        String before = readSilently(fullName);
        if (!requester.request(ConfirmPayload.ofSkillFile(name, relativePath, before, content))) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("用户未确认写入该文件，已取消；请勿重试，可改为把内容直接回复给用户")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }

        // ③ 写入（经 Service：属主校验 + 路径校验 + 主文件版本维护）
        try {
            skillService.writeFile(brief.getId(), relativePath, content, context.getUserId());
            return AiToolResult.builder()
                    .success(true)
                    .content("已写入 " + name + " 的 " + relativePath + (StrUtil.isBlank(before) ? "（新建）" : "（已覆盖原内容）"))
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (BusException e) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("写入失败：" + e.getMessage())
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (Exception e) {
            log.warn("write_skill_file failed, name={}, path={}, msg={}", name, relativePath, e.getMessage());
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("写入失败：请稍后重试或改由用户在 Skills 页面编辑")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
    }

    /** 静默读旧内容（用于确认框展示 diff；读不到返回空串） */
    private String readSilently(String fullName) {
        try {
            return StrUtil.nullToEmpty(BaseResourceManager.getInstance().getFileContent(fullName));
        } catch (Exception e) {
            return "";
        }
    }
}
