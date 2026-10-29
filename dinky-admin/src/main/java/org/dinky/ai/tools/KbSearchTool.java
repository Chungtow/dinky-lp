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
import org.dinky.ai.kb.KbCollectionBrief;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.service.kb.KbClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识库检索工具 {@code kb_search}（🟢 只读，知识库 {@code @kb} 接入 v1.0）。
 *
 * <p><b>它是 {@code @kb} 的执行体</b>：{@code @kb/<code>} 只声明检索范围，正文由本工具按需召回。
 * 两者缺一不可——没有 {@code @kb} 就没有范围，没有本工具就只剩范围声明、没有任何内容。
 *
 * <p><b>范围怎么定</b>：
 * <ul>
 *   <li>模型显式传 {@code collection} → 用它，但<b>必须逐个在可见集内</b>，否则直接拒绝；</li>
 *   <li>未传 → 用当前用户<b>全部可见库</b>（无 {@code @} 时的默认范围，D-B5）。</li>
 * </ul>
 * kb-service 不认人，{@code filter.collection} 里放什么就搜什么——所以越权判定只能在这里、
 * 在请求发出<b>之前</b>完成。
 *
 * <p><b>{@code expand} 三档</b>（消费 kb-service 0.3.2 的 L0/L1 上下文扩展）：
 * {@code none} 精确片段 / {@code adjacent} 邻接扩片 / {@code section} 整节。
 * 选择权交给模型（只有它看得到问题原文），但 {@code expand_k} 固定 1 且不暴露——
 * 上界握在服务端，避免模型把结果撑到预算之外。
 *
 * @since 2026/10/27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbSearchTool implements AiTool {

    public static final String NAME = "kb_search";

    /** 单次返回条数上限（模型可调，但有上限） */
    private static final int MAX_TOP_K = 10;

    private static final int DEFAULT_TOP_K = 5;

    /** {@code expand_k} 固定为 1：不暴露给模型 */
    private static final int EXPAND_K = 1;

    private static final String EXPAND_NONE = "none";

    private static final String EXPAND_ADJACENT = "adjacent";

    private static final String EXPAND_SECTION = "section";

    private final KbClient kbClient;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 双重条件：功能开关打开 <b>且</b> kb-service 当前可用。
     *
     * <p>不可用时<b>不注册</b>本工具——否则模型会把有限额度浪费在必然失败的调用上
     * （4b 的 UAT 已踩过一次：工具连续失败后，真正该做的事反而没额度了）。
     */
    @Override
    public boolean isEnabled(SystemConfiguration config) {
        if (config == null || !config.isKbEnable()) {
            return false;
        }
        return kbClient.isReachable();
    }

    @Override
    public int timeoutSeconds() {
        return KbClient.SEARCH_TIMEOUT_SECONDS;
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("query", AiToolSpec.stringProperty("检索语句。用用户问题里的关键概念，不要带 @kb 前缀，也不要整段复制问题。"));
        JSONObject topK = AiToolSpec.integerProperty("返回条数，默认 5，范围 1~10。");
        topK.set("minimum", 1);
        topK.set("maximum", MAX_TOP_K);
        properties.set("top_k", topK);
        properties.set(
                "collection",
                AiToolSpec.stringProperty("可选的库编码（多个用英文逗号分隔）。不传 = 在当前对话允许的全部知识库内检索。" + "编码形如 c_1a2b3c4d，不要传显示名。"));
        JSONObject expand = AiToolSpec.stringProperty("上下文扩展档位：none（默认，只要命中片段）/ adjacent（把命中切片前后相邻切片一起返回，适合需要连贯上下文时）"
                + "/ section（返回命中切片所在的整个章节，适合问规范、流程、配置方法这类成体系的问题）。");
        expand.set("enum", new JSONArray().put(EXPAND_NONE).put(EXPAND_ADJACENT).put(EXPAND_SECTION));
        properties.set("expand", expand);

        return AiToolSpec.builder()
                .name(NAME)
                .description("在知识库（@kb）里检索文档片段，返回带出处的原文。"
                        + "当用户问到规范 / 流程 / 约定 / 配置方法 / 背景说明，或明确 @kb 了某个知识库时使用。"
                        + "问表结构、字段、数据行请用 describe_table 一类工具，不要用本工具。"
                        + "返回为空就如实说'未检索到'，不要凭记忆编造答案。")
                .parameters(AiToolSpec.objectSchema(null, properties, CollUtil.newArrayList("query")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        String query = args == null ? "" : StrUtil.trimToEmpty(args.getStr("query"));
        if (StrUtil.isBlank(query)) {
            return AiToolResult.failure("缺少 query 参数。请给出要检索的关键概念。", System.currentTimeMillis() - start);
        }
        List<KbCollectionBrief> visible = context == null ? null : context.getVisibleCollections();
        if (CollUtil.isEmpty(visible)) {
            return AiToolResult.success(
                    "当前账号没有可访问的知识库，无法检索。请直接说明'没有可用的知识库'，不要编造内容。", System.currentTimeMillis() - start);
        }

        List<String> scope = resolveScope(args.getStr("collection"), context);
        if (scope == null) {
            // 审计：越权请求必须留痕（排障第一现场：用户说"我明明有权限"时查这里）
            if (context != null && StrUtil.isNotBlank(args.getStr("collection"))) {
                context.recordKbDenied(args.getStr("collection"));
            }
            return AiToolResult.failure(
                    KbToolSupport.notVisibleMessage(args.getStr("collection")) + "当前可用："
                            + KbToolSupport.visibleLabels(context),
                    System.currentTimeMillis() - start);
        }

        int topK = normalizeTopK(args.get("top_k"));
        String expand = normalizeExpand(args.getStr("expand"));

        JSONObject filter = new JSONObject().set("collection", new JSONArray(scope));
        JSONObject body = new JSONObject()
                .set("query", query)
                .set("top_k", topK)
                .set("filter", filter)
                .set("expand", expand)
                .set("expand_k", EXPAND_K);

        try {
            JSONObject response = kbClient.search(body);
            if (context != null) {
                // 记"实际搜了哪些库 + 命中多少条"：0 条也要记，它是"检索了但库里没有"的客观依据
                JSONArray results = response == null ? null : response.getJSONArray("results");
                context.recordKbSearch(scope, results == null ? 0 : results.size());
            }
            String text = render(query, scope, expand, response, context);
            return AiToolResult.success(text, System.currentTimeMillis() - start);
        } catch (KbClient.KbBadRequestException e) {
            log.info("kb_search bad request: {}, scope={}", e.getMessage(), scope);
            return AiToolResult.failure(
                    "知识库检索参数不合法：" + e.getMessage() + "（这是参数问题，可改用更简单的 query 或去掉 collection 重试一次）",
                    System.currentTimeMillis() - start);
        } catch (KbClient.KbUnavailableException e) {
            log.warn("kb_search unavailable: {}", e.getMessage());
            // 必须显式声明不可用：绝不能让模型把"没查到"当成"知识库里没有"（实施计划 U-13）
            return AiToolResult.failure(
                    "知识库暂不可用（" + e.getMessage() + "）。**请勿据此断言规范或口径**，应告知用户知识库当前不可用。",
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("kb_search failed", e);
            return AiToolResult.failure(
                    "知识库检索失败（" + e.getClass().getSimpleName() + "）。请勿据此断言规范或口径。", System.currentTimeMillis() - start);
        }
    }

    /**
     * 解析本次检索范围。
     *
     * @return 命中的编码列表；传入的编码<b>有一个不在可见集内</b>时返回 {@code null}（调用方据此拒绝）
     */
    private List<String> resolveScope(String rawCollection, AiToolContext context) {
        if (StrUtil.isBlank(rawCollection)) {
            // 批次 E：左侧栏绑定了某个库时，它就是本轮的"默认范围"——
            // 用户正在看 A 库，模型却把全部可见库都搜一遍，等于把范围意图丢了（还会多花 retrieval 预算）。
            // 绑定值由注入层校验过可见性；这里仍再判一次，防止上下文被其它入口构造。
            String bound = context == null ? null : context.getDefaultKbCollection();
            if (StrUtil.isNotBlank(bound) && KbToolSupport.find(context, bound) != null) {
                return new ArrayList<>(Collections.singletonList(bound));
            }
            return KbToolSupport.visibleCodes(context);
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String part : StrUtil.split(rawCollection, ',')) {
            String code = StrUtil.trimToEmpty(part);
            if (StrUtil.isBlank(code)) {
                continue;
            }
            if (KbToolSupport.find(context, code) == null) {
                // 一个不合法就整体拒绝：部分放行会让模型以为"我搜了 A 和 B"，实际只搜了 A
                return null;
            }
            codes.add(code);
        }
        return new ArrayList<>(codes);
    }

    private int normalizeTopK(Object raw) {
        if (raw == null) {
            return DEFAULT_TOP_K;
        }
        try {
            int value = Integer.parseInt(StrUtil.trimToEmpty(String.valueOf(raw)));
            return Math.min(Math.max(value, 1), MAX_TOP_K);
        } catch (NumberFormatException e) {
            return DEFAULT_TOP_K;
        }
    }

    private String normalizeExpand(String raw) {
        String value = StrUtil.trimToEmpty(raw).toLowerCase();
        if (EXPAND_ADJACENT.equals(value) || EXPAND_SECTION.equals(value)) {
            return value;
        }
        return EXPAND_NONE;
    }

    /** 渲染检索结果：每条必带出处（来源 § 章节 # 切片区间） */
    private String render(String query, List<String> scope, String expand, JSONObject response, AiToolContext context) {
        JSONArray results = response == null ? null : response.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            return "未检索到与「" + query + "」相关的内容（检索范围：" + scopeLabel(scope) + "）。" + "如果没有别的线索，请如实告诉用户知识库里没有相关内容，不要自行补充。";
        }

        int budget = budgetOf(context);
        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索「").append(query).append("」命中 ").append(results.size()).append(" 条");
        Long took = response.getLong("took_ms");
        if (took != null) {
            sb.append("（耗时 ").append(took).append(" ms");
            if (!EXPAND_NONE.equals(expand)) {
                sb.append("，扩展模式 ").append(expand);
            }
            sb.append("）");
        }
        sb.append("，检索范围：").append(scopeLabel(scope)).append("\n");

        int appended = 0;
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String block = renderItem(i + 1, item);
            if (block == null) {
                continue;
            }
            if (sb.length() + block.length() > budget) {
                sb.append("\n（结果已达本次预算上限，剩余 ").append(results.size() - appended).append(" 条未展示；可缩小 query 或调小 top_k）\n");
                break;
            }
            sb.append(block);
            appended++;
        }
        if (appended == 0) {
            // 预算被单条撑爆：至少给出第一段，避免"命中了却什么都没返回"
            sb.append(renderItem(1, results.getJSONObject(0)));
        }
        return sb.toString();
    }

    private String renderItem(int index, JSONObject item) {
        if (item == null) {
            return null;
        }
        String text = StrUtil.trimToEmpty(item.getStr("text"));
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String source = StrUtil.nullToEmpty(item.getStr("source"));
        String section = StrUtil.nullToEmpty(item.getStr("section"));
        Integer chunkNo = item.getInt("chunk_no");
        String range = chunkRange(item, chunkNo);

        StringBuilder sb = new StringBuilder();
        sb.append("\n【").append(index).append("】");
        sb.append(StrUtil.isBlank(source) ? "（未命名来源）" : source);
        if (StrUtil.isNotBlank(section)) {
            sb.append(" § ").append(section);
        }
        if (StrUtil.isNotBlank(range)) {
            sb.append(" #").append(range);
        }
        Object score = item.get("score");
        if (score != null) {
            sb.append("（相似度 ").append(score).append("）");
        }
        sb.append("\n").append(text);
        if (!text.endsWith("\n")) {
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 切片区间：扩展模式下是 {@code [lo,hi]}，未命中时回落到单个 chunk_no */
    private String chunkRange(JSONObject item, Integer chunkNo) {
        JSONArray range = item.getJSONArray("chunk_range");
        if (range != null && range.size() == 2) {
            Object lo = range.get(0);
            Object hi = range.get(1);
            if (lo != null && hi != null) {
                return String.valueOf(lo).equals(String.valueOf(hi)) ? String.valueOf(lo) : lo + "-" + hi;
            }
        }
        return chunkNo == null ? "" : String.valueOf(chunkNo);
    }

    private String scopeLabel(List<String> scope) {
        if (CollUtil.isEmpty(scope)) {
            return "（无）";
        }
        return String.join("、", scope);
    }

    /** 本次允许的最大字符数：工具结果上限与知识库独立预算取小 */
    private int budgetOf(AiToolContext context) {
        int toolLimit = context == null ? 0 : context.getMaxResultChars();
        int kbLimit = SystemConfiguration.getInstances().getKbMaxChars();
        if (toolLimit <= 0) {
            return kbLimit > 0 ? kbLimit : 4000;
        }
        return kbLimit > 0 ? Math.min(toolLimit, kbLimit) : toolLimit;
    }
}
