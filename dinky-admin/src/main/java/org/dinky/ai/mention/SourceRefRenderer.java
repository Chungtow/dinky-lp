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

import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.Schema;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Table;
import org.dinky.metadata.driver.Driver;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @source/} 引用的渲染器（AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>四级渐进披露</b>：数据源级只给库清单、库级只给表名+注释、表级给字段+<b>Flink DDL 骨架</b>、
 * 字段级只给单列。这样"@ 一下"不会把整库几百张表灌进上下文。
 *
 * <p><b>Flink DDL 骨架</b>复用 {@code DataBaseService#getSqlGeneration} 的同一条实现
 * （{@code table.getFlinkTableSql(数据源名, flinkTemplate)}）：它按<b>该数据源的 flinkTemplate</b>
 * 渲染，因此 MySQL 数据源给出 mysql-cdc/jdbc 模板、Doris 数据源给出 doris connector 模板，
 * 与"数据源详情页生成 SQL"口径完全一致（无需另写生成器）。
 *
 * <p><b>为什么不复用 {@code metadata_schema} 缓存</b>：该缓存无 TTL，陈旧 schema 会直接污染
 * 生成的 SQL；用户显式 {@code @} 引用即代表"我要此刻的最新结构"，故直连 driver（P1 决策 D5）。
 *
 * @since 2026/10/08
 */
@Slf4j
@Component
public class SourceRefRenderer {

    /** 引用前缀（与前端候选、后端正则同源，改名等于协议变更） */
    public static final String PREFIX = "@source/";

    private static final int MAX_SCHEMAS = 50;

    private static final int MAX_TABLES = 300;

    private static final int MAX_COLUMNS = 40;

    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 渲染一条 {@code @source/} 引用。
     *
     * <p><b>永不抛异常</b>：任何失败都转成一行可读的降级说明（与 P0 的"可解释降级"一致），
     * 避免一条写错的引用把整轮对话打断。
     *
     * @param rawPath {@code @source/} 之后的路径
     * @param sourcesByName 本租户启用中的数据源索引（请求线程：{@code listEnabledAll()}；
     *     工具线程：请求线程预解析的快照）
     * @return 注入 prompt 的文本块
     */
    public String render(String rawPath, Map<String, DataBase> sourcesByName) {
        SourceRefResolver.Ref ref = SourceRefResolver.parse(rawPath);
        if (ref == null) {
            return "### "
                    + PREFIX
                    + StrUtil.nullToEmpty(rawPath)
                    + "\n- 无法解析该引用：格式应为 `@source/<数据源名>[/<库>[/<表>[.<字段>]]]`，"
                    + "各段只允许字母、数字、下划线、连字符与点，最多 4 段。\n";
        }
        DataBase dataBase = SourceRefResolver.resolve(sourcesByName, ref.getDatasource());
        if (dataBase == null) {
            return failure(ref, "本租户下没有启用中的同名数据源。" + SourceRefResolver.availableNames(sourcesByName));
        }
        StringBuilder sb = new StringBuilder(320);
        try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
            appendHeader(sb, ref, dataBase);
            switch (ref.level()) {
                case 1:
                    appendDatasourceLevel(sb, driver, ref);
                    break;
                case 2:
                    appendDatabaseLevel(sb, driver, ref);
                    break;
                case 3:
                    appendTableLevel(sb, driver, ref, dataBase);
                    break;
                default:
                    appendColumnLevel(sb, driver, ref);
                    break;
            }
        } catch (Exception e) {
            log.warn("Render {} failed, path: {}, message: {}", PREFIX, ref.getRaw(), e.getMessage());
            return failure(ref, "读取元数据失败（" + classify(e) + "）。请确认该数据源连接可用、库表名正确。");
        }
        return applyBudget(sb.toString(), SystemConfiguration.getInstances().getLlmSourceExpandMaxChars());
    }

    /** 标题与来源行（四级共用）。 */
    private void appendHeader(StringBuilder sb, SourceRefResolver.Ref ref, DataBase dataBase) {
        sb.append("### ")
                .append(PREFIX)
                .append(ref.getNormalized())
                .append("（外部系统物理对象 · 快照 ")
                .append(LocalDateTime.now().format(TS))
                .append("）\n");
        sb.append("- 来源: ")
                .append(StrUtil.blankToDefault(dataBase.getType(), "未知类型"))
                .append(" 数据源 `")
                .append(ref.getDatasource())
                .append("`");
        if (ref.getDatabase() != null) {
            sb.append(" / 库 `").append(ref.getDatabase()).append('`');
        }
        if (ref.getTable() != null) {
            sb.append(" / 表 `").append(ref.getTable()).append('`');
        }
        if (ref.getColumn() != null) {
            sb.append(" / 字段 `").append(ref.getColumn()).append('`');
        }
        sb.append('\n');
    }

    /** 数据源级：只列库清单（不展开表），并给出下一级展开用法。 */
    private void appendDatasourceLevel(StringBuilder sb, Driver driver, SourceRefResolver.Ref ref) {
        List<Schema> schemas = driver.listSchemas();
        if (CollUtil.isEmpty(schemas)) {
            sb.append("- 未能枚举到任何库（该数据源可能无权限，或连接不可用）\n");
            return;
        }
        List<String> names = new ArrayList<>();
        for (Schema schema : schemas) {
            if (schema != null && StrUtil.isNotBlank(schema.getName())) {
                names.add(schema.getName());
            }
        }
        sb.append("- 库清单（共 ")
                .append(names.size())
                .append(" 个）: ")
                .append(joinLimit(names, MAX_SCHEMAS))
                .append('\n');
        sb.append("- 展开用法: `")
                .append(PREFIX)
                .append(ref.getDatasource())
                .append("/<库>` 看表名；再加 `/<表>` 看字段与 Flink DDL 骨架\n");
    }

    private void appendDatabaseLevel(StringBuilder sb, Driver driver, SourceRefResolver.Ref ref) {
        List<Table> tables = driver.listTables(ref.getDatabase());
        if (CollUtil.isEmpty(tables)) {
            sb.append("- 未获取到表：库名可能不存在，或当前账号无权查看\n");
            return;
        }
        sb.append("- 表清单（共 ").append(tables.size()).append(" 张，此处只列名、不展开字段）:\n");
        int limit = Math.min(tables.size(), MAX_TABLES);
        for (int i = 0; i < limit; i++) {
            Table table = tables.get(i);
            if (table == null) {
                continue;
            }
            sb.append("    - ").append(table.getName());
            if (StrUtil.isNotBlank(table.getComment())) {
                sb.append(" -- ").append(trim(table.getComment(), 60));
            }
            sb.append('\n');
        }
        if (tables.size() > limit) {
            sb.append("    ... (仅列前 ").append(limit).append(" 张)\n");
        }
        sb.append("- 展开用法: `")
                .append(PREFIX)
                .append(ref.getDatasource())
                .append('/')
                .append(ref.getDatabase())
                .append("/<表>` 看字段与 Flink DDL 骨架\n");
    }

    private void appendTableLevel(StringBuilder sb, Driver driver, SourceRefResolver.Ref ref, DataBase dataBase) {
        List<Column> columns = driver.listColumns(ref.getDatabase(), ref.getTable());
        if (CollUtil.isEmpty(columns)) {
            sb.append("- 未获取到字段：表名可能不存在，或当前账号无权访问\n");
            return;
        }
        sb.append("- 字段（共 ").append(columns.size()).append(" 列）:\n");
        int limit = Math.min(columns.size(), MAX_COLUMNS);
        for (int i = 0; i < limit; i++) {
            Column column = columns.get(i);
            if (column == null) {
                continue;
            }
            sb.append("    - ").append(column.getName()).append(' ').append(StrUtil.nullToEmpty(column.getType()));
            if (column.isKeyFlag()) {
                sb.append(" [PK]");
            }
            if (StrUtil.isNotBlank(column.getComment())) {
                sb.append(" -- ").append(trim(column.getComment(), 60));
            }
            sb.append('\n');
        }
        if (columns.size() > limit) {
            sb.append("    ... (共 ")
                    .append(columns.size())
                    .append(" 列，仅展示前 ")
                    .append(limit)
                    .append(" 列)\n");
        }
        appendFlinkDdl(sb, driver, ref, dataBase);
        sb.append("- ⚠️ 这是【外部系统物理表】，不是当前 Flink Catalog 里的表；").append("在作业里引用前需先用上面的 DDL 注册（或确认 env 已注册同名 catalog）。\n");
    }

    /** Flink DDL 骨架：与「数据源详情页 → 生成 SQL」同实现，失败静默（字段已给出，骨架属加分项）。 */
    private void appendFlinkDdl(StringBuilder sb, Driver driver, SourceRefResolver.Ref ref, DataBase dataBase) {
        try {
            Table table = driver.getTable(ref.getDatabase(), ref.getTable());
            if (table == null) {
                return;
            }
            String ddl = table.getFlinkTableSql(dataBase.getName(), dataBase.getFlinkTemplate());
            if (StrUtil.isBlank(ddl)) {
                return;
            }
            sb.append("- 可直接使用的 Flink DDL 骨架（按该数据源的 `flinkTemplate` 渲染；connector 参数可按需调整）:\n")
                    .append("```sql\n")
                    .append(ddl.trim())
                    .append("\n```\n");
        } catch (Exception e) {
            log.debug("Build flink ddl skeleton failed, table: {}.{}", ref.getDatabase(), ref.getTable());
        }
    }

    private void appendColumnLevel(StringBuilder sb, Driver driver, SourceRefResolver.Ref ref) {
        List<Column> columns = driver.listColumns(ref.getDatabase(), ref.getTable());
        if (CollUtil.isEmpty(columns)) {
            sb.append("- 未获取到字段：表名可能不存在，或当前账号无权访问\n");
            return;
        }
        Column hit = null;
        for (Column column : columns) {
            if (column != null && ref.getColumn().equalsIgnoreCase(column.getName())) {
                hit = column;
                break;
            }
        }
        if (hit == null) {
            sb.append("- 未找到字段 `")
                    .append(ref.getColumn())
                    .append("`：该表共 ")
                    .append(columns.size())
                    .append(" 列，请核对字段名\n");
            return;
        }
        sb.append("- 字段详情: ").append(hit.getName()).append(' ').append(StrUtil.nullToEmpty(hit.getType()));
        if (hit.isKeyFlag()) {
            sb.append(" [PK]");
        }
        if (StrUtil.isNotBlank(hit.getComment())) {
            sb.append(" -- ").append(trim(hit.getComment(), 80));
        }
        sb.append('\n');
        sb.append("- 该表共 ")
                .append(columns.size())
                .append(" 列；如需完整结构，引用 `")
                .append(PREFIX)
                .append(ref.getDatasource())
                .append('/')
                .append(ref.getDatabase())
                .append('/')
                .append(ref.getTable())
                .append("`\n");
    }

    /** 把底层异常翻译成一句人话（连接类问题占多数）——失败提示必须可解释，不能只说"失败了"。 */
    private static String classify(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("access denied") || lower.contains("authentication") || lower.contains("password")) {
            return "账号或口令错误";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时";
        }
        if (lower.contains("refused") || lower.contains("unknown host") || lower.contains("connect")) {
            return "数据源不可达";
        }
        if (lower.contains("does not exist") || lower.contains("unknown database") || lower.contains("not found")) {
            return "库或表不存在";
        }
        return "错误类型 " + root.getClass().getSimpleName();
    }

    private static String applyBudget(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (该 @ 引用内容超预算，已截断)\n";
    }

    private static String failure(SourceRefResolver.Ref ref, String reason) {
        return "### " + PREFIX + ref.getNormalized() + "\n- ❌ " + reason + "\n";
    }

    private static String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private static String joinLimit(List<String> items, int limit) {
        if (CollUtil.isEmpty(items)) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        int size = Math.min(items.size(), limit);
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(items.get(i));
        }
        if (items.size() > size) {
            sb.append(" …等 ").append(items.size()).append(" 个");
        }
        return sb.toString();
    }
}
