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

package org.dinky.ai.semantic;

import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.resource.semantic.SemanticHdfsClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SemanticAssetProvider} 实现：<b>索引优先 → YAML 兜底 → 降级摘要</b>。
 *
 * <p><b>三档读取</b>（对应计划 §4.6 的降级表）：
 * <ol>
 *   <li>{@code index/} 齐 + {@code index_state=ready} → 读切片，口径/度量/前缀维度齐全；</li>
 *   <li>索引缺失或 {@code index_pending} → 直读发布副本 YAML，按"简版"摘录并标注；</li>
 *   <li>项目从未发布 → 明确报"尚未发布"，<b>不猜</b>；HDFS 异常 → 只记日志并返回可读说明。</li>
 * </ol>
 *
 * <p><b>为什么不用缓存</b>：`_published` 不可变（版本目录一旦生成不再改），按版本号做 key 的
 * 缓存本可安全，但一个进程内可能同时有多个版本（滚动发布），失效策略写错就会"引用旧口径"
 * ——本批先不引入，等实测出性能问题再加（语义索引单文件 1~3 KB）。
 *
 * @since 2026/10/22
 */
@Slf4j
@Component
public class SemanticAssetProviderImpl implements SemanticAssetProvider {

    /** 维度/度量逐条渲染上限：防止单表几百列把预算一次吃光 */
    private static final int MAX_ITEMS_PER_FIELD = 40;

    private static final int ONE_LINER_MAX = 120;

    private static final int SHA_SHORT = 8;

    private static final String INDEX_PROJECT = "index/project.json";

    private static final String INDEX_TABLE_DIR = "index/tables/";

    private static final String INDEX_METRIC_DIR = "index/metrics/";

    private static final String INDEX_SUFFIX = ".json";

    private static final String META_FILE = "meta.json";

    private static final String YML = ".yml";

    private static final String YAML = ".yaml";

    private final SemanticHdfsClient client = SemanticHdfsClient.getInstance();

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------ 枚举 / 解析

    @Override
    public List<SemanticAssetRef> enumerate(String project) {
        String p = client.normalizeProject(project);
        return enumerateRefs(p, requireVersion(p));
    }

