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

package org.dinky.metadata.driver;

import org.dinky.assertion.Asserts;
import org.dinky.data.model.Column;
import org.dinky.data.model.HiveTableDetail;
import org.dinky.data.model.Schema;
import org.dinky.data.model.Table;
import org.dinky.metadata.config.AbstractJdbcConfig;
import org.dinky.metadata.constant.HiveConstant;
import org.dinky.metadata.convert.HiveTypeConvert;
import org.dinky.metadata.convert.ITypeConvert;
import org.dinky.metadata.enums.DriverType;
import org.dinky.metadata.query.HiveQuery;
import org.dinky.metadata.query.IDBQuery;
import org.dinky.metadata.result.JdbcSelectResult;
import org.dinky.utils.LogUtil;

import org.apache.commons.lang3.StringUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HiveDriver extends AbstractJdbcDriver implements Driver {

    @Override
    public Table getTable(String schemaName, String tableName) {
        List<Table> tables = listTables(schemaName, tableName);
        Table table = null;
        for (Table item : tables) {
            if (Asserts.isEquals(item.getName(), tableName)) {
                table = item;
                break;
            }
        }
        if (Asserts.isNotNull(table)) {
            table.setColumns(listColumns(schemaName, table.getName()));
        }
        return table;
    }

    /**
     * 获取 Hive 表详情：location / fileType / 分区字段 / 普通字段。
     * 通过 `show create table` 的 DDL 解析，避免写死 warehouse.dir / 文件格式 / 分区字段。
     */
    @Override
    public HiveTableDetail getTableDetail(String schemaName, String tableName) {
        HiveTableDetail detail = new HiveTableDetail();
        String ddl = getCreateTableDdl(schemaName, tableName);
        // 列清单保留全部列（含分区列）：ADS 层导 MySQL 时，分区列常作为日期字段使用。
        // `describe` 输出中夹带的分区信息分隔行（'#' 开头）已由 listColumns 跳过。
        detail.setLocation(parseLocation(ddl));
        detail.setFileType(parseFileType(ddl));
        detail.setTableType(parseTableType(ddl));
        List<Column> partitionColumns = parsePartitionColumns(ddl);
        if (partitionColumns.isEmpty()) {
            // 兜底：`show create table` 的 DDL 未包含（或未能解析出）PARTITIONED BY 时，
            // 改从 `describe` 输出的分区信息段解析。
            // 实测：不同 HiveServer2 / Spark ThriftServer 的 DDL 输出格式存在差异，
            // 会导致分区列丢失 —— 表现为向导里 source.partitionColumns 为空、
            // reader.path 丢掉分区段（生成 .../ads_traccar_stat 而非 .../pt=${SYNC_TIME}）。
            partitionColumns = parsePartitionColumnsFromDescribe(schemaName, tableName);
        }
        detail.setPartitionColumns(partitionColumns);
        detail.setColumns(listColumns(schemaName, tableName));
        return detail;
    }

    private String getCreateTableDdl(String schemaName, String tableName) {
        String sql = String.format("show create table `%s`.`%s`", schemaName, tableName);
        // Hive JDBC 会把多行 DDL 拆成多行结果集（每行一段），需拼接所有行还原完整 DDL
        JdbcSelectResult result = query(sql, 1000);
        if (result.isSuccess()
                && result.getRowData() != null
                && !result.getRowData().isEmpty()) {
            StringBuilder ddl = new StringBuilder();
            for (Map<String, Object> row : result.getRowData()) {
                for (Object value : row.values()) {
                    if (value != null) {
                        ddl.append(value.toString()).append("\n");
                    }
                }
            }
            return ddl.toString();
        }
        return "";
    }

    private String parseLocation(String ddl) {
        Matcher matcher =
                Pattern.compile("LOCATION\\s*'([^']+)'", Pattern.DOTALL).matcher(ddl);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 推断文件格式：orc / parquet / text。
     *
     * Hive 的 `show create table` 会展开为 `STORED AS INPUTFORMAT '...OrcInputFormat' OUTPUTFORMAT ...`；
     * 而 Spark（如 STS 数据源）输出的是 `USING orc` / `USING parquet`，或 `STORED AS ORC` 形式。
     * 只认 INPUTFORMAT 会让 Spark 表错误地落到默认值 text（实测踩过），故多种写法都兼容。
     */
    private String parseFileType(String ddl) {
        Matcher matcher =
                Pattern.compile("INPUTFORMAT\\s*'([^']+)'", Pattern.DOTALL).matcher(ddl);
        if (matcher.find()) {
            String inputFormat = matcher.group(1).toLowerCase();
            if (inputFormat.contains("orc")) {
                return "orc";
            }
            if (inputFormat.contains("parquet")) {
                return "parquet";
            }
            if (inputFormat.contains("text")) {
                return "text";
            }
        }
        // Spark 风格：`USING orc` / `USING parquet` / `USING text`
        Matcher usingMatcher = Pattern.compile("USING\\s+([a-zA-Z]+)", Pattern.CASE_INSENSITIVE)
                .matcher(ddl);
        if (usingMatcher.find()) {
            String using = usingMatcher.group(1).toLowerCase();
            if (using.contains("orc")) {
                return "orc";
            }
            if (using.contains("parquet")) {
                return "parquet";
            }
            if (using.contains("text") || using.contains("csv")) {
                return "text";
            }
        }
        // Hive 风格：`STORED AS ORC` / `STORED AS PARQUET` / `STORED AS TEXTFILE`
        Matcher storedAsMatcher = Pattern.compile("STORED\\s+AS\\s+([a-zA-Z]+)", Pattern.CASE_INSENSITIVE)
                .matcher(ddl);
        if (storedAsMatcher.find()) {
            String storedAs = storedAsMatcher.group(1).toLowerCase();
            if (storedAs.contains("orc")) {
                return "orc";
            }
            if (storedAs.contains("parquet")) {
                return "parquet";
            }
            if (storedAs.contains("text") || storedAs.contains("csv")) {
                return "text";
            }
        }
        // 兜底：整段 DDL 中出现关键字（覆盖 tblproperties 等场景）
        String lowerDdl = ddl.toLowerCase();
        if (lowerDdl.contains("orc")) {
            return "orc";
        }
        if (lowerDdl.contains("parquet")) {
            return "parquet";
        }
        return "text";
    }

    private String parseTableType(String ddl) {
        return ddl.trim().toUpperCase().startsWith("CREATE EXTERNAL TABLE") ? "EXTERNAL_TABLE" : "MANAGED_TABLE";
    }

    /**
     * 从 {@code describe} 结果中解析分区列（回退方案）。
     *
     * <p>Hive/Spark 的 {@code describe} 在普通列之后输出分区信息段：
     *
     * <pre>
     *   col_name      data_type   comment
     *   user_id       bigint
     *   ...
     *   # Partition Information
     *   # col_name    data_type   comment
     *   pt            string                  &lt;-- 分区列
     * </pre>
     *
     * <p>当 {@code show create table} 的 DDL 中没有可解析的 PARTITIONED BY
     * （不同 HiveServer2 / Spark ThriftServer 输出格式有差异）时用它兜底，避免分区列丢失。
     */
    private List<Column> parsePartitionColumnsFromDescribe(String schemaName, String tableName) {
        List<Column> columns = new ArrayList<>();
        IDBQuery dbQuery = getDBQuery();
        String sql = String.format("describe `%s`.`%s`", schemaName, tableName);
        try (PreparedStatement preparedStatement = conn.get().prepareStatement(sql);
                ResultSet results = preparedStatement.executeQuery()) {
            boolean inPartitionSection = false;
            List<String> seen = new ArrayList<>();
            while (results.next()) {
                String colName = results.getString(dbQuery.columnName());
                if (colName == null) {
                    continue;
                }
                String trimmed = colName.trim();
                if (trimmed.startsWith("#")) {
                    // '# Partition Information' 之后进入分区段（随后的 '# col_name ...' 仍在段内）
                    inPartitionSection = trimmed.toLowerCase().contains("partition");
                    continue;
                }
                if (!inPartitionSection || trimmed.isEmpty() || seen.contains(trimmed.toLowerCase())) {
                    continue;
                }
                seen.add(trimmed.toLowerCase());
                Column column = new Column();
                column.setName(trimmed);
                String type = results.getString(dbQuery.columnType());
                column.setType(StringUtils.isEmpty(type) ? "string" : type.trim());
                column.setJavaType(getTypeConvert().convert(column));
                columns.add(column);
            }
        } catch (Exception e) {
            log.error(
                    "HiveDriver.parsePartitionColumnsFromDescribe failed, schema={}, table={}",
                    schemaName,
                    tableName,
                    e);
        }
        return columns;
    }

    private List<Column> parsePartitionColumns(String ddl) {
        List<Column> columns = new ArrayList<>();
        Matcher matcher =
                Pattern.compile("PARTITIONED BY\\s*\\((.*?)\\)", Pattern.DOTALL).matcher(ddl);
        if (!matcher.find()) {
            return columns;
        }
        String content = matcher.group(1);
        for (String part : content.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String name = trimmed;
            String type = "";
            Matcher nameMatcher = Pattern.compile("`([^`]+)`").matcher(trimmed);
            if (nameMatcher.find()) {
                name = nameMatcher.group(1);
                type = trimmed.replaceAll("`[^`]+`", "").trim().split("\\s+")[0];
            } else {
                String[] tokens = trimmed.split("\\s+");
                name = tokens[0];
                if (tokens.length > 1) {
                    type = tokens[1];
                }
            }
            Column column = new Column();
            column.setName(name);
            if (StringUtils.isEmpty(type)) {
                type = "string";
            }
            column.setType(type);
            column.setJavaType(getTypeConvert().convert(column));
            columns.add(column);
        }
        return columns;
    }

    /**
     * 解析结果集中目标列的实际列名：优先使用 {@code preferred}，否则回退 {@code aliases}（大小写不敏感）。
     *
     * <p>Spark ThriftServer 与 HiveServer2 的 SHOW 语句返回列名不同：{@code show databases} 在
     * HiveServer2 返回 {@code database_name}、在 Spark ThriftServer 返回 {@code namespace}；
     * {@code show tables} 在 HiveServer2 返回 {@code tab_name}、在 Spark ThriftServer 返回
     * {@code tableName}。直接按列名取值会抛 {@code SQLException: Could not find ...}，故此处做动态解析。
     *
     * @return 实际列名；全部未命中时返回 {@code null}（由调用方记录日志并降级返回空）
     */
    private String resolveColumn(ResultSetMetaData metaData, String preferred, String... aliases) throws SQLException {
        Set<String> labels = new LinkedHashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            labels.add(metaData.getColumnLabel(i).toLowerCase());
        }
        if (labels.contains(preferred.toLowerCase())) {
            return preferred;
        }
        for (String alias : aliases) {
            if (labels.contains(alias.toLowerCase())) {
                return alias;
            }
        }
        return null;
    }

    @Override
    public List<Table> listTables(String schemaName) {
        List<Table> tableList = new ArrayList<>();
        // 空库名会拼出非法的 use 语句（Spark ThriftServer 上同样失败），直接返回并告警
        if (StringUtils.isBlank(schemaName)) {
            log.warn("HiveDriver.listTables skipped: schemaName is blank");
            return tableList;
        }
        PreparedStatement preparedStatement = null;
        ResultSet results = null;
        IDBQuery dbQuery = getDBQuery();
        String sql = dbQuery.tablesSql(schemaName);
        try {
            execute(String.format(HiveConstant.USE_DB, schemaName));
            preparedStatement = conn.get().prepareStatement(sql);
            results = preparedStatement.executeQuery();
            ResultSetMetaData metaData = results.getMetaData();
            List<String> columnList = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columnList.add(metaData.getColumnLabel(i));
            }
            // 表名列表：HiveServer2 为 tab_name，Spark ThriftServer 为 tableName（大小写不敏感回退）
            String tableNameColumn = resolveColumn(metaData, dbQuery.tableName(), "tableName", "tablename");
            if (Asserts.isNullString(tableNameColumn)) {
                log.error(
                        "HiveDriver.listTables failed: table name column not found, schema={}, columns={}",
                        schemaName,
                        columnList);
                return tableList;
            }
            while (results.next()) {
                String tableName = results.getString(tableNameColumn);
                if (Asserts.isNotNullString(tableName)) {
                    Table tableInfo = new Table();
                    tableInfo.setName(tableName);
                    if (columnList.contains(dbQuery.tableComment())) {
                        tableInfo.setComment(results.getString(dbQuery.tableComment()));
                    }
                    tableInfo.setSchema(schemaName);
                    if (columnList.contains(dbQuery.tableType())) {
                        tableInfo.setType(results.getString(dbQuery.tableType()));
                    }
                    if (columnList.contains(dbQuery.catalogName())) {
                        tableInfo.setCatalog(results.getString(dbQuery.catalogName()));
                    }
                    if (columnList.contains(dbQuery.engine())) {
                        tableInfo.setEngine(results.getString(dbQuery.engine()));
                    }
                    tableList.add(tableInfo);
                }
            }
        } catch (Exception e) {
            log.error("HiveDriver.listTables failed, schema={}", schemaName, e);
        } finally {
            close(preparedStatement, results);
        }
        return tableList;
    }

    @Override
    public List<Schema> getSchemasAndTables() {
        return listSchemas();
    }

    @Override
    public List<Schema> listSchemas() {

        List<Schema> schemas = new ArrayList<>();
        PreparedStatement preparedStatement = null;
        ResultSet results = null;
        String schemasSql = getDBQuery().schemaAllSql();
        try {
            preparedStatement = conn.get().prepareStatement(schemasSql);
            results = preparedStatement.executeQuery();
            ResultSetMetaData metaData = results.getMetaData();
            // 库名列表：HiveServer2 为 database_name，Spark ThriftServer 为 namespace（大小写不敏感回退）
            String schemaNameColumn = resolveColumn(metaData, getDBQuery().schemaName(), "namespace", "Database");
            if (Asserts.isNullString(schemaNameColumn)) {
                List<String> columnList = new ArrayList<>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    columnList.add(metaData.getColumnLabel(i));
                }
                log.error("HiveDriver.listSchemas failed: schema name column not found, columns={}", columnList);
                return schemas;
            }
            while (results.next()) {
                String schemaName = results.getString(schemaNameColumn);
                if (Asserts.isNotNullString(schemaName)) {
                    Schema schema = new Schema(schemaName);
                    // 单个库读取失败不应导致整体为空：记录告警后跳过该库
                    try {
                        if (execute(String.format(HiveConstant.USE_DB, schemaName))) {
                            schema.setTables(listTables(schema.getName()));
                        }
                    } catch (Exception e) {
                        log.warn("HiveDriver.listTables failed for schema={}, skipped", schemaName, e);
                    }
                    schemas.add(schema);
                }
            }
        } catch (Exception e) {
            log.error("HiveDriver.listSchemas failed", e);
        } finally {
            close(preparedStatement, results);
        }
        return schemas;
    }

    @Override
    public List<Column> listColumns(String schemaName, String tableName) {
        List<Column> columns = new ArrayList<>();
        PreparedStatement preparedStatement = null;
        ResultSet results = null;
        IDBQuery dbQuery = getDBQuery();
        String tableFieldsSql = dbQuery.columnsSql(schemaName, tableName);
        try {
            preparedStatement = conn.get().prepareStatement(tableFieldsSql);
            results = preparedStatement.executeQuery();
            ResultSetMetaData metaData = results.getMetaData();
            List<String> columnList = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columnList.add(metaData.getColumnLabel(i));
            }
            Integer positionId = 1;
            List<String> seenColumnNames = new ArrayList<>();
            while (results.next()) {
                String currentColumnName = results.getString(dbQuery.columnName());
                if (currentColumnName == null) {
                    continue;
                }
                String trimmedName = currentColumnName.trim();
                // 同名列只保留一次：Spark 的 `describe` 会把分区列先列在普通列区域、
                // 又在分区段之后列一次，不去重会在字段列表里出现重复项（如 pt 出现两次）。
                if (seenColumnNames.contains(trimmedName.toLowerCase())) {
                    continue;
                }
                seenColumnNames.add(trimmedName.toLowerCase());
                // Hive/Spark 的 `describe` 结果里夹着分区信息分隔行与空行，形如：
                //   col_name      data_type
                //   user_id       bigint
                //   ...
                //   # Partition Information
                //   # col_name    data_type    comment
                //   pt            string              <-- 分区列，必须保留
                // '#' 开头的分隔行与空行不是列，跳过（**continue 而非 break**：Hive 输出中
                // 分区列位于分区段之后，break 会把分区列丢掉）；其余行全部保留，含分区列
                // —— ADS 层导 MySQL 时分区列常作为日期字段使用。
                if (trimmedName.isEmpty() || trimmedName.startsWith("#")) {
                    continue;
                }
                Column field = new Column();
                if (StringUtils.isEmpty(currentColumnName)) {
                    break;
                } else {
                    if (columnList.contains(dbQuery.columnName())) {
                        field.setName(currentColumnName);
                    }
                    if (columnList.contains(dbQuery.columnType())) {
                        field.setType(results.getString(dbQuery.columnType()));
                    }
                    if (columnList.contains(dbQuery.columnComment())
                            && Asserts.isNotNull(results.getString(dbQuery.columnComment()))) {
                        String columnComment =
                                results.getString(dbQuery.columnComment()).replaceAll("\"|'", "");
                        field.setComment(columnComment);
                    }
                    field.setPosition(positionId++);
                    field.setJavaType(getTypeConvert().convert(field));
                }
                columns.add(field);
            }
        } catch (SQLException e) {
            log.error("HiveDriver.listColumns failed, schema={}, table={}", schemaName, tableName, e);
        } finally {
            close(preparedStatement, results);
        }
        return columns;
    }

    @Override
    public String getCreateTableSql(Table table) {
        StringBuilder createTable = new StringBuilder();
        PreparedStatement preparedStatement = null;
        ResultSet results = null;
        String createTableSql = getDBQuery().createTableSql(table.getSchema(), table.getName());
        try {
            preparedStatement = conn.get().prepareStatement(createTableSql);
            results = preparedStatement.executeQuery();
            while (results.next()) {
                createTable
                        .append(results.getString(getDBQuery().createTableName()))
                        .append("\n");
            }
        } catch (Exception e) {
            log.error(
                    "HiveDriver.getCreateTableSql failed, schema={}, table={}", table.getSchema(), table.getName(), e);
        } finally {
            close(preparedStatement, results);
        }
        return createTable.toString();
    }

    @Override
    public int executeUpdate(String sql) throws Exception {
        Asserts.checkNullString(sql, "Sql 语句为空");
        String querySQL = sql.trim().replaceAll(";$", "");
        int res = 0;
        try (Statement statement = conn.get().createStatement()) {
            res = statement.executeUpdate(querySQL);
        }
        return res;
    }

    @Override
    public JdbcSelectResult query(String sql, Integer limit) {
        if (Asserts.isNull(limit)) {
            limit = 100;
        }
        JdbcSelectResult result = new JdbcSelectResult();
        List<LinkedHashMap<String, Object>> datas = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        List<String> columnNameList = new ArrayList<>();
        PreparedStatement preparedStatement = null;
        ResultSet results = null;
        int count = 0;
        try {
            String querySQL = sql.trim().replaceAll(";$", "");
            preparedStatement = conn.get().prepareStatement(querySQL);
            results = preparedStatement.executeQuery();
            if (Asserts.isNull(results)) {
                result.setSuccess(true);
                close(preparedStatement, results);
                return result;
            }
            ResultSetMetaData metaData = results.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columnNameList.add(metaData.getColumnLabel(i));
                Column column = new Column();
                column.setName(metaData.getColumnLabel(i));
                column.setType(metaData.getColumnTypeName(i));
                column.setAutoIncrement(metaData.isAutoIncrement(i));
                column.setNullable(metaData.isNullable(i) == 0 ? false : true);
                column.setJavaType(getTypeConvert().convert(column));
                columns.add(column);
            }
            result.setColumns(columnNameList);
            while (results.next()) {
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    data.put(
                            columns.get(i).getName(),
                            getTypeConvert()
                                    .convertValue(
                                            results,
                                            columns.get(i).getName(),
                                            columns.get(i).getType()));
                }
                datas.add(data);
                count++;
                if (count >= limit) {
                    break;
                }
            }
            result.setSuccess(true);
        } catch (Exception e) {
            result.setError(LogUtil.getError(e));
            result.setSuccess(false);
        } finally {
            close(preparedStatement, results);
            result.setRowData(datas);
            return result;
        }
    }

    @Override
    public IDBQuery getDBQuery() {
        return new HiveQuery();
    }

    @Override
    public ITypeConvert<AbstractJdbcConfig> getTypeConvert() {
        return new HiveTypeConvert();
    }

    @Override
    String getDriverClass() {
        return "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    public String getType() {
        return DriverType.HIVE.getValue();
    }

    @Override
    public String getName() {
        return "Hive";
    }

    @Override
    public Map<String, String> getFlinkColumnTypeConversion() {
        HashMap<String, String> map = new HashMap<>();
        map.put("BOOLEAN", "BOOLEAN");
        map.put("TINYINT", "TINYINT");
        map.put("SMALLINT", "SMALLINT");
        map.put("INT", "INT");
        map.put("VARCHAR", "STRING");
        map.put("TEXT", "STRING");
        map.put("DATETIME", "TIMESTAMP");
        return map;
    }
}
