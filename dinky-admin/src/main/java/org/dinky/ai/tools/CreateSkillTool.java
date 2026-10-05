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
import org.dinky.ai.skill.SkillDocParser;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Skill;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.service.SkillService;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 创建 skill（🔴 <b>写类</b>：必须先经用户二次确认）。
 *
 * <p>实现照抄 {@code ExecSqlTool} 的闸门三件套：① {@code isEnabled} 读配置；② {@code timeoutSeconds}
 * 用写类协调值；③ {@code execute} 内取 {@code confirmRequester}，<b>为 null 一律拒绝</b>（fail-safe）、
 * 被拒绝后明确「不要重试」。
 *
 * <p>执行分两步（都走 {@link SkillService}，与服务端 UI 完全同一套校验）：
 * ① {@code create} 建目录 + 写模板主文件；② 若模型给了 {@code content}，再经 {@code writeFile} 覆盖
 * 主文件（那里会重新解析 frontmatter，非法内容会被拒）。
 *
 * @since 2026/10/05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateSkillTool implements AiTool {

    public static final String NAME = "create_skill";

    /** 正文长度上限（与 writeFile 的单文件上限口径一致） */
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
        properties.set("name", AiToolSpec.stringProperty("skill 名称：只能用小写字母 / 数字 / 连字符（如 dw-sql-review），同时作为目录名"));
        properties.set("description", AiToolSpec.stringProperty("一句话说明该 skill 解决什么问题（会展示在清单里供检索）"));
        properties.set(
                "content",
                AiToolSpec.stringProperty(
                        "可选：SKILL.md 完整内容（含 frontmatter 的 name / description）。" + "建议填完整以便一次成型；留空则由系统生成模板"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("创建一个新的 skill（团队规范 / SOP / 流程知识）。"
                        + "会向用户请求二次确认——用户拒绝或未确认时不会创建，且不要重试。"
                        + "只在用户明确要求沉淀某套流程 / 规范时使用；"
                        + "创建后可用 write_skill_file 补充 references/ 附件。")
                .parameters(AiToolSpec.objectSchema(null, properties, Arrays.asList("name", "description")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        String name = args == null ? null : StrUtil.trimToNull(args.getStr("name"));
        String description = args == null ? null : StrUtil.trimToNull(args.getStr("description"));
        if (name == null || description == null) {
            return AiToolResult.failure("缺少 name 或 description 参数", System.currentTimeMillis() - start);
        }
        String content = args.getStr("content");
        if (content != null && content.length() > MAX_CONTENT_CHARS) {
            return AiToolResult.failure(
                    "content 过长（超过 " + MAX_CONTENT_CHARS + " 字符），已拒绝", System.currentTimeMillis() - start);
        }

        // ① 二次确认（fail-safe：没有确认通道就绝不写）
        AiToolContext.ConfirmRequester requester = context == null ? null : context.getConfirmRequester();
        if (requester == null) {
            log.warn("create_skill rejected: no confirm channel, name={}", name);
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("当前会话不支持写操作二次确认，已拒绝创建")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
        String preview = mainFilePreview(name, description, content);
        if (!requester.request(ConfirmPayload.ofSkillFile(name, SkillDocParser.SKILL_MAIN_FILE, "", preview))) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("用户未确认创建该 skill，已取消；请勿重试，可改为把建议内容直接回复给用户")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }

        // ② 创建（actorId 显式传入：工具跑在异步线程，不能依赖 StpUtil）
        try {
            Skill created = skillService.create(name, description, context.getUserId());
            if (StrUtil.isNotBlank(content)) {
                skillService.writeFile(created.getId(), SkillDocParser.SKILL_MAIN_FILE, content, context.getUserId());
            }
            return AiToolResult.builder()
                    .success(true)
                    .content("已创建 skill：" + name + "（目录 " + created.getDirFullName() + "）。"
                            + "如需补充附件，可用 write_skill_file 写入 references/ 下的文件。")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (BusException e) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("创建失败：" + e.getMessage())
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (Exception e) {
            log.warn("create_skill failed, name={}, msg={}", name, e.getMessage());
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("创建失败：请稍后重试或改由管理员在 Skills 页面创建")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
    }

    /** 生成确认框里展示的「将要写入的内容」（模板或模型给的内容） */
    private String mainFilePreview(String name, String description, String content) {
        return StrUtil.isNotBlank(content) ? content : SkillDocParser.buildTemplate(name, description);
    }
}
