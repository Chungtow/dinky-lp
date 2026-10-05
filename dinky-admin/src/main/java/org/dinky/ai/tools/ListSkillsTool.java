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
import org.dinky.data.model.SystemConfiguration;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * 列出可见的 skill / 知识文档（🟢 只读）。
 *
 * <p>数据来自请求线程预解析的快照（{@link AiToolContext#getVisibleSkills()}），<b>不查库</b>——
 * 见 {@link SkillToolSupport} 的说明。
 *
 * <p><b>开关</b>：需同时满足 {@code sys.llm.settings.skillEnable}（skill 功能总开关，阶段 4a）与
 * {@code sys.llm.settings.toolSkillEnable}（工具开关，阶段 4b）才会下发给模型。
 *
 * @since 2026/10/05
 */
@Slf4j
@Component
public class ListSkillsTool implements AiTool {

    public static final String NAME = "list_skills";

    /** 清单上限：清单本身不是答案，过长既费 token 又挤占上下文 */
    private static final int MAX_ITEMS = 100;

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
        return AiToolSpec.builder()
                .name(NAME)
                .description("列出当前用户可见的 skill 与知识文档，只返回名称与一句话说明，不含正文。"
                        + "当用户问'我们有哪些规范 / SOP / skill'，或需要确认某个 skill 的准确名称时使用。"
                        + "注意：只包含当前用户可见的资产，不是全量；要读正文请用 read_skill；"
                        + "问'有哪些表''表结构'请用 list_tables / describe_table。")
                .parameters(AiToolSpec.objectSchema(null, new JSONObject(), Collections.<String>emptyList()))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        List<SkillBrief> skills = context == null ? null : context.getVisibleSkills();
        if (CollUtil.isEmpty(skills)) {
            return AiToolResult.success("当前用户可见的 skill / 知识文档为空（尚未创建，或当前用户无访问权限）。", System.currentTimeMillis() - start);
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(skills.size(), MAX_ITEMS);
        sb.append("当前用户可见 ").append(skills.size()).append(" 个资产");
        if (limit < skills.size()) {
            sb.append("（仅列出前 ").append(limit).append(" 个）");
        }
        sb.append("：\n");
        for (int i = 0; i < limit; i++) {
            SkillBrief brief = skills.get(i);
            sb.append("- ").append(brief.getName());
            if (StrUtil.isNotBlank(brief.getDescription())) {
                sb.append(" — ").append(brief.getDescription());
            }
            if (StrUtil.isNotBlank(brief.getAssetType())
                    && !SkillDocParser.ASSET_TYPE_SKILL.equals(brief.getAssetType())) {
                sb.append("（类型：").append(brief.getAssetType()).append("）");
            }
            sb.append("\n");
        }
        return AiToolResult.success(sb.toString(), System.currentTimeMillis() - start);
    }
}
