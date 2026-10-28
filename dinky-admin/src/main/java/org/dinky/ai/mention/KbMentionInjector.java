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

package org.dinky.ai.mention;

import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.model.SystemConfiguration;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @kb/<code>} 注入器（知识库 {@code @kb} 接入 v1.0）。
 *
 * <p><b>⚠️ 本类最重要的一句话：{@code @kb} 是范围句柄，不是内容句柄。</b>
 * 它<b>只</b>产出一行"本次在哪些库里检索"的声明，<b>绝不</b>调用 {@code /v1/search}、
 * <b>绝不</b>注入任何文档正文。正文一律由模型按需调用 {@code kb_search} 工具召回。
 *
 * <p>若把这里改成"读出库里文档全文再拼进上下文"，会立刻退化成预注入：
 * 每次对话都产生 embedding 费用与数百毫秒延迟，还把大量无关片段塞进上下文
 * （调研 §4.7.0 已论证，实施计划 U-06 是这条红线的专项用例）。
 *
 * <p><b>越权必须显式拒绝</b>：{@code code} 不在可见集内时，回一句"你无权访问该知识库"并置
 * {@code kb_denied}，<b>不</b>下发检索，也<b>不</b>静默降级成"可见集并集"。静默降级是最坏失败：
 * 用户以为搜了 docC，实际搜的是别的库，回答"有据"却答错了范围。
 *
 * @since 2026/10/27
 */
@Slf4j
@Component
public class KbMentionInjector implements MentionInjector {

    @Override
    public String type() {
        return MentionType.KB;
    }

    @Override
    public String inject(MentionInjectContext context) {
        if (context == null || context.getMention() == null) {
            return "";
        }
        // 开关关闭时**完全不注入**（连"无权访问"都不写）：U-14 要求行为与接入前逐字一致，
        // 否则用户会看到一句莫名其妙的"知识库无权限"，以为是自己配置错了。
        if (!SystemConfiguration.getInstances().isLlmKbEnable()) {
            return "";
        }
        String code = context.getMention().getName();
        List<KbCollectionBrief> visible = context.getVisibleCollections();
        String rendered = render(code, visible);
        // 审计：@kb 指到不可见/已停用的库时留痕。判定条件与 render 内部完全一致（同一个 find），
        // 不另写一套"是否越权"的判断——两套口径迟早会分叉
        if (StrUtil.isNotBlank(StrUtil.trim(code))
                && find(visible, StrUtil.trim(code)) == null
                && context.getRequest() != null) {
            context.getRequest().recordKbDenied(StrUtil.trim(code));
        }
        return rendered;
    }

    /**
     * 渲染单个 {@code @kb/<code>} 的范围声明。
     *
     * @param code 库编码（{@code c_} + 8 hex）；为空表示无法识别
     * @param visibleCollections 请求线程算好的可见集快照
     * @return 要追加到上下文的文本（永不为 null）
     */
    public String render(String code, List<KbCollectionBrief> visibleCollections) {
        String target = StrUtil.trim(code);
        if (StrUtil.isBlank(target)) {
            return "- @kb/（缺少知识库编码，已忽略；正确写法如 `@kb/c_1a2b3c4d`）\n";
        }
        KbCollectionBrief hit = find(visibleCollections, target);
        if (hit == null) {
            // 越权 / 不存在 / 已停用：显式拒绝，且不下发任何检索（D-B6）
            log.info("Kb mention denied: code={}, visible={}", target, labels(visibleCollections));
            return "- @kb/" + target + "（无访问权限或该知识库已停用，本次不会在其中检索；可用范围：" + labels(visibleCollections) + "）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- 知识库范围：")
                .append(StrUtil.nullToEmpty(hit.getName()))
                .append("（")
                .append(hit.getCode())
                .append("）");
        if (StrUtil.isNotBlank(hit.getDescription())) {
            sb.append(" — ").append(hit.getDescription());
        }
        sb.append("\n");
        sb.append("  → 本次对话请用 `kb_search` 工具在该库内检索；命中后回答需带出处（来源 §章节）。\n");
        log.info("Kb mention injected: code={}, name={}", hit.getCode(), hit.getName());
        return sb.toString();
    }

    /** 可见集为空时也给模型一句可执行的结论，避免它反复试工具 */
    public String renderEmptyScope() {
        return "- 知识库：当前账号没有可访问的知识库，不要调用 `kb_search`。\n";
    }

    private KbCollectionBrief find(List<KbCollectionBrief> visibleCollections, String code) {
        if (CollUtil.isEmpty(visibleCollections)) {
            return null;
        }
        for (KbCollectionBrief brief : visibleCollections) {
            if (brief != null && code.equals(brief.getCode())) {
                return brief;
            }
        }
        return null;
    }

    /** 可见库标签清单（脱敏：只列名字与编码，不含归属人） */
    private String labels(List<KbCollectionBrief> visibleCollections) {
        if (CollUtil.isEmpty(visibleCollections)) {
            return "（无）";
        }
        List<String> parts = new ArrayList<>();
        for (KbCollectionBrief brief : visibleCollections) {
            if (brief != null && StrUtil.isNotBlank(brief.getCode())) {
                parts.add(StrUtil.nullToEmpty(brief.getName()) + "(" + brief.getCode() + ")");
            }
        }
        return parts.isEmpty() ? "（无）" : String.join("、", parts);
    }
}
