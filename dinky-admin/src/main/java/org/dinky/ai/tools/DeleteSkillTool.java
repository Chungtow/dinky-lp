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
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.service.SkillService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 删除 skill / doc（🔴 <b>破坏性写类</b>：必须经<b>强化确认</b>）。
 *
 * <p><b>为什么用「输入名字」而不是「点两次确认」</b>：连点两次对误操作几乎没有防护（手快就连点），
 * 而输入名称能强制操作者确认「删的到底是哪一个」——GitHub 删仓库用的就是这个模式。
 * 载荷里 {@code requireTypedName=true}，由前端渲染输入框、后端比对回投值（见
 * {@code AiChatServiceImpl#confirmRun}）——<b>不能只靠前端禁用按钮</b>，否则这道闸形同虚设。
 *
 * @since 2026/10/05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteSkillTool implements AiTool {

    public static final String NAME = "delete_skill";

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
        properties.set("name", AiToolSpec.stringProperty("要删除的 skill / 知识文档名称（必须与 list_skills 返回的完全一致）"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("删除一个 skill / 知识文档（目录、其中的所有文件与元数据都会被删除，不可恢复）。"
                        + "只有在用户**明确要求删除**时使用；会要求用户手动输入名称确认，"
                        + "被拒绝或未确认时不会删除，且不要重试。")
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

        AiToolContext.ConfirmRequester requester = context.getConfirmRequester();
        if (requester == null) {
            log.warn("delete_skill rejected: no confirm channel, name={}", name);
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("当前会话不支持二次确认，已拒绝删除")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
        ConfirmPayload payload = ConfirmPayload.ofSkillDelete(name);
        payload.setRequireTypedName(true);
        if (!requester.request(payload)) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("用户未确认删除该 skill，已取消；请勿重试")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }

        try {
            skillService.removeSkill(brief.getId(), context.getUserId());
            // 阶段 4b 修复：删除后同步移出本对话的可见快照（与 create_skill 的追加对称），
            // 避免模型继续按旧快照 read/write 一个已经被删掉的 skill。
            if (context.getVisibleSkills() != null) {
                List<SkillBrief> remaining = new ArrayList<>(context.getVisibleSkills());
                remaining.removeIf(one -> name.equals(one.getName()));
                context.setVisibleSkills(remaining);
            }
            return AiToolResult.builder()
                    .success(true)
                    .content("已删除 " + name + "（目录、文件与元数据均已移除）")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (BusException e) {
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("删除失败：" + e.getMessage())
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        } catch (Exception e) {
            log.warn("delete_skill failed, name={}, msg={}", name, e.getMessage());
            return AiToolResult.builder()
                    .success(false)
                    .errorMessage("删除失败：请稍后重试或改由用户在 Skills 页面删除")
                    .costMs(System.currentTimeMillis() - start)
                    .writeAttempted(true)
                    .build();
        }
    }
}
