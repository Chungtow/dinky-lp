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

import org.dinky.config.Dialect;
import org.dinky.data.dto.StudioMetaStoreDTO;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.ext.ConfigItem;
import org.dinky.data.model.Catalog;
import org.dinky.data.model.ClusterInstance;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.Schema;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.ClusterInstanceService;
import org.dinky.service.DataBaseService;
import org.dinky.service.StudioService;
import org.dinky.service.TaskService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

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

    /** 最多列出的 Doris 数据源个数（每个数据源查一次库清单）。 */
    private static final int MAX_DORIS_SOURCES = 5;

    /** 单段清单最多列出的条目数（库 / 表）。 */
    private static final int MAX_ITEMS_PER_LINE = 60;

    private static final String DEFAULT_CATALOG = "default_catalog";

    private static final String DEFAULT_DATABASE = "default_database";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;

    private final ClusterInstanceService clusterInstanceService;

    private final StudioService studioService;

    private final DataBaseService dataBaseService;

    private final NameRegistryService nameRegistryService;

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
     * @param taskId 当前作业 id（可为 null → 返回空串）
     * @return 上下文文本；非 FlinkSQL 方言或无 taskId 时返回空串
     */
    public String build(Integer taskId) {
        if (taskId == null) {
            return "";
        }
        TaskDTO task;
        try {
            task = taskService.getTaskInfoById(taskId);
        } catch (Exception e) {
            log.warn("FlinkContext: load task {} failed", taskId, e);
            return "";
        }
        if (task == null || !supports(task.getDialect())) {
            return "";
        }

        String snapshot = LocalDateTime.now().format(TS);
        StringBuilder sb = new StringBuilder(1024);
        sb.append("## FlinkSQL 运行环境（AI 自动注入，仅供本次回答使用）\n");
        appendJobBinding(sb, task, snapshot);
        appendCatalogSection(sb, task);
        appendExternalResources(sb);
        appendRules(sb);
        appendRegistry(sb);

        String text = sb.toString();
        int max = SystemConfiguration.getInstances().getLlmFlinkContextMaxChars();
        if (max > 0 && text.length() > max) {
            text = text.substring(0, max) + "\n... (FlinkSQL 上下文超预算，已截断)\n";
        }
        return text;
    }

    // ==================== 1. 作业绑定 ====================

    private void appendJobBinding(StringBuilder sb, TaskDTO task, String snapshot) {
        sb.append("\n### 当前作业\n");
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

    /** 把底层异常翻译成「可读原因」，替代过去的「未绑定数据源」误导性文案。 */
    private String classifyCatalogFailure(Exception e) {
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
        sb.append("\n### 外部资源清单\n");
        SystemConfiguration config = SystemConfiguration.getInstances();
        sb.append("- Kafka 接入地址: `")
                .append(config.getKafkaBootstrapServers())
                .append("`（来源：配置中心-全局配置-Kafka 配置；**Flink 侧必须用 INTERNAL listener 29092**，不是 EXTERNAL 29094）\n");
        sb.append("- Kafka 约定建议: scan.startup.mode=")
                .append(config.getKafkaDefaultScanStartupMode())
                .append("；group.id 前缀=")
                .append(config.getKafkaConsumerGroupPrefix())
                .append('\n');

        try {
            List<DataBase> databases = dataBaseService.listEnabledAll();
            if (databases == null || databases.isEmpty()) {
                return;
            }
            List<DataBase> doris = databases.stream()
                    .filter(db -> db.getType() != null && "Doris".equalsIgnoreCase(db.getType()))
                    .limit(MAX_DORIS_SOURCES)
                    .collect(Collectors.toList());
            if (doris.isEmpty()) {
                sb.append("- 未配置 Doris 数据源（无法列出已知的 Doris 库表）\n");
                return;
            }
            sb.append("- Doris 数据源（sink 侧候选，**物理表必须先建好**，Flink 侧只能写逻辑映射表）:\n");
            for (DataBase db : doris) {
                sb.append("  - `").append(db.getName()).append("`: ");
                try {
                    Driver driver = Driver.build(db.getDriverConfig());
                    sb.append(joinLimit(
                            driver.listSchemas().stream().map(Schema::getName).collect(Collectors.toList())));
                } catch (Exception e) {
                    sb.append("(库清单获取失败: ").append(trim(e.getMessage(), 80)).append(")");
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            log.debug("FlinkContext: list external resources failed", e);
        }
    }

    // ==================== 4. 规则红线 ====================

    private void appendRules(StringBuilder sb) {
        sb.append(RULES);
    }

    // ==================== 5. 命名名册 ====================

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

    /** 静态规则红线：全部沉淀自本项目既有交付的实测结论（参数放置 / 命名唯一性 / Doris 约束 / 时区）。 */
    private static final String RULES = "\n### 参数放置规则（写错位置会静默失效，必须遵守）\n"
            + "- 走 SQL `SET` 有效: `execution.checkpointing.*`（**min-pause 除外**）、`restart-strategy.*`、`table.exec.*`、"
            + "`parallelism.default`、`pipeline.name`\n"
            + "- **只能放「任务自定义配置」**: `execution.checkpointing.min-pause`（SQL SET 实测无效；须用 Flink 1.17 新 key）\n"
            + "- **必须写在 `CREATE TABLE ... WITH(...)`**: connector 全部参数、`sink.parallelism`\n"
            + "- **禁止进 env**: `pipeline.name`（作业唯一标识，进 env 会让同一批作业同名）\n"
            + "- Standalone 模式下 `flink-conf.yaml` 与集群配置模板**均不在作业链路上**（已实测）；"
            + "客户端参数只有 SET 与自定义配置两个通道\n"
            + "\n### 命名唯一性（同一 Kafka topic 多作业共用 group.id 会瓜分分区、数据偏小）\n"
            + "- 必须逐作业唯一: `pipeline.name`、`properties.group.id`、`sink.label-prefix`\n"
            + "\n### Doris 写入约束\n"
            + "- 物理表**必须先建好**；Flink 侧 `CREATE TABLE` 只是逻辑映射，不会建表\n"
            + "- 回撤流（聚合 / Top-N / JOIN）写入必须 `PRIMARY KEY(...) NOT ENFORCED` + 显式 `sink.parallelism`\n"
            + "- 按天聚合结果用 `UNIQUE KEY(stat_date)` + Merge-on-Write 覆盖\n"
            + "\n### 上下文口径\n"
            + "- 本区块是**某一时刻的快照**（见「上下文快照时间」）：catalog / 名册 / 资源清单可能已变化\n"
            + "- 当回答依赖上述快照事实时，请在「依据」中注明该快照时间，便于用户判断是否需要刷新\n"
            + "\n### 时区口径\n"
            + "- canal `servertime` 已是北京时间挂钟字符串：按 STRING 原样截取，**禁止 TIMESTAMP 解析**（会偏 8 小时）\n"
            + "- 作业基线含 `SET 'table.local-time-zone' = 'Asia/Shanghai'`\n";
}
