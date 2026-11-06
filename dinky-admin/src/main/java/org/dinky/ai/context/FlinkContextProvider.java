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

package org.dinky.ai.context;

import org.dinky.ai.fewshot.SimilarTasksQuery;
import org.dinky.ai.fewshot.SimilarTasksService;
import org.dinky.ai.kafka.KafkaTopicCatalog;
import org.dinky.ai.rule.FlinkSqlRuleEngine;
import org.dinky.config.Dialect;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.StudioMetaStoreDTO;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.ext.ConfigItem;
import org.dinky.data.model.Catalog;
import org.dinky.data.model.ClusterInstance;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.FragmentVariable;
import org.dinky.data.model.Schema;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.ClusterInstanceService;
import org.dinky.service.DataBaseService;
import org.dinky.service.FragmentVariableService;
import org.dinky.service.StudioService;
import org.dinky.service.TaskService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FlinkSQL 上下文装配器（AI Chat P0 · 只读）。
 *
 * <p><b>设计要点</b>：FlinkSQL 作业的上下文<b>不是</b>「一个 JDBC 数据源的 schema」，而是五类信息的组合：
 *
 * <ol>
 *   <li><b>作业绑定</b>——运行模式、集群、并行度、env、自定义配置、<b>表名口径</b>；
 *   <li><b>Flink Catalog</b>——经 env 回放枚举出的 catalog / 库（/默认库的表）；
 *   <li><b>外部资源清单</b>——Kafka 接入地址（来自系统配置）、Doris 数据源的库清单；
 *   <li><b>规则红线</b>——参数放置、命名唯一性、Doris 写入约束、时区口径等<b>静态约定</b>；
 *   <li><b>命名名册</b>——已占用的 pipeline.name / group.id / label-prefix（见 {@link NameRegistryService}）。
 * </ol>
 *
 * <p>因此不能复用 {@code {{schema}}}（它由 {@code databaseId} 驱动，而 FlinkSQL 任务没有数据源），
 * 而由本类产出独立的 {@code {{flinkContext}}} 区块。<b>只对 {@link Dialect#FLINK_SQL} 生效</b>，
 * 其它方言一律返回空串 —— 对既有 Sql / SparkSQL 链路零影响。
 *
 * <p><b>P0 只读</b>：不生成 SQL、不写作业、不提交任务。catalog 枚举复用 Studio（Catalog 面板）同一条
 * 链路（{@code buildEnvSql} + {@code FlinkTableMetadataUtil}），既保证口径一致，又<strong>共享其 JobManager 缓存</strong>。
 *
 * <p><b>不注入机密</b>：自定义配置与 Kafka 配置中的口令类字段一律掩码后输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlinkContextProvider {

    /** 最多展开库清单的数据源个数（每个数据源查一次库清单，连库有成本，故封顶）。 */
    private static final int MAX_ENRICHED_SOURCES = 3;

    /** 单段清单最多列出的条目数（库 / 表）。 */
    private static final int MAX_ITEMS_PER_LINE = 60;

    private static final String DEFAULT_CATALOG = "default_catalog";

    private static final String DEFAULT_DATABASE = "default_database";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;

    private final ClusterInstanceService clusterInstanceService;

    private final StudioService studioService;

    private final DataBaseService dataBaseService;

    /** P0-b（2026-11-03）：全局变量清单——只取名字，用于堵住"臆造 ${xxx} 占位符" */
    private final FragmentVariableService fragmentVariableService;

    private final NameRegistryService nameRegistryService;

    /** M1-A：规则库引擎（渲染 prompt 红线；S8 起取代原 RULES 常量） */
    private final FlinkSqlRuleEngine ruleEngine;

    /** M1：平台运行画像（人工维护的链路与 topic 说明） */
    private final PlatformProfileLoader platformProfileLoader;

    /** P0：集群能力画像（**部署脚本探测生成**：Flink 版本 + 真实可用的 connector） */
    private final FlinkCapabilitiesLoader flinkCapabilitiesLoader;

    /**
     * P0（2026-11-04）：few-shot 召回。
     *
     * <p><b>为什么必须进 prompt</b>：复盘 M1-01 六轮失败得出的结论不是"事实给少了"——
     * 白名单、真实 Kafka 地址、默认 sink 的 4 张表，模型全都看到了，照样编出
     * {@code connector=hive} / {@code ${kafka.bootstrap.servers}} / 不存在的 topic 与表。
     * 根因是它每次都在<b>从零构造整个作业结构</b>，没有可参照的成品。
     * 给一段本集群真实可跑的作业，任务就从"生成"退化成"改参数"，错误面小一个数量级。
     */
    private final SimilarTasksService similarTasksService;

    /** P1（2026-11-04）：Kafka topic 实时目录（AdminClient 直连 29092，与 Flink 用同一 listener） */
    private final KafkaTopicCatalog kafkaTopicCatalog;

    /**
     * 判断该方言是否由本 provider 提供上下文（当前只有 FlinkSQL）。
     *
     * <p>供调用方（{@code AiChatServiceImpl}）在「不引入 {@link Dialect} 依赖」的前提下分流文案。
     *
     * @param dialect 作业方言
     * @return true 表示由 FlinkContextProvider 产出 {@code {{flinkContext}}}
     */
    public boolean supports(String dialect) {
        return dialect != null && Dialect.FLINK_SQL.getValue().equalsIgnoreCase(dialect);
    }

    /**
     * 组装 FlinkSQL 上下文（L0 区块）。
     *
     * <p><b>2026-11-03 起不再以 taskId 作为开关</b>。原实现在 {@code taskId == null} 时整块返回空串，
     * 而"新建 FlinkSQL 作业"的正是一个没有 taskId 的 tab —— 于是「集群能力 / 平台默认 / 链路约定 /
     * 规则红线 / 输出协议」这些**与具体作业无关的框架事实**被一并吞掉，模型只能凭通用知识发挥。
     * UAT 实测后果：编出 {@code 'connector'='doris'} 与 {@code ${doris.fenodes}}、把
     * {@code execution.checkpointing.min-pause} 写进 SET、用 {@code debezium-json} 解析 canal 流，
     * 并且拒绝按四段式协议补发围栏（它确实没看到协议）。
     *
     * <p>现在的切分口径：<b>方言是 FlinkSQL 就注入框架事实</b>；
     * 作业绑定相关段落（当前作业 / Catalog）仅在能取到作业时才有内容，
     * 其中「当前作业」段落即使未绑定也会写一段"必须先确认作业名/并行度/env"的约束，
     * 用来兜住"信息缺失却不提问"的失败模式。
     *
     * @param dialect 请求方言（为空时回退到作业方言）
     * @param taskId  当前作业 id（可为 null：新建 / 未保存的 tab）
     * @return 装配结果（文本 + 段落清单 + 绑定/截断标记）；非 FlinkSQL 方言返回 {@link FlinkContext#empty()}
     */
    public FlinkContext build(String dialect, Integer taskId) {
        return build(dialect, taskId, null);
    }

    /**
     * 带用户问题的装配（P0，2026-11-04）：多出的 {@code question} 用于 few-shot 召回打分。
     *
     * <p>召回是"检索"，只按关键词/表名/topic 打分，不调模型；{@code question} 为空时该段不输出，
     * 行为与旧的两参版本完全一致。
     *
     * @param question 用户本轮提问（可为空）
     */
    public FlinkContext build(String dialect, Integer taskId, String question) {
        TaskDTO task = loadTask(taskId);
        String effectiveDialect = StrUtil.isBlank(dialect) ? (task == null ? null : task.getDialect()) : dialect;
        if (!supports(effectiveDialect)) {
            return FlinkContext.empty();
        }
        // 作业方言与请求方言不一致（例如在 FlinkSQL tab 里问一个 Sql 作业）时，不注入作业细节，
        // 但框架事实照给——避免"问错 tab"直接退化成空上下文。
        final TaskDTO boundTask = (task != null && supports(task.getDialect())) ? task : null;

        String snapshot = LocalDateTime.now().format(TS);
        List<FlinkContext.Section> sections = new ArrayList<>();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("## FlinkSQL 运行环境（AI 自动注入，仅供本次回答使用）\n");
        // 顺序即 prompt 里的阅读顺序：先"你是谁/在哪个作业"，再"能干什么"，最后"红线与协议"
        sb.append(render(sections, "job", "当前作业绑定", body -> appendJobBinding(body, boundTask, snapshot)));
        sb.append(render(sections, "capabilities", "L1 集群能力", this::appendCapabilities));
        if (boundTask != null) {
            sb.append(render(sections, "catalog", "Flink Catalog", body -> appendCatalogSection(body, boundTask)));
        }
        sb.append(render(sections, "resources", "外部资源清单", this::appendExternalResources));
        sb.append(render(sections, "defaults", "L2 平台默认", this::appendPlatformDefaults));
        sb.append(render(sections, "profile", "平台链路约定", this::appendProfile));
        sb.append(render(sections, "rules", "规则红线", this::appendRules));
        // 参考作业放在"规则红线"之后、"输出协议"之前：模型先看"不能怎么写"，
        // 再看"本集群实际怎么写的"，最后才看"要怎么交付"。
        sb.append(render(sections, "similar", "参考作业（few-shot）", body -> appendSimilarTasks(body, boundTask, question)));
        sb.append(render(sections, "protocol", "输出协议", this::appendProtocol));
        sb.append(render(sections, "review", "审核协议", this::appendReviewProtocol));
        sb.append(render(sections, "registry", "已占用名册", this::appendRegistry));

        String text = sb.toString();
        boolean truncated = false;
        int max = SystemConfiguration.getInstances().getLlmFlinkContextMaxChars();
        if (max > 0 && text.length() > max) {
            text = text.substring(0, max) + "\n... (FlinkSQL 上下文超预算，已截断)\n";
            truncated = true;
        }
        return new FlinkContext(text, boundTask != null, truncated, sections);
    }

    /**
     * 兼容旧调用：只要文本（非 FlinkSQL 方言返回空串）。
     *
     * <p>保留它是因为"有没有上下文"这个判断在多处复用；需要段落清单 / 绑定状态的调用方
     * 请直接用 {@link #build(String, Integer)}。
     */
    public String buildText(String dialect, Integer taskId) {
        return build(dialect, taskId).getText();
    }

    /** 按 id 取作业；失败一律当"未绑定"处理（宁可少给作业细节，也不要因为一次查询异常吞掉整块框架事实） */
    private TaskDTO loadTask(Integer taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            return taskService.getTaskInfoById(taskId);
        } catch (Exception e) {
            log.warn("FlinkContext: load task {} failed", taskId, e);
            return null;
        }
    }

    /**
     * 单段装配：每段独立 catch。
     *
     * <p>过去整块上下文共用一个 StringBuilder + 调用方 try/catch，任何一段抛异常都会让**整块**上下文
     * 变空（或退化成只有一句降级文案）。分段之后，掉一段只丢一段，且降级原因能落到段落清单里，
     * 由 {@code GET /api/aiChat/context} 直接看到。
     *
     * @return 该段正文（失败为空串）
     */
    private String render(
            List<FlinkContext.Section> sections, String id, String title, Consumer<StringBuilder> writer) {
        StringBuilder body = new StringBuilder(512);
        String degraded = null;
        try {
            writer.accept(body);
        } catch (Exception e) {
            log.warn("FlinkContext: section {} failed", id, e);
            degraded = trim(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 160);
            // 段落失败时清空：宁可整段没有，也不要给模型半截事实
            body.setLength(0);
        }
        sections.add(new FlinkContext.Section(id, title, body.length(), degraded));
        return body.toString();
    }

    // ==================== 1. 作业绑定 ====================

    private void appendJobBinding(StringBuilder sb, TaskDTO task, String snapshot) {
        sb.append("\n### 当前作业\n");
        if (task == null) {
            // 未绑定作业 = 新建 / 未保存的 tab。这一段不是"占位"，而是用来堵"信息缺失却不提问"：
            // 没有作业名/并行度/env 时，模型唯一正确的动作是**问用户**，而不是自己编一个。
            sb.append("- **未绑定具体作业**（新建 / 未保存的 tab，或请求未带 taskId）：").append("本次没有作业名、并行度、env、clusterId");
            sb.append("\n- 生成或改动作业时**必须先向用户确认**：作业名（且 `pipeline.name` 必须与它一致）、" + "并行度、目标库表；给建议值时必须标注「建议值，需你确认」\n");
            sb.append("- **不得**凭空编造作业名、集群、env、数据源连接串或占位变量（如 `${db.host}`）\n");
            return;
        }
        // 快照单独成行（而不是只写在标题括号里）：既让模型意识到「这是某时刻状态、可能已变」，
        // 也便于按「上下文口径」要求在回答依据里复述，从而对用户可见、可审计。
        sb.append("- 上下文快照时间: ").append(snapshot).append("（以下所有事实均为该时刻快照；catalog / 名册 / 资源清单可能随后变化）\n");
        sb.append("- 作业: task ")
                .append(task.getId())
                .append(" `")
                .append(trim(task.getName(), 60))
                .append("` | 方言 ")
                .append(task.getDialect());
        if (task.getType() != null) {
            sb.append(" | 运行模式 ").append(task.getType());
        }
        sb.append('\n');

        String cluster = describeCluster(task.getClusterId(), task.getClusterName());
        sb.append("- 集群: ").append(cluster).append('\n');
        if (task.getParallelism() != null) {
            sb.append("- 并行度: ").append(task.getParallelism()).append('\n');
        }

        Integer envId = task.getEnvId();
        if (envId != null && envId > 0) {
            String envName = safeTaskName(envId);
            sb.append("- FlinkSQL 环境(env): `")
                    .append(envName)
                    .append("` (envId=")
                    .append(envId)
                    .append(") —— 该环境语句会前置拼接到作业 SQL（注册 catalog / 设置等）\n");
        } else {
            sb.append("- FlinkSQL 环境(env): **未绑定**（envId=")
                    .append(envId == null ? "null" : envId)
                    .append("）\n");
        }
        sb.append("- 表名口径: ")
                .append("未绑 env 时**必须使用全限定名** `catalog.database.table`（`default_catalog.default_database.xxx`）；")
                .append("只有绑定 env 注册了 catalog 后才可使用短名。**本项目默认口径＝不绑 env + 全限定名**\n");

        List<ConfigItem> customConfig =
                task.getConfigJson() == null ? null : task.getConfigJson().getCustomConfig();
        if (customConfig != null && !customConfig.isEmpty()) {
            sb.append("- 任务自定义配置: ")
                    .append(customConfig.stream()
                            .map(item -> item.getKey() + "=" + mask(item.getKey(), item.getValue()))
                            .collect(Collectors.joining(" ; ")))
                    .append('\n');
        } else {
            sb.append("- 任务自定义配置: 无\n");
        }
    }

    private String describeCluster(Integer clusterId, String clusterName) {
        if (clusterId == null) {
            return "未指定（clusterId=null）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(clusterName == null ? ("clusterId=" + clusterId) : clusterName);
        try {
            ClusterInstance instance = clusterInstanceService.getById(clusterId);
            if (instance != null) {
                sb.append(" (").append(instance.getType());
                if (instance.getJobManagerHost() != null) {
                    sb.append(", ").append(instance.getJobManagerHost());
                }
                if (instance.getVersion() != null) {
                    sb.append(", Flink ").append(instance.getVersion());
                }
                sb.append(')');
            }
        } catch (Exception e) {
            log.debug("FlinkContext: describe cluster {} failed", clusterId, e);
        }
        return sb.toString();
    }

    private String safeTaskName(Integer taskId) {
        try {
            TaskDTO envTask = taskService.getTaskInfoById(taskId);
            return envTask == null ? ("task-" + taskId) : envTask.getName();
        } catch (Exception e) {
            return "task-" + taskId;
        }
    }

    // ==================== 2. Flink Catalog ====================

    private void appendCatalogSection(StringBuilder sb, TaskDTO task) {
        sb.append("\n### Flink Catalog（经「env 回放」枚举，仅反映快照时刻状态；在请求线程内直接执行）\n");
        // 2026-11-03 实测：模型看到 default_catalog 里没有业务表，就要求用户"去面板顶部选 schema"——
        // 而 FlinkSQL 作业根本没有数据源绑定，这个引导是死路。必须显式说明"内联 DDL 是常态"。
        sb.append("- 说明: 本平台 FlinkSQL 作业的源表**在 SQL 内用 `CREATE TABLE ... WITH(...)` 内联声明**，"
                + "`default_catalog` 里没有业务表属**正常现象**；需要业务字段时看下方「外部资源清单」的数据源，"
                + "或请用户 `@source/<数据源>/<库>/<表>` 引用。**不要让用户去面板顶部选 schema**（FlinkSQL 无数据源绑定）\n");
        StudioMetaStoreDTO dto = toMetaStoreDTO(task);
        try {
            List<Catalog> catalogs = studioService.getMSCatalogs(dto);
            if (catalogs == null || catalogs.isEmpty()) {
                sb.append("- 未枚举到任何 catalog（Flink 至少应有 default_catalog；请检查集群与 env 语句）\n");
                return;
            }
            for (Catalog catalog : catalogs) {
                List<String> databases = catalog.getSchemas() == null
                        ? new ArrayList<>()
                        : catalog.getSchemas().stream().map(Schema::getName).collect(Collectors.toList());
                sb.append("- catalog `")
                        .append(catalog.getName())
                        .append("`: ")
                        .append(joinLimit(databases))
                        .append('\n');
            }
            appendDefaultTables(sb, dto, catalogs);
        } catch (Exception e) {
            sb.append("- **枚举失败**：").append(classifyCatalogFailure(e)).append('\n');
            log.warn("FlinkContext: catalog enumeration failed for task {}", task.getId(), e);
        }
    }

    /** 复制一份元数据查询 DTO（appendDefaultTables 需要改 catalog/database 后再查一次）。 */
    private StudioMetaStoreDTO copyOf(StudioMetaStoreDTO src) {
        StudioMetaStoreDTO dto = new StudioMetaStoreDTO();
        dto.setDialect(src.getDialect());
        dto.setEnvId(src.getEnvId());
        dto.setFragment(src.isFragment());
        dto.setStatement(src.getStatement());
        return dto;
    }

    private void appendDefaultTables(StringBuilder sb, StudioMetaStoreDTO dto, List<Catalog> catalogs) {
        boolean hasDefault = catalogs.stream().anyMatch(c -> DEFAULT_CATALOG.equals(c.getName()));
        if (!hasDefault) {
            return;
        }
        StudioMetaStoreDTO tableDto = copyOf(dto);
        tableDto.setCatalog(DEFAULT_CATALOG);
        tableDto.setDatabase(DEFAULT_DATABASE);
        try {
            Schema schema = studioService.getMSSchemaInfo(tableDto);
            if (schema != null
                    && schema.getTables() != null
                    && !schema.getTables().isEmpty()) {
                List<String> tables =
                        schema.getTables().stream().map(Table::getName).collect(Collectors.toList());
                sb.append("- `")
                        .append(DEFAULT_CATALOG)
                        .append('.')
                        .append(DEFAULT_DATABASE)
                        .append("` 下的表: ")
                        .append(joinLimit(tables))
                        .append('\n');
            }
        } catch (Exception e) {
            // 表清单属补充信息，失败静默（catalog / 库清单已给出）
            log.debug("FlinkContext: list default tables failed", e);
        }
    }

    /** 把作业转成 Studio 的元数据查询 DTO（复用 Catalog 面板同一条链路与缓存）。 */
    private StudioMetaStoreDTO toMetaStoreDTO(TaskDTO task) {
        StudioMetaStoreDTO dto = new StudioMetaStoreDTO();
        dto.setDialect(Dialect.FLINK_SQL.getValue());
        dto.setEnvId(task.getEnvId());
        dto.setFragment(task.isFragment());
        dto.setStatement(task.getStatement() == null ? "" : task.getStatement());
        return dto;
    }

    /**
     * 把底层异常翻译成「可读原因」，替代过去的「未绑定数据源」误导性文案。
     *
     * <p>P1 起同时供 {@code EnvRefRenderer} 复用（{@code @env/} 的枚举走同一条回放链路，
     * 失败原因必须与 catalog 区块一致），故从 private 改为 public static。
     */
    public static String classifyCatalogFailure(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("notwebcontext") || lower.contains("not web context")) {
            return "上下文丢失（NotWebContextException）：catalog 枚举必须在 HTTP 请求线程内直接执行，本次已降级。 原始信息: " + trim(message, 200);
        }
        if (lower.contains("could not find") || lower.contains("factory") || lower.contains("no factory")) {
            return "catalog 工厂类不可达（如 `dinky-catalog-*.jar` 不在 Dinky JVM classpath），已降级为仅提供集群与作业信息。"
                    + " 建议检查 env 语句里的 `CREATE CATALOG` 类型与 extends 目录。原始信息: " + trim(message, 200);
        }
        if (lower.contains("does not exist") || lower.contains("not found")) {
            return "env 语句引用的 catalog / 库不存在，已降级。原始信息: " + trim(message, 200);
        }
        return "已被降级（异常类型 " + root.getClass().getSimpleName() + "）。原始信息: " + trim(message, 200);
    }

    // ==================== 3. 外部资源清单 ====================

    private void appendExternalResources(StringBuilder sb) {
        sb.append("\n### 外部资源清单（L3：注册中心数据源，源表字段用 `@source/` 引用获取）\n");
        // 2026-11-03 M1-01 实测：模型查到 traccar-mysql 的表结构后，直接把 MySQL JDBC 当成
        // Flink 的 source **和** sink（url 还是 127.0.0.1:3306）——"手边能看到表"压过了链路约定。
        // 这里必须显式限定清单的用途，否则它会被读成"可用的数据通道目录"。
        sb.append("- **本清单只用于查表结构 / 字段口径，不是 Flink 的数据通道**：")
                .append("source 一律走 Kafka（`@topic/`，canal-json），sink 一律走 ClickHouse（jdbc）；")
                .append("数据源地址只出现在 `@source/` 引用里，**不要**把 `jdbc:mysql://<数据源主机>` 写进 ")
                .append("`WITH ('url' = ...)` 当 source 或 sink\n");
        try {
            List<DataBase> databases = dataBaseService.listEnabledAll();
            if (databases == null || databases.isEmpty()) {
                sb.append("- 已启用的数据源: 无\n");
                return;
            }
            // 2026-11-03 实测：过去只列 Doris，MySQL（traccar-mysql，含 tc_positions）完全不可见，
            // 模型因此判定"没有任何业务表元数据"而拒答。改为列出**全部**已启用数据源。
            sb.append("- 已启用的数据源（源表字段用 `@source/<数据源>/<库>/<表>` 引用获取；" + "sink 侧物理表**必须已存在**，Flink 侧只写逻辑映射）:\n");
            int enriched = 0;
            for (DataBase db : databases) {
                sb.append("  - id=")
                        .append(db.getId())
                        .append(" `")
                        .append(db.getName())
                        .append("` (")
                        .append(db.getType())
                        .append(')');
                if (enriched < MAX_ENRICHED_SOURCES) {
                    enriched++;
                    try {
                        Driver driver = Driver.build(db.getDriverConfig());
                        sb.append(": ")
                                .append(joinLimit(driver.listSchemas().stream()
                                        .map(Schema::getName)
                                        .collect(Collectors.toList())));
                    } catch (Exception e) {
                        sb.append(": (库清单获取失败: ")
                                .append(trim(e.getMessage(), 80))
                                .append(')');
                    }
                }
                sb.append('\n');
            }
            if (databases.size() > MAX_ENRICHED_SOURCES) {
                sb.append("  - （其余 ")
                        .append(databases.size() - MAX_ENRICHED_SOURCES)
                        .append(" 个数据源未展开库清单，可按名称 `@source/` 引用）\n");
            }
        } catch (Exception e) {
            log.debug("FlinkContext: list external resources failed", e);
        }
    }

    // ==================== 3.4 集群能力（L1） ====================

    /**
     * L1 区块：**集群客观能力**（推断不出来的事实）。
     *
     * <p>这里只放"探测得到、模型猜不到"的东西：Flink 版本、真实可用的 connector、调试专用 connector。
     * 缺了它，模型就会写 {@code 'connector' = 'hive'}（提交阶段才报 Could not find any factories）。
     */
    private void appendCapabilities(StringBuilder sb) {
        FlinkCapabilities capabilities;
        try {
            capabilities = flinkCapabilitiesLoader.capabilities();
        } catch (Exception e) {
            log.debug("FlinkContext: capabilities unavailable", e);
            return;
        }
        if (capabilities == null || capabilities.isEmpty()) {
            return;
        }
        sb.append("\n### 集群能力（L1：探测自 Flink lib，**白名单之外一律不可用**）\n");
        if (!capabilities.getFlinkVersion().isEmpty()) {
            sb.append("- Flink 版本: ").append(capabilities.getFlinkVersion());
            if (!capabilities.getSourceHost().isEmpty()) {
                sb.append("（探测来源 ")
                        .append(trim(capabilities.getSourceHost(), 80))
                        .append("）");
            }
            sb.append('\n');
        }
        if (!capabilities.usableConnectors().isEmpty()) {
            sb.append("- 可用 connector: ")
                    .append(capabilities.usableConnectors().stream()
                            .map(item -> "`" + item + "`")
                            .collect(Collectors.joining("、")))
                    .append('\n');
        }
        if (!capabilities.getDebugConnectors().isEmpty()) {
            sb.append("- **调试专用** connector: ")
                    .append(capabilities.getDebugConnectors().stream()
                            .map(item -> "`" + item + "`")
                            .collect(Collectors.joining("、")))
                    .append(" —— 只能临时验证，**不能作为交付 sink**（校验器会直接拦）\n");
        }
        // 2026-11-04：原文案把 `doris` 也列进"白名单外"，而探测结果显示 doris 是**已装**的
        // （flink-doris-connector-1.17-26.1.1.jar）。模型看到这种自相矛盾的文案会随机挑一边信，
        // 因此这里只举真正不在白名单里的例子，并强调"以本段列出的可用清单为准"。
        sb.append("- 上述清单**之外**的 connector（例如 `hive` / `elasticsearch` / `hbase`）在本集群没有 jar，"
                + "写进 `WITH(...)` 会在提交阶段报 `Could not find any factories`，**不要尝试，也不要建议用户换这些 sink**。"
                + "本段若与下方「平台链路约定」冲突，**以本段的可用清单为准**（它探测自真实 lib 目录）\n");
        appendHiveGuidance(sb);
    }

    /**
     * Hive 表的正确读法（2026-11-04 M1-01 复盘新增）。
     *
     * <p><b>为什么必须单独写一段</b>：M1-01 里用户要的数据在 Hive 外部表（`lpods.ods_traccar_*`）里，
     * 而集群没有 hive connector。模型面临"要的数据在 A、可用的通道只有 B"的冲突时，选择了硬写
     * {@code 'connector'='hive'}——它是真知道白名单的（SQL 注释里都写了"白名单不含 hive"），
     * 只是不知道**替代路径是什么**。只告诉它"不许用"而不告诉它"该用什么"，它就只能违反约束。
     *
     * <p>这里给出可执行的替代：Hive/MySQL/Doris 数据源只用于 {@code @source/} 查字段口径，
     * 真实数据通道是 Kafka → ClickHouse/Doris。
     */
    private void appendHiveGuidance(StringBuilder sb) {
        boolean hasHive = capabilitiesHas("hive");
        if (hasHive) {
            return;
        }
        sb.append("- **读 Hive 表（如 `lpods.ods_*`）的正确做法**：本集群 Flink **没有** hive connector，"
                + "`WITH ('connector' = 'hive')` 必定在提交阶段失败。"
                + "Hive 数据源（`hive-lpods` 等）**只能**用 `@source/` 查字段口径与分区格式，"
                + "不能作为 Flink 的 source。真实数据通道是 **Kafka（`@topic/`）→ Flink → ClickHouse/Doris**。"
                + "因此当用户要求\"基于某张 Hive 表算\"时，正确动作是："
                + "① 用 `@source/` 拿到该表的字段口径；② 明确告诉用户\"Flink 侧无 hive connector，需改用 Kafka 链路\"；"
                + "③ 给出 Kafka 版 SQL（topic 见「平台链路约定」）。"
                + "**不要**直接产出 hive DDL，也不要建议用户\"去找平台加 jar\"当作交付方案\n");
    }

    /** 能力画像里是否装了某个 connector（用于按事实分支，而不是硬编码举例） */
    private boolean capabilitiesHas(String connector) {
        try {
            FlinkCapabilities capabilities = flinkCapabilitiesLoader.capabilities();
            return capabilities != null
                    && capabilities.allConnectors().contains(connector.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 3.5 平台默认配置（L2） ====================

    /**
     * L2 区块：**配置中心里的默认值**（Kafka 与默认 sink 数据源）。
     *
     * <p><b>默认 sink 的解析口径（2026-11-03 改）</b>：配置项是全局的
     * （{@code dinky_sys_config} 无 tenant_id），所以它引用的对象<b>不能是租户内唯一的 id</b>，
     * 否则必然出现"某个租户解析不到"（实测：配置成 tenant 2 的 id，tenant 1 直接降级成不可用）。
     *
     * <p>现在的顺序：
     * <ol>
     *   <li><b>按名称</b>（{@code aiDefaultSinkDataBaseName}，主通道）：在**当前租户**内按名称查启用的数据源，
     *       用本租户自己的凭证 —— 一份全局配置对每个租户都成立；
     *   <li><b>按 id</b>（{@code aiDefaultSinkDataBaseId}，兼容通道）：仅在没填名称时兜底，
     *       语义与旧版一致（跨租户 id 只会在该租户下解析失败，此时明确告知而不是编造）。
     * </ol>
     *
     * <p>口令一律不回显。
     */
    private void appendPlatformDefaults(StringBuilder sb) {
        SystemConfiguration config = SystemConfiguration.getInstances();
        sb.append("\n### 平台默认配置（L2：配置中心，未显式指定时的默认取值）\n");
        sb.append("- Kafka 接入地址: `")
                .append(config.getKafkaBootstrapServers())
                .append("`（**Flink 侧必须用 INTERNAL listener 29092**，不是 EXTERNAL 29094）\n");
        sb.append("- Kafka 约定建议: scan.startup.mode=")
                .append(config.getKafkaDefaultScanStartupMode())
                .append("；group.id 前缀=")
                .append(config.getKafkaConsumerGroupPrefix())
                .append('\n');
        appendKafkaTopics(sb);
        // 拆成两段：sink 解析（多分支 return）+ 全局变量清单（必须无条件输出，否则"
        // 不知道有哪些变量可用"的模型会继续臆造 ${xxx}）
        appendDefaultSink(sb);
        appendGlobalVariables(sb);
    }

    /**
     * 真实 topic 清单（P1，2026-11-04）：从 broker 实时列举，取代"人工登记"作为权威来源。
     *
     * <p><b>为什么要写进 prompt 而不只做成工具</b>：工具要模型主动调，实测它对"确认事实"这种事情
     * 并不积极（更愿意直接按印象写）。把清单直接摆在眼前，"编 topic"这条路才真正被堵死；
     * 工具则留给"用户问有哪些 topic"这类需要现场确认的场合。
     *
     * <p>取不到时明确写"暂时取不到"，**绝不**退化成"没有 topic"——那会让模型把可达性问题
     * 误读成"该 topic 不存在"，然后去改一个本来正确的 SQL。
     */
    private void appendKafkaTopics(StringBuilder sb) {
        if (kafkaTopicCatalog == null) {
            return;
        }
        List<KafkaTopicCatalog.TopicInfo> topics;
        try {
            topics = kafkaTopicCatalog.externalTopics();
        } catch (Exception e) {
            log.debug("FlinkContext: list kafka topics failed", e);
            return;
        }
        if (topics == null) {
            sb.append("- Kafka topic 清单: **暂时取不到**（broker 不可达）。这不代表没有 topic，"
                    + "以「平台链路约定」登记的 topic 为准，**不要**据此判定某 topic 不存在\n");
            return;
        }
        if (topics.isEmpty()) {
            sb.append("- Kafka topic 清单: 集群里当前**没有任何业务 topic**，写 kafka 源表前必须先确认 topic 来源\n");
            return;
        }
        sb.append("- Kafka **真实** topic（实时列举自 broker，与登记表冲突时以此为准）: ");
        int shown = 0;
        for (KafkaTopicCatalog.TopicInfo topic : topics) {
            if (shown > 0) {
                sb.append('、');
            }
            sb.append('`').append(topic.getName()).append('`');
            if (topic.getPartitions() > 0) {
                sb.append('(').append(topic.getPartitions()).append(" 分区)");
            }
            shown++;
        }
        sb.append('\n');
    }

    /** L2 的 sink 段（多分支返回；与变量清单分开，避免任一分支提前 return 吞掉变量清单） */
    private void appendDefaultSink(StringBuilder sb) {
        SystemConfiguration config = SystemConfiguration.getInstances();

        String sinkName = StrUtil.trimToEmpty(config.getAiDefaultSinkDataBaseName());
        if (!sinkName.isEmpty()) {
            DataBase sink = null;
            try {
                sink = dataBaseService.getEnabledByName(sinkName);
            } catch (Exception e) {
                log.debug("FlinkContext: load default sink by name {} failed", sinkName, e);
            }
            if (sink != null) {
                appendResolvedSink(sb, sink);
                return;
            }
            // 名称填了但本租户没有：这不是"配置坏了"，而是"这个租户还没注册同名数据源"。
            // 必须说清处置方式，否则模型会自己编一个连接串出来。
            sb.append("- 默认 sink 数据源: **本租户未注册同名数据源**（`sys.flink.settings.aiDefaultSinkDataBaseName=")
                    .append(sinkName)
                    .append("`）。该配置是全局配置、所有租户共用：请在本租户的「注册中心 → 数据源」注册一个**同名**且已启用的数据源")
                    .append("（各租户可用自己的连接凭证），或改用 `@source/` 显式指定目标库。")
                    .append("**不要编造连接串或占位变量**\n");
            appendAvailableDataSources(sb);
            return;
        }

        int sinkId = config.getAiDefaultSinkDataBaseId();
        if (sinkId <= 0) {
            sb.append("- 默认 sink 数据源: **未配置**（`sys.flink.settings.aiDefaultSinkDataBaseName` 为空且 ")
                    .append("`sys.flink.settings.aiDefaultSinkDataBaseId=0`）")
                    .append(" —— 生成作业时**必须**向用户确认写哪个库，或用 `@source/` 显式指定，不要自己编造连接串\n");
            appendAvailableDataSources(sb);
            return;
        }
        DataBase sink = null;
        try {
            sink = dataBaseService.getById(sinkId);
        } catch (Exception e) {
            log.debug("FlinkContext: load default sink data source {} failed", sinkId, e);
        }
        if (sink == null) {
            // 兼容通道的已知缺陷：id 是租户内唯一的，而配置是全局的，所以跨租户 id 必然查不到。
            // 这里不越权去读别的租户的连接信息，只明确告知并给出替代路径。
            sb.append("- 默认 sink 数据源: **当前租户不可用**（id=")
                    .append(sinkId)
                    .append(" 在本租户下不存在：可能已删除，或指向了其它租户的数据源——该配置项是全局配置、所有租户共用）")
                    .append("。建议改用 `sys.flink.settings.aiDefaultSinkDataBaseName`（按名称，各租户各自注册同名数据源）")
                    .append("；在此之前请用 `@source/` 指定目标库，不要编造连接串\n");
            appendAvailableDataSources(sb);
            return;
        }
        sb.append("- 默认 sink 数据源: ").append(describeSinkIdentity(sink)).append('\n');
        appendSinkDetails(sb, sink);
        sb.append("  - 提示: 该值来自**按 id** 的兼容通道（`aiDefaultSinkDataBaseId`），对其它租户不可用；")
                .append("建议改配 `aiDefaultSinkDataBaseName`（按名称）\n");
    }

    /** 命中默认 sink 时的统一输出（名称通道与 id 通道共用，避免两处文案漂移） */
    private void appendResolvedSink(StringBuilder sb, DataBase sink) {
        sb.append("- 默认 sink 数据源: ").append(describeSinkIdentity(sink)).append('\n');
        appendSinkDetails(sb, sink);
    }

    /** {@code id=N `name` (Type)} —— 上下文里同时给 id 与名称，便于用户核对配置值 */
    private static String describeSinkIdentity(DataBase sink) {
        return "id=" + sink.getId() + " `" + sink.getName() + "` (" + sink.getType() + ")";
    }

    private void appendSinkDetails(StringBuilder sb, DataBase sink) {
        String endpoint = describeJdbcEndpoint(sink);
        if (!endpoint.isEmpty()) {
            sb.append("  - 连接形态: `").append(endpoint).append("`（账号口令不注入上下文）\n");
        }
        // P0-a（2026-11-03）：这里原来写的是"作业里请用变量占位"，等于暗示了一个不说明定义的机制，
        // 模型据此臆造出 `${clickhouse.username}` —— 而 Dinky 的 ${} 是全局变量/JEXL 语法，
        // 未定义时提交阶段直接抛 BusException（"The variable name or jexl key of sql ... does not exist"）。
        sb.append("  - **写 sink 的 `username` / `password` 时的硬约束**："
                + "`${...}` 是 Dinky 的全局变量/JEXL 语法，**只有出现在「已定义的全局变量」清单里的名字才能引用**，"
                + "并且**该作业必须开启「全局变量」开关**（作业配置 → 全局变量，字段 `fragment`）——"
                + "开关关闭时占位符会**原样下发**，Flink 直接报 `Encountered \"$\"`；"
                + "开关打开但变量未定义时报 `variable ... does not exist`。两者都是**提交阶段**失败。"
                + "所以**绝对不要臆造变量名**（例如 `${clickhouse.username}` 这种）。不确定时的正确做法："
                + "**省略这两个选项**，并在 `precheck` 里写一条 `unknown`："
                + "「sink 账号口令需补充——请先定义全局变量并在作业配置里开启「全局变量」，或直接填写」\n");
        sb.append("  - 生成作业时 sink 默认落这个库；目标表**必须已存在**（Flink 只做逻辑映射）\n");
        appendSinkTables(sb, sink);
    }

    /**
     * 默认 sink 库里的**目标表候选**清单（2026-11-04 新增）。
     *
     * <p><b>为什么必须给</b>：M1-01 实测时模型只知道"默认 sink 是个 ClickHouse 数据源"，
     * 不知道库里有哪些表，于是自己造了一张 {@code dws_traccar_device_position_top5_df} 并配
     * {@code 'connector'='print'} 当交付 sink——既没落到默认库，也不可能上线。
     * 给出表清单后，"写哪张表"从"编一个"变成"从清单里选"。
     *
     * <p>只列**表名**（不展开字段）：字段口径由 {@code @source/} 按需取，避免上下文膨胀。
     */
    private void appendSinkTables(StringBuilder sb, DataBase sink) {
        String schema = defaultSchemaOf(sink);
        if (schema.isEmpty()) {
            return;
        }
        try {
            Driver driver = Driver.build(sink.getDriverConfig());
            List<Table> tables = driver.listTables(schema);
            if (tables == null || tables.isEmpty()) {
                sb.append("  - 该库（`")
                        .append(schema)
                        .append("`）当前**没有已存在的表**：")
                        .append("sink 前必须先在该库建表，否则作业能提交、首条数据才失败\n");
                return;
            }
            List<String> names = tables.stream()
                    .map(Table::getName)
                    .filter(StrUtil::isNotBlank)
                    .sorted()
                    .collect(Collectors.toList());
            sb.append("  - 该库（`")
                    .append(schema)
                    .append("`）已存在的目标表: ")
                    .append(joinLimit(names))
                    .append('\n');
            sb.append("  - **sink 只能写上面这些已存在的表**（库名 `")
                    .append(schema)
                    .append("`）；")
                    .append("要新建表必须先给出建表 DDL 并让用户确认执行，不能直接把不存在的表名写进 INSERT INTO\n");
        } catch (Exception e) {
            // 表清单属补充信息：连不上就只丢这一段，不影响其它段落
            sb.append("  - 该库（`")
                    .append(schema)
                    .append("`）的表清单获取失败: ")
                    .append(trim(e.getMessage(), 100))
                    .append("（生成 sink 前请用 `@source/` 确认目标表存在）\n");
            log.debug("FlinkContext: list sink tables failed for {}", sink.getName(), e);
        }
    }

    /**
     * 从数据源连接串里取**默认库名**（{@code jdbc:clickhouse://host:port/traccar} → {@code traccar}）。
     *
     * <p>无库名（{@code jdbc:clickhouse://host:port}）时返回空串——此时无法确定表清单口径，
     * 宁可不给清单，也不能把 {@code 38123} 这种端口段当库名去查。
     */
    static String defaultSchemaOf(DataBase dataBase) {
        if (dataBase == null) {
            return "";
        }
        try {
            org.dinky.metadata.config.DriverConfig<?> driverConfig = dataBase.getDriverConfig();
            Object connectConfig = driverConfig == null ? null : driverConfig.getConnectConfig();
            if (!(connectConfig instanceof Map)) {
                return "";
            }
            Object url = ((Map<?, ?>) connectConfig).get("url");
            if (url == null) {
                return "";
            }
            String text = String.valueOf(url);
            int query = text.indexOf('?');
            if (query >= 0) {
                text = text.substring(0, query);
            }
            int slash = text.lastIndexOf('/');
            if (slash < 0 || slash == text.length() - 1) {
                return "";
            }
            String schema = text.substring(slash + 1).trim();
            // `jdbc:clickhouse://host:38123` → 取到的是端口，不是库名
            if (schema.isEmpty() || schema.contains(":") || schema.matches("\\d+")) {
                return "";
            }
            return schema;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * P0-b：注入**当前租户**已定义的全局变量名（值不下发）。
     *
     * <p><b>为什么要单独给这份清单</b>：`${...}` 未定义会在<b>提交阶段</b>抛
     * {@code The variable name or jexl key of sql "${x}" does not exist}（{@code VariableManager:146}），
     * 而模型看不到变量表，只能"按常见命名猜"——11-03 UAT 就这样猜出了
     * {@code ${clickhouse.username}}。给出真实清单后，正确行为从"猜名字"变成"看清单"。
     *
     * <p>只输出**名字**：敏感变量的值在库里是加密存储的，任何情况下都不进上下文。
     */
    private void appendGlobalVariables(StringBuilder sb) {
        List<String> names = new ArrayList<>();
        try {
            Object tenantId = TenantContextHolder.get();
            // 用字符串比较：租户上下文里存的可能是 Integer 也可能是 String（登录态/请求头来源不同），
            // 直接 equals 会因为装箱类型不一致把本租户变量全过滤掉。
            String currentTenant = tenantId == null ? null : String.valueOf(tenantId);
            for (FragmentVariable variable : fragmentVariableService.listEnabledAll()) {
                if (variable == null || StrUtil.isBlank(variable.getName())) {
                    continue;
                }
                // dinky_fragment 不在租户过滤名单里（变量表按 name 唯一、跨租户同名会互相覆盖），
                // 这里显式按当前租户过滤，避免把别的租户的变量名喂给模型。
                if (currentTenant != null && !currentTenant.equals(String.valueOf(variable.getTenantId()))) {
                    continue;
                }
                names.add("`" + variable.getName() + "`");
            }
        } catch (Exception e) {
            log.debug("FlinkContext: load global variables failed", e);
        }
        sb.append("- 已定义的全局变量（注册中心 → 全局变量；引用 `${name}` 必须来自这里，且作业需开启「全局变量」开关）: ");
        if (names.isEmpty()) {
            sb.append("**无** —— 此时作业里出现任何 `${...}` 都会失败"
                    + "（开关打开 → `variable ... does not exist`；开关关闭 → Flink 报 `Encountered \"$\"`），"
                    + "需要账号口令时按上面 sink 段的约束处理（省略 + precheck 写 unknown）\n");
            return;
        }
        sb.append(String.join("、", names)).append("（值不下发：敏感变量在库中加密存储）\n");
    }

    /** 上下文里最多列多少个可选数据源（避免挤占预算；更多请去注册中心看） */
    private static final int DEFAULT_SINK_CANDIDATE_LIMIT = 12;

    /**
     * 列出**当前租户**可见的数据源清单 {@code id=name(type)}。
     *
     * <p>为什么要在上下文里列 id：`aiDefaultSinkDataBaseId` 填的是 id，而界面上长期看不到 id
     * （2026-11-03 反馈）。虽然模型不需要 id，但用户会照着上下文/日志核对"到底配的是哪个库"，
     * 列出来能让"配置值 → 具体数据源"的对应关系可解释。
     */
    private void appendAvailableDataSources(StringBuilder sb) {
        try {
            List<DataBase> databases = dataBaseService.listEnabledAll();
            if (databases == null || databases.isEmpty()) {
                return;
            }
            sb.append("  - 当前租户可选数据源（配置项填这里的 id）: ");
            int shown = 0;
            for (DataBase dataBase : databases) {
                if (shown >= DEFAULT_SINK_CANDIDATE_LIMIT) {
                    sb.append("…（更多见注册中心）");
                    break;
                }
                if (shown > 0) {
                    sb.append("；");
                }
                sb.append(dataBase.getId())
                        .append('=')
                        .append(dataBase.getName())
                        .append('(')
                        .append(dataBase.getType())
                        .append(')');
                shown++;
            }
            sb.append('\n');
        } catch (Exception e) {
            log.debug("FlinkContext: list enabled data sources failed", e);
        }
    }

    // ==================== 3.6 平台画像（人工维护） ====================

    /**
     * 人工维护的平台说明：connector 用法约束 / 标准链路 / 已知 topic。
     *
     * <p><b>业务口径不在这里</b>——它是知识（`lpdw-dict` / skill），由业务维护，
     * 放配置文件里迟早与业务实际脱节。
     */
    private void appendProfile(StringBuilder sb) {
        PlatformProfile profile;
        try {
            profile = platformProfileLoader.profile();
        } catch (Exception e) {
            log.debug("FlinkContext: platform profile unavailable", e);
            return;
        }
        if (profile == null || profile.isEmpty()) {
            return;
        }
        if (!profile.getConnectorNotes().isEmpty() || !profile.getPipelines().isEmpty()) {
            sb.append("\n### 平台链路约定（人工维护，与通用知识冲突时以本节为准）\n");
            for (String note : profile.getConnectorNotes()) {
                sb.append("- ").append(note).append('\n');
            }
            for (String pipeline : profile.getPipelines()) {
                sb.append("- ").append(pipeline).append('\n');
            }
        }
        if (!profile.getTopics().isEmpty()) {
            sb.append("- 已知 topic:\n");
            for (Map<String, String> topic : profile.getTopics()) {
                sb.append("  - `").append(topic.getOrDefault("name", "?")).append('`');
                String note = topic.get("note");
                if (note != null && !note.isEmpty()) {
                    sb.append(" —— ").append(note);
                }
                sb.append('\n');
            }
        }
        sb.append("- 业务口径（「位置条数用哪张表」这类）以知识库 `lpdw-dict` 为准；" + "知识库没写清时**向用户确认**，不要按通用经验替用户定口径\n");
    }

    // ==================== 3.7 参考作业（few-shot，P0 2026-11-04） ====================

    /** 单条 few-shot SQL 的字符上限（两条合计 3600，给 20000 的总预算留足余量） */
    private static final int MAX_FEWSHOT_SQL_CHARS = 1800;

    /** 召回条数：多给会撑爆 prompt，2 条足够覆盖"结构最接近" */
    private static final int MAX_FEWSHOT_ITEMS = 2;

    /** 关键词上限（打分是 O(关键词 × 语料)，兜住上限避免病态输入拖慢召回） */
    private static final int MAX_QUERY_KEYWORDS = 20;

    private static final java.util.regex.Pattern LATIN_WORD =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,}");

    private static final java.util.regex.Pattern CJK_RUN = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    /**
     * 参考作业段：把本集群**真实可跑**的历史作业作为 few-shot 注入。
     *
     * <p><b>为什么这一段比规则更重要</b>：M1-01 六轮的结论是"事实齐全 ≠ 写得出来"。
     * 模型看到 {@code topic=traccar-cdc} 之后仍然不知道 canal 报文要 `format='raw'` + `JSON_VALUE`
     * 拆字段（`canal-json` 在窗口聚合场景下根本不能用），这类**结构知识**只能靠范例传递。
     *
     * <p>语料已脱敏（口令类字面量替换为 {@code ***}），且 L2 线上作业已过 draft 档规则过滤——
     * 脏作业不会进池（反面教材不能当范例）。
     */
    private void appendSimilarTasks(StringBuilder sb, TaskDTO task, String question) {
        if (similarTasksService == null || StrUtil.isBlank(question)) {
            // 没有提问（例如 GET /api/aiChat/context 的纯探测调用）就不注入：
            // 召回要扫一遍线上作业，没必要为一次探测付这个成本。
            return;
        }
        cn.hutool.json.JSONArray items;
        try {
            JSONObject recalled = similarTasksService.recall(buildSimilarQuery(question, task));
            items = recalled == null ? null : recalled.getJSONArray("items");
        } catch (Exception e) {
            log.debug("FlinkContext: recall similar tasks failed", e);
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append("\n### 参考作业（本集群真实可跑的历史作业，已脱敏）\n");
        sb.append("- **用法**：照它的**骨架**改（源表怎么声明、canal 报文怎么拆字段、窗口怎么开、sink 怎么写），"
                + "业务字段与目标表以本次需求为准。口令类字面量已替换为 `***`，照抄前请确认\n");
        int shown = 0;
        for (Object element : items) {
            if (!(element instanceof JSONObject)) {
                continue;
            }
            JSONObject item = (JSONObject) element;
            String sql = item.getStr("sql");
            if (StrUtil.isBlank(sql)) {
                continue;
            }
            shown++;
            sb.append("\n#### 参考 ").append(shown).append("：").append(trim(item.getStr("title"), 80));
            Object taskId = item.get("taskId");
            if ("L2".equalsIgnoreCase(item.getStr("source")) && taskId != null) {
                sb.append("（来自本租户作业 #").append(taskId).append("）");
            }
            sb.append('\n');
            sb.append("```sql\n").append(trim(sql, MAX_FEWSHOT_SQL_CHARS)).append("\n```\n");
            String customConfig = item.getStr("customConfig");
            if (StrUtil.isNotBlank(customConfig)) {
                sb.append("- customConfig: ").append(trim(customConfig, 300)).append('\n');
            }
        }
    }

    /** 用问题 + 当前作业名构造召回条件（纯本地打分，不调模型） */
    private static SimilarTasksQuery buildSimilarQuery(String question, TaskDTO task) {
        SimilarTasksQuery query = new SimilarTasksQuery();
        query.setLimit(MAX_FEWSHOT_ITEMS);
        Set<String> keywords = new LinkedHashSet<>();
        keywords.addAll(splitKeywords(question));
        if (task != null) {
            keywords.addAll(splitKeywords(task.getName()));
        }
        query.setKeywords(new ArrayList<>(keywords));
        return query;
    }

    /**
     * 中英混合分词：拉丁词整取，中文按 2-gram 滑窗。
     *
     * <p>中文没有空格，整句当一个关键词必然一条也匹配不上（{@code blob.contains} 是子串匹配），
     * 所以切成 2 字滑窗——"按天统计" → 按天/天统/统计，能命中语料里的"统计""窗口"等词。
     */
    private static List<String> splitKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return keywords;
        }
        java.util.regex.Matcher latin = LATIN_WORD.matcher(text);
        while (latin.find() && keywords.size() < MAX_QUERY_KEYWORDS) {
            keywords.add(latin.group().toLowerCase(java.util.Locale.ROOT));
        }
        java.util.regex.Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find() && keywords.size() < MAX_QUERY_KEYWORDS) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length() && keywords.size() < MAX_QUERY_KEYWORDS; i++) {
                keywords.add(run.substring(i, i + 2));
            }
        }
        return keywords;
    }

    /**
     * 数据源 → {@code host:port/db}（**绝不返回口令**）。
     *
     * <p>用途有两个：① 上下文里告诉模型"默认 sink 长什么样"；② 供连接串一致性规则比对。
     */
    static String describeJdbcEndpoint(DataBase dataBase) {
        if (dataBase == null) {
            return "";
        }
        try {
            org.dinky.metadata.config.DriverConfig<?> driverConfig = dataBase.getDriverConfig();
            Object connectConfig = driverConfig == null ? null : driverConfig.getConnectConfig();
            if (!(connectConfig instanceof Map)) {
                return "";
            }
            Object url = ((Map<?, ?>) connectConfig).get("url");
            if (url == null) {
                return "";
            }
            return normalizeJdbcEndpoint(String.valueOf(url));
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code jdbc:clickhouse://doris05:38123/traccar?x=1} → {@code doris05:38123/traccar} */
    static String normalizeJdbcEndpoint(String url) {
        if (url == null) {
            return "";
        }
        int schemeAt = url.indexOf("://");
        if (schemeAt < 0) {
            return "";
        }
        String rest = url.substring(schemeAt + 3);
        int queryAt = rest.indexOf('?');
        if (queryAt >= 0) {
            rest = rest.substring(0, queryAt);
        }
        int semicolonAt = rest.indexOf(';');
        if (semicolonAt >= 0) {
            rest = rest.substring(0, semicolonAt);
        }
        return rest.replaceAll("/+$", "");
    }

    // ==================== 4. 规则红线 ====================

    /**
     * 参数放置规则（固定文案，属"框架事实"而非"规则库策略"）+ 规则库渲染的红线。
     *
     * <p><b>S8 起</b>：具体红线由 {@code config/…/ai/flinksql-rules.yaml} 的 {@code prompt} 字段渲染，
     * 这里只保留两段不随规则增删变化的框架事实（参数通道 / 快照口径），避免"平台事实"被规则配置误删。
     */
    private void appendRules(StringBuilder sb) {
        sb.append(PARAM_CHANNELS).append(ruleEngine.renderPrompt()).append(CONTEXT_SCOPE);
    }

    /**
     * 四段式输出协议（M1-D）：让模型的产出可以被前端解析成卡片、被后端直接校验。
     *
     * <p>协议写成"必须"而不是"建议"：不给出围栏时，前端只能渲染 Markdown，
     * 「写入草稿」就无从谈起（用户得手工复制四段并逐段粘贴）。
     */
    private void appendProtocol(StringBuilder sb) {
        // 协议原文只有一份（FlinkJobProtocol）：注入用它与补发提示用它是同一段文本，
        // 避免"两处文案各改一半"导致补发时模型看到的协议与注入时的协议不一致。
        sb.append(FlinkJobProtocol.PROMPT);
    }

    /**
     * 审核既有作业的硬约束（M1-03 / 零误报回归的守门条款）。
     *
     * <p><b>为什么必须写死</b>：2026-11-03 UAT 实测，用户说"请审核当前打开的文件中的 flinksql"时，
     * 模型 {@code tool_call_count=0} —— 一次校验器都没调，却照样输出"有 1 个 error 级问题"，
     * 把"自由点评"伪装成"规则结论"。审核类请求必须强制走校验器，且结论要能追溯到规则编号。
     */
    private void appendReviewProtocol(StringBuilder sb) {
        sb.append("\n### 审核既有作业（用户问「这段 SQL 能不能提交 / 有没有问题 / 帮我审一下」时）\n")
                .append("- **必须先调用 `validate_task_draft`**：把当前编辑区 SQL、任务名、并行度、taskId、" + "任务自定义配置一并传入，拿到报告后再回答\n")
                .append("- 回答里必须**引用规则编号**（如 `FLINK-PARAM-001`）与级别（error / warn / unknown），" + "不能只说「有问题」或只给一段散文\n")
                .append("- 只有校验器的 error 等于「不能提交」；warn 属建议、unknown 属待确认，必须分区写，不得混为一谈\n")
                .append("- **没调用校验器就不得下「能提交 / 没问题 / 通过」的结论**，也不得编造规则命中\n")
                .append("- 校验器覆盖不到的内容（业务口径、字段语义、性能）可以补充，但必须标注" + "「以下为经验建议，非规则结论」\n");
    }

    // ==================== 5. 命名名册 ===================="""

    private void appendRegistry(StringBuilder sb) {
        sb.append("\n### 已占用名册（生成新作业前必须避开）\n");
        try {
            NameRegistryService.Snapshot snapshot = nameRegistryService.getSnapshot();
            if (snapshot == null || snapshot.isEmpty()) {
                sb.append("- （本租户内未统计到已占用值）\n");
            } else {
                for (String kind : NameRegistryService.Snapshot.kinds()) {
                    List<String> values = snapshot.get(kind);
                    if (values.isEmpty()) {
                        continue;
                    }
                    sb.append("- ")
                            .append(NameRegistryService.Snapshot.kindLabel(kind))
                            .append(": ")
                            .append(joinLimit(values))
                            .append('\n');
                }
            }
            sb.append("- 口径: 只统计 `dinky_task` 中**本租户**的 `FlinkSql` / `FlinkSqlEnv` 作业")
                    .append("（当前 ")
                    .append(snapshot == null ? 0 : snapshot.getTaskCount())
                    .append(" 个）；`jobs/` 目录不在统计范围\n");
        } catch (Exception e) {
            log.warn("FlinkContext: name registry failed", e);
            sb.append("- （名册获取失败，已降级）\n");
        }
    }

    // ==================== 工具方法 ====================

    private static String joinLimit(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "(无)";
        }
        if (items.size() <= MAX_ITEMS_PER_LINE) {
            return String.join(", ", items);
        }
        return String.join(", ", items.subList(0, MAX_ITEMS_PER_LINE)) + " …(共 " + items.size() + " 个)";
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 口令类字段掩码：绝不把密码送进 prompt。 */
    private static String mask(String key, String value) {
        if (key == null) {
            return value;
        }
        String lower = key.toLowerCase();
        if (lower.contains("password") || lower.contains("secret") || lower.contains("key")) {
            return "***";
        }
        return value;
    }

    /** 参数通道事实（Standalone 下只有 SET 与自定义配置两条路，写错位置静默失效） */
    private static final String PARAM_CHANNELS = "\n### 参数放置规则（写错位置会静默失效，必须遵守）\n"
            + "- 走 SQL `SET` 有效: `execution.checkpointing.*`（**min-pause 除外**）、`restart-strategy.*`、`table.exec.*`、"
            + "`parallelism.default`、`pipeline.name`\n"
            + "- **只能放「任务自定义配置」**: `execution.checkpointing.min-pause`（SQL SET 实测无效；须用 Flink 1.17 新 key）\n"
            + "- **必须写在 `CREATE TABLE ... WITH(...)`**: connector 全部参数、`sink.parallelism`\n"
            + "- **禁止进 env**: `pipeline.name`（作业唯一标识，进 env 会让同一批作业同名）\n"
            + "- Standalone 模式下 `flink-conf.yaml` 与集群配置模板**均不在作业链路上**（已实测）；"
            + "客户端参数只有 SET 与自定义配置两个通道\n";

    /** 快照口径（每次对话都要提醒模型：这是某一时刻的事实） */
    private static final String CONTEXT_SCOPE = "\n### 上下文口径\n"
            + "- 本区块是**某一时刻的快照**（见「上下文快照时间」）：catalog / 名册 / 资源清单可能已变化\n"
            + "- 当回答依赖上述快照事实时，请在「依据」中注明该快照时间，便于用户判断是否需要刷新\n";
}