    @Override
    public SemanticAssetRef resolve(String handlePath) {
        String raw = StrUtil.trimToEmpty(handlePath).replace('\\', '/');
        while (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (StrUtil.isBlank(raw)) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        String[] seg = raw.split("/", -1);
        // 只接受 1/2/3 段（3 段时第 2 段必须是 table|metric）；更深的形态是文件路径，一律拒绝
        if (seg.length == 0 || seg.length > 3) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        String project = client.normalizeProject(seg[0]);
        String version = requireVersion(project);
        if (seg.length == 1) {
            SemanticAssetRef ref = SemanticAssetRef.project(project, version);
            ref.setPath(INDEX_PROJECT);
            return ref;
        }

        String forcedType = null;
        String name;
        if (seg.length == 3) {
            String kind = seg[1].toLowerCase(Locale.ROOT);
            if (!MentionKeyword.TABLE.equals(kind) && !MentionKeyword.METRIC.equals(kind)) {
                // 形如 @semantic/<p>/metrics/day_count.yml —— 只接受资产名，不接受文件路径（§4.3-4）
                throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
            }
            forcedType = MentionKeyword.TABLE.equals(kind) ? SemanticAssetRef.TYPE_TABLE : SemanticAssetRef.TYPE_METRIC;
            name = seg[2];
        } else {
            name = seg[1];
        }
        if (StrUtil.isBlank(name)) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }

        List<SemanticAssetRef> all = enumerateRefs(project, version);
        String wanted = name;
        String wantedType = forcedType;
        List<SemanticAssetRef> matches = all.stream()
                .filter(r -> wanted.equalsIgnoreCase(StrUtil.nullToEmpty(r.getName())))
                .filter(r -> wantedType == null || wantedType.equals(r.getType()))
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            // 索引降级（发布副本缺 index/ 或 index_state 未就绪）时根本读不到资产名清单，
            // 此时"未找到"是假象：YAML 里明明有该资产。按 U25 判据①「仍能回答」，
            // 退化为"整项目 YAML 原文注入 + 标注未就绪"，而不是报未找到。
            SemanticAssetRef degraded = degradedProjectRef(project, version, all);
            if (degraded != null) {
                log.info("semantic index degraded, fallback to project YAML: project={}, handle={}", project, raw);
                return degraded;
            }
            throw new BusException(notFoundMessage(raw, all));
        }
        if (matches.size() > 1) {
            // D20：同名（TABLE 与 METRIC 都有）→ 报歧义不猜，文案直接给长式写法
            throw new BusException(Status.SEMANTIC_HANDLE_AMBIGUOUS, name);
        }
        return matches.get(0);
    }

    /** 句柄中的类型关键字（不是 {@link org.dinky.ai.mention.MentionType} 的值，仅句柄语法糖） */
    private static final class MentionKeyword {

        private static final String TABLE = "table";

        private static final String METRIC = "metric";
    }

    private String notFoundMessage(String raw, List<SemanticAssetRef> all) {
        List<String> candidates =
                all.stream().map(SemanticAssetRef::getName).distinct().limit(3).collect(Collectors.toList());
        return "未找到语义资产 " + raw
                + (candidates.isEmpty() ? "（该项目下没有可引用资产）" : "（该项目的候选：" + String.join("、", candidates) + "）");
    }

    /**
     * 索引降级时把"未命中资产"转成**项目级降级引用**（整项目 YAML 原文注入）。
     *
     * <p>索引正常时返回 {@code null}，保持"未找到 + 给候选"的原有语义——不能因为降级
     * 就静默把一个错误名字当成"整个项目"，那会掩盖用户的拼写错误。
     */
    private SemanticAssetRef degradedProjectRef(String project, String version, List<SemanticAssetRef> all) {
        if (all.isEmpty() || all.stream().noneMatch(SemanticAssetRef::isDegraded)) {
            return null;
        }
        SemanticAssetRef ref = SemanticAssetRef.project(project, version);
        ref.setPath(INDEX_PROJECT);
        ref.setDegraded(true);
        return ref;
    }

    private String requireVersion(String project) {
        String version = client.latestPublishedVersion(project);
        if (StrUtil.isBlank(version)) {
            throw new BusException(Status.SEMANTIC_PUBLISHED_NOT_FOUND, project);
        }
        return version;
    }

    /** 索引优先枚举；索引缺失/未就绪时退化为"扫发布副本 YAML"（只有文件名，无口径） */
    private List<SemanticAssetRef> enumerateRefs(String project, String version) {
        JsonNode projectIndex = readJson(project, version, INDEX_PROJECT);
        if (projectIndex != null
                && projectIndex.path("names").isArray()
                && projectIndex.path("names").size() > 0) {
            Map<String, String> oneLiners = new LinkedHashMap<>();
            Map<String, String> sourceYamls = new LinkedHashMap<>();
            collectProjectEntries(projectIndex.path("tables"), SemanticAssetRef.TYPE_TABLE, oneLiners, sourceYamls);
            collectProjectEntries(projectIndex.path("metrics"), SemanticAssetRef.TYPE_METRIC, oneLiners, sourceYamls);

            List<SemanticAssetRef> refs = new ArrayList<>();
            for (JsonNode node : projectIndex.path("names")) {
                String type = SemanticAssetRef.TYPE_METRIC.equalsIgnoreCase(
                                node.path("type").asText())
                        ? SemanticAssetRef.TYPE_METRIC
                        : SemanticAssetRef.TYPE_TABLE;
                String name = node.path("name").asText("");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                String key = type + "|" + name;
                SemanticAssetRef ref = SemanticAssetRef.of(type, project, name, version, indexPath(type, name));
                ref.setOneLiner(oneLiners.get(key));
                ref.setSourceYaml(sourceYamls.get(key));
                refs.add(ref);
            }
            return refs;
        }

        // 降级：扫 YAML 文件（文件名当资产名，明确标注无口径）。
        // 优先 metrics/ 目录：dbt 语义模型与指标基本都在这里；把 dbt_project.yml 之类的
        // 工程配置也算成"可引用资产"只会污染候选（U25 实测：候选项出现了 dbt_project）。
        List<String> yamlFiles = new ArrayList<>();
        List<String> otherYaml = new ArrayList<>();
        for (String rel : client.listPublishedFiles(project, version)) {
            String lower = rel.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(YML) && !lower.endsWith(YAML)) {
                continue;
            }
            if (lower.startsWith("metrics/")) {
                yamlFiles.add(rel);
            } else {
                otherYaml.add(rel);
            }
        }
        if (yamlFiles.isEmpty()) {
            yamlFiles.addAll(otherYaml);
        }
        List<SemanticAssetRef> refs = new ArrayList<>();
        for (String rel : yamlFiles) {
            String lower = rel.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(YML) && !lower.endsWith(YAML)) {
                continue;
            }
            SemanticAssetRef ref =
                    SemanticAssetRef.of(SemanticAssetRef.TYPE_TABLE, project, baseName(rel), version, rel);
            ref.setSourceYaml(rel);
            ref.setDegraded(true);
            refs.add(ref);
        }
        if (!refs.isEmpty()) {
            log.info(
                    "semantic index missing, degraded to YAML listing: project={}, version={}, files={}",
                    project,
                    version,
                    refs.size());
        }
        return refs;
    }

    private void collectProjectEntries(
            JsonNode array, String type, Map<String, String> oneLiners, Map<String, String> sourceYamls) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String name = item.path("name").asText("");
            if (StrUtil.isBlank(name)) {
                continue;
            }
            String key = type + "|" + name;
            oneLiners.put(key, item.path("one_liner").asText(""));
            sourceYamls.put(key, item.path("path").asText(""));
        }
    }

    private String indexPath(String type, String name) {
        return (SemanticAssetRef.TYPE_METRIC.equals(type) ? INDEX_METRIC_DIR : INDEX_TABLE_DIR) + name + INDEX_SUFFIX;
    }

    private String baseName(String rel) {
        String name = rel;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {YML, YAML}) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public String read(SemanticAssetRef ref, int maxChars) {
        if (ref == null) {
            return "";
        }
        int budget = maxChars > 0 ? maxChars : PROJECT_LIST_MAX_CHARS;
        String text;
        try {
            text = ref.isProject() ? renderProject(ref, budget) : renderAsset(ref, budget);
        } catch (BusException e) {
            // 语义资产问题不应让对话失败：转成一行可读说明交给模型（§4.6）
            log.warn("semantic asset render rejected: {} - {}", ref.sourceUri(), e.getMessage());
            return "- " + StrUtil.nullToEmpty(e.getMessage()) + "\n";
        } catch (RuntimeException e) {
            log.warn("semantic asset render failed: {}", ref.sourceUri(), e);
            return "- 语义资产读取失败（" + ref.sourceUri() + "）：" + StrUtil.nullToEmpty(e.getMessage()) + "\n";
        }
        return truncate(text, budget);
    }

    /** 项目级清单：表名 + 指标名 + 一句话口径（**不含**完整定义，D18 预算 6000） */
    private String renderProject(SemanticAssetRef ref, int budget) {
        if (ref.isDegraded()) {
            // 索引缺失/未就绪：按 U25 判据①「仍能回答」——直接给 YAML 原文，不拒答、不臆测
            return renderDegradedProject(ref, budget);
        }
        List<SemanticAssetRef> refs = enumerateRefs(ref.getProject(), ref.getVersion());
        if (refs.isEmpty() || refs.stream().allMatch(SemanticAssetRef::isDegraded)) {
            // 索引缺失/未就绪：给 YAML 原文比给"文件名清单"有用得多（U25 判据①）
            return renderDegradedProject(ref, budget);
        }
        Meta meta = meta(ref.getProject());
        StringBuilder sb = new StringBuilder(1024);
        sb.append("## 语义资产（@semantic 引用，口径以此为准）\n");
        sb.append("### 项目 ").append(ref.getProject()).append("（published ").append(ref.getVersion());
        if (meta != null && StrUtil.isNotBlank(meta.getIndexState())) {
            sb.append("，index ").append(meta.getIndexState());
        }
        sb.append("）\n");
        appendSection(sb, refs, SemanticAssetRef.TYPE_METRIC, "指标", budget);
        appendSection(sb, refs, SemanticAssetRef.TYPE_TABLE, "表", budget);
        sb.append("- 精确引用单个资产：`@semantic/").append(ref.getProject()).append("/<名>`\n");
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, List<SemanticAssetRef> refs, String type, String label, int budget) {
        List<SemanticAssetRef> items =
                refs.stream().filter(r -> type.equals(r.getType())).collect(Collectors.toList());
        if (items.isEmpty()) {
            return;
        }
        sb.append("#### ").append(label).append("（").append(items.size()).append("）\n");
        int shown = 0;
        for (SemanticAssetRef item : items) {
            if (sb.length() >= budget) {
                sb.append("- …（清单超出预算，已截断 ")
                        .append(items.size() - shown)
                        .append(" 项；可用 `@semantic/")
                        .append(item.getProject())
                        .append("/<名>` 精确引用）\n");
                return;
            }
            sb.append("- ").append(label).append(" ").append(item.getName());
            if (StrUtil.isNotBlank(item.getOneLiner())) {
                sb.append("：").append(oneLiner(item.getOneLiner()));
            }
            if (StrUtil.isNotBlank(item.getSourceYaml())) {
                sb.append("（").append(item.getSourceYaml()).append("）");
            }
            sb.append("\n");
            shown++;
        }
    }

    private String oneLiner(String text) {
        String value = StrUtil.nullToEmpty(text).trim();
        int nl = value.indexOf('\n');
        if (nl >= 0) {
            value = value.substring(0, nl).trim();
        }
        return value.length() > ONE_LINER_MAX ? value.substring(0, ONE_LINER_MAX) + "…" : value;
    }

    /** 单资产渲染：索引切片全文的结构化投影；切片缺失则退化为 YAML 原文摘录 */
    private String renderAsset(SemanticAssetRef ref, int budget) {
        String slicePath = ref.getPath();
        JsonNode slice = StrUtil.isBlank(slicePath) ? null : readJson(ref.getProject(), ref.getVersion(), slicePath);
        if (slice == null) {
            return renderYamlFallback(ref, budget);
        }
        String sha = slice.path("yaml_sha256").asText("");
        String sourceYaml = slice.path("source_yaml").asText("");
        StringBuilder sb = new StringBuilder(768);
        if (SemanticAssetRef.TYPE_METRIC.equals(ref.getType())) {
            appendMetric(sb, slice, ref);
        } else {
            appendTable(sb, slice, ref);
        }
        sb.append("- 来源：")
                .append(ref.getProject())
                .append(" / ")
                .append(ref.getVersion())
                .append(" / ")
                .append(StrUtil.isNotBlank(sourceYaml) ? sourceYaml : slicePath);
        if (StrUtil.isNotBlank(sha)) {
            sb.append("（yaml_sha256 ").append(shortSha(sha)).append("）");
        }
        sb.append("  ← ")
                .append("semantic://")
                .append(ref.getProject())
                .append("/")
                .append(ref.getVersion())
                .append("/")
                .append(slicePath)
                .append("\n");
        return sb.toString();
    }

    private void appendMetric(StringBuilder sb, JsonNode slice, SemanticAssetRef ref) {
        sb.append("## 指标 ").append(text(slice, "name", ref.getName())).append("\n");
        appendIfPresent(sb, "一句话", text(slice, "one_liner", ""));
        appendIfPresent(sb, "类型", text(slice, "type", ""));
        String expr = text(slice, "expr", "");
        String measure = text(slice, "measure", "");
        String agg = text(slice, "agg", "");
        StringBuilder formula = new StringBuilder();
        if (StrUtil.isNotBlank(agg)) {
            formula.append(agg).append("(");
        }
        formula.append(StrUtil.isNotBlank(expr) ? expr : "(见 measure 定义)");
        if (StrUtil.isNotBlank(agg)) {
            formula.append(")");
        }
        if (StrUtil.isNotBlank(measure)) {
            formula.append("；度量 ").append(measure).append(StrUtil.isNotBlank(agg) ? "（" + agg + "）" : "");
        }
        appendIfPresent(sb, "口径", formula.toString());
        appendIfPresent(sb, "时间维度", text(slice, "time_dim", ""));
        appendIfPresent(sb, "所属语义模型", text(slice, "semantic_model", ""));
        appendIfPresent(sb, "物理表", text(slice, "table", ""));
        appendList(sb, "可用维度（**取数请用前缀名**）", slice.path("dims_prefixed"));
        if (slice.path("dims_prefixed").isArray()
                && slice.path("dims_prefixed").size() == 0
                && slice.path("dims").isArray()) {
            appendList(sb, "可用维度", slice.path("dims"));
        }
    }

    private void appendTable(StringBuilder sb, JsonNode slice, SemanticAssetRef ref) {
        sb.append("## 表 ").append(text(slice, "view_model", ref.getName())).append("\n");
        appendIfPresent(sb, "一句话", text(slice, "description", ""));
        String relation = text(slice, "relation", "");
        String physical = text(slice, "schema", "") + "." + text(slice, "table", "");
        appendIfPresent(sb, "物理落点", StrUtil.isNotBlank(relation) ? relation : physical);
        List<String> entities = new ArrayList<>();
        for (JsonNode ent : slice.path("entities")) {
            String name = ent.path("name").asText("");
            if (StrUtil.isNotBlank(name)) {
                entities.add(name + "(" + ent.path("type").asText("") + ")");
            }
        }
        appendIfPresent(sb, "实体", String.join("、", entities));
        appendList(sb, "时间维度", slice.path("time_dims"));
        appendList(sb, "可用维度（**取数请用前缀名**）", slice.path("dims_prefixed"));
        List<String> measures = new ArrayList<>();
        for (JsonNode measure : slice.path("measures")) {
            String name = measure.path("name").asText("");
            if (StrUtil.isBlank(name)) {
                continue;
            }
            measures.add(name + "(" + measure.path("agg").asText("") + " = "
                    + measure.path("expr").asText("") + ")");
        }
        appendIfPresent(sb, "度量", String.join("、", limit(measures)));
        List<String> metrics = new ArrayList<>();
        for (JsonNode metric : slice.path("metrics")) {
            String name = metric.asText("");
            if (StrUtil.isNotBlank(name)) {
                metrics.add(name);
            }
        }
        appendIfPresent(sb, "关联指标", String.join("、", limit(metrics)));
        appendList(sb, "已排除列（发布期剔除，勿引用）", slice.path("excluded_columns"));
    }

    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (StrUtil.isNotBlank(value)) {
            sb.append("- ").append(label).append("：").append(value).append("\n");
        }
    }

    private void appendList(StringBuilder sb, String label, JsonNode array) {
        if (array == null || !array.isArray() || array.size() == 0) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            items.add(item.asText(""));
        }
        sb.append("- ").append(label).append("：").append(String.join("、", limit(items)));
        if (array.size() > MAX_ITEMS_PER_FIELD) {
            sb.append(" …（共 ").append(array.size()).append(" 项）");
        }
        sb.append("\n");
    }

    private List<String> limit(List<String> items) {
        List<String> cleaned = items.stream().filter(StrUtil::isNotBlank).collect(Collectors.toList());
        return cleaned.size() > MAX_ITEMS_PER_FIELD ? cleaned.subList(0, MAX_ITEMS_PER_FIELD) : cleaned;
    }

    /**
     * 项目级降级渲染：没有索引时读不到资产名，只能把该项目**指标/语义模型相关 YAML** 原文摘录进来。
     *
     * <p>{@code metrics/} 目录优先（dbt 项目的指标与语义模型通常放这里），每文件限额，总量受
     * {@code budget} 约束；末尾显式提示"请勿臆测未出现的内容"，避免模型拿半截 YAML 编口径。
     */
    private String renderDegradedProject(SemanticAssetRef ref, int budget) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("## 语义资产 ")
                .append(ref.getProject())
                .append("（published ")
                .append(ref.getVersion())
                .append("，索引未就绪）\n");
        sb.append("- ")
                .append(StrUtil.format(Status.SEMANTIC_INDEX_PENDING_INFO.getMessage(), ref.getProject()))
                .append("\n");
        List<String> yamls = new ArrayList<>();
        for (String rel : client.listPublishedFiles(ref.getProject(), ref.getVersion())) {
            String lower = rel.toLowerCase(Locale.ROOT);
            if (lower.endsWith(YML) || lower.endsWith(YAML)) {
                yamls.add(rel);
            }
        }
        yamls.sort(Comparator.comparing(
                (String rel) -> !rel.toLowerCase(Locale.ROOT).startsWith("metrics/")));
        int perFile = Math.max(256, budget / 4);
        for (String rel : yamls) {
            if (sb.length() >= budget - 256) {
                break;
            }
            try {
                String content = client.readPublished(ref.getProject(), ref.getVersion(), rel);
                String body = content.length() > perFile ? content.substring(0, perFile) + "\n…（已截断）" : content;
                sb.append("### ").append(rel).append("\n```yaml\n").append(body).append("\n```\n");
            } catch (RuntimeException e) {
                log.warn("read published yaml failed: {}/{}/{}", ref.getProject(), ref.getVersion(), rel, e);
            }
        }
        sb.append("- 说明：索引未就绪，无法按名精确取指标口径；以上为 YAML 原文，未出现的内容不要臆测。\n");
        return sb.toString();
    }

    /** 索引缺失时的降级：读发布副本 YAML 原文，截断后包成代码块并说明"口径未就绪" */
    private String renderYamlFallback(SemanticAssetRef ref, int budget) {
        String rel = StrUtil.isNotBlank(ref.getSourceYaml()) ? ref.getSourceYaml() : ref.getPath();
        StringBuilder sb = new StringBuilder(512);
        sb.append("## 语义资产 ").append(StrUtil.nullToEmpty(ref.getName())).append("\n");
        sb.append("- ")
                .append(StrUtil.format(Status.SEMANTIC_INDEX_PENDING_INFO.getMessage(), ref.getProject()))
                .append("\n");
        if (StrUtil.isBlank(rel)) {
            sb.append("- 无法定位该资产的 YAML 源文件（索引未就绪且未记录来源）\n");
            return sb.toString();
        }
        try {
            String yaml = client.readPublished(ref.getProject(), ref.getVersion(), rel);
            int bodyBudget = Math.max(budget - sb.length() - 64, 256);
            String body = yaml.length() > bodyBudget ? yaml.substring(0, bodyBudget) + "\n…（已截断）" : yaml;
            sb.append("- 来源：")
                    .append(ref.getProject())
                    .append(" / ")
                    .append(ref.getVersion())
                    .append(" / ")
                    .append(rel)
                    .append("（索引未就绪，按 YAML 原文摘录）\n");
            sb.append("```yaml\n").append(body).append("\n```\n");
        } catch (RuntimeException e) {
            log.warn("read published yaml failed: {}/{}/{}", ref.getProject(), ref.getVersion(), rel, e);
            sb.append("- 读取 YAML 失败：")
                    .append(StrUtil.nullToEmpty(e.getMessage()))
                    .append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 元信息

    @Override
    public Meta meta(String project) {
        String p = client.normalizeProject(project);
        String version = client.latestPublishedVersion(p);
        if (StrUtil.isBlank(version)) {
            return null;
        }
        Meta meta = new Meta();
        meta.setVersion(version);
        JsonNode node = readJson(p, version, META_FILE);
        if (node == null) {
            // 发布版本连 meta.json 都没有：说明版本不完整，按"未就绪"处理（消费端会降级）
            meta.setIndexState("unknown");
            return meta;
        }
        meta.setPublishedAt(node.path("published_at").asText(""));
        meta.setIndexState(node.path("index_state").asText(""));
        meta.setSourceSha256(node.path("source_sha256").asText(""));
        meta.setIndexFiles(node.path("index_files").asInt(0));
        meta.setIndexBuiltAt(node.path("index_built_at").asText(""));
        return meta;
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode readJson(String project, String version, String rel) {
        if (StrUtil.isBlank(rel)) {
            return null;
        }
        try {
            if (!client.publishedExists(project, version, rel)) {
                return null;
            }
            return mapper.readTree(client.readPublished(project, version, rel));
        } catch (RuntimeException e) {
            log.warn("read semantic published json failed: {}/{}/{}", project, version, rel, e);
            return null;
        } catch (Exception e) {
            log.warn("parse semantic published json failed: {}/{}/{}", project, version, rel, e);
            return null;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return StrUtil.isNotBlank(value) ? value : StrUtil.nullToEmpty(fallback);
    }

    private String shortSha(String sha) {
        String value = StrUtil.nullToEmpty(sha);
        return value.length() > SHA_SHORT ? value.substring(0, SHA_SHORT) : value;
    }

    private String truncate(String text, int budget) {
        if (text == null || text.length() <= budget) {
            return StrUtil.nullToEmpty(text);
        }
        return text.substring(0, budget) + "\n…（语义上下文超出预算 " + budget + " 字符，已截断）\n";
    }
}
