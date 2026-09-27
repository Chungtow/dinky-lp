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

package org.dinky.ai;

import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.metadata.driver.Driver;
import org.dinky.metadata.result.JdbcSelectResult;
import org.dinky.service.DataBaseService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 生成 SQL 的校验器（阶段 0：正确性闭环的核心）。
 *
 * <p>职责：把"生成即结束"升级为"生成 → 执行 → 报错 → 自动修复"闭环。校验执行前先做<b>语句分级</b>：
 * <ul>
 *   <li>{@link SqlType#SELECT}：默认允许（只读，行数上限 {@value #MAX_ROWS}）</li>
 *   <li>{@link SqlType#METADATA}（SHOW / DESC / EXPLAIN）：由配置开关控制</li>
 *   <li>{@link SqlType#DML} / {@link SqlType#DDL}：<b>默认禁止</b>，仅当管理员显式开启才执行</li>
 *   <li>{@link SqlType#UNKNOWN}（含多语句）：一律不执行</li>
 * </ul>
 *
 * <p><b>安全边界</b>：本类只执行 AI 生成的单条语句，且带超时与行数上限；执行结果用于回传给大模型修复，
 * 不用于任何写操作。
 *
 * @since 2026/09/27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlVerifier {

    /** 校验执行时的最大返回行数（与 <code>/api/database/execSql</code> 保持一致） */
    private static final int MAX_ROWS = 500;

    /** markdown 代码块（<code>```sql ... ```</code> 或 <code>``` ... ```</code>） */
    private static final java.util.regex.Pattern SQL_BLOCK_PATTERN =
            java.util.regex.Pattern.compile("```(?:sql|SQL)?\\s*([\\s\\S]*?)```");

    /** 语句类型（决定能否执行） */
    public enum SqlType {
        /** SELECT / WITH（CTE） */
        SELECT,
        /** SHOW / DESC / DESCRIBE / EXPLAIN */
        METADATA,
        /** INSERT / UPDATE / DELETE / MERGE / REPLACE */
        DML,
        /** CREATE / ALTER / DROP / TRUNCATE / GRANT */
        DDL,
        /** 无法识别（含多语句），一律不执行 */
        UNKNOWN
    }

    private final DataBaseService dataBaseService;

    private final ExecutorService verifyExecutor = Executors.newCachedThreadPool();

    /**
     * 判定 SQL 语句类型。
     *
     * @param sql 待判定的 SQL（可含 markdown 代码块包裹）
     * @return 语句类型；空或多语句时为 {@link SqlType#UNKNOWN}
     */
    public SqlType classify(String sql) {
        if (StrUtil.isBlank(sql)) {
            return SqlType.UNKNOWN;
        }
        String cleaned = stripComments(sql).trim();
        // 去掉结尾单个分号后再判断是否为多语句
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (StrUtil.isBlank(cleaned) || cleaned.contains(";")) {
            return SqlType.UNKNOWN;
        }
        String head = cleaned.split("\\s+")[0].toUpperCase();
        switch (head) {
            case "SELECT":
            case "WITH":
                return SqlType.SELECT;
            case "SHOW":
            case "DESC":
            case "DESCRIBE":
            case "EXPLAIN":
                return SqlType.METADATA;
            case "INSERT":
            case "UPDATE":
            case "DELETE":
            case "MERGE":
            case "REPLACE":
                return SqlType.DML;
            case "CREATE":
            case "ALTER":
            case "DROP":
            case "TRUNCATE":
            case "GRANT":
            case "REVOKE":
                return SqlType.DDL;
            default:
                return SqlType.UNKNOWN;
        }
    }

    /**
     * 判断某类语句当前是否允许执行；返回 null 表示允许，否则返回禁止原因。
     *
     * @param type 语句类型
     * @return 禁止原因（null = 允许）
     */
    public String rejectReason(SqlType type) {
        SystemConfiguration config = SystemConfiguration.getInstances();
        if (type == null) {
            return "无法识别的 SQL，已跳过执行校验";
        }
        switch (type) {
            case SELECT:
                return null;
            case METADATA:
                return config.isLlmSqlExecAllowMetadata() ? null : "当前配置不允许执行 SHOW / DESC / EXPLAIN 类语句";
            case DML:
                return config.isLlmSqlExecAllowDml() ? null : "安全策略：不允许 AI 执行 DML 语句（INSERT/UPDATE/DELETE）";
            case DDL:
                return config.isLlmSqlExecAllowDdl() ? null : "安全策略：不允许 AI 执行 DDL 语句（CREATE/ALTER/DROP）";
            default:
                return "无法识别或多语句 SQL，已跳过执行校验";
        }
    }

    /**
     * 执行校验。
     *
     * @param databaseId 数据源 id（来自当前作业上下文）
     * @param sql 待校验的 SQL
     * @return 校验结果（含是否执行、成功与否、错误原文、行数、耗时）
     */
    public VerifyResult verify(Integer databaseId, String sql) {
        SqlType type = classify(sql);
        VerifyResult result = new VerifyResult();
        result.setSqlType(type == null ? null : type.name());

        String reason = rejectReason(type);
        if (reason != null) {
            result.setExecuted(false);
            result.setRejected(true);
            result.setError(reason);
            return result;
        }
        if (databaseId == null) {
            result.setExecuted(false);
            result.setError("未绑定数据源，无法执行校验");
            return result;
        }

        int timeoutSeconds = Math.max(SystemConfiguration.getInstances().getLlmSqlExecTimeout(), 1);
        Future<VerifyResult> future = verifyExecutor.submit(() -> doExecute(databaseId, sql));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            result.setExecuted(false);
            result.setError("校验执行超时（超过 " + timeoutSeconds + " 秒），已中断");
            return result;
        } catch (Exception e) {
            log.warn("SQL verify failed, databaseId: {}", databaseId, e);
            result.setExecuted(false);
            result.setError("校验执行失败：" + e.getMessage());
            return result;
        }
    }

    /** 真正执行查询（在独立线程中运行，便于超时中断） */
    private VerifyResult doExecute(Integer databaseId, String sql) {
        VerifyResult result = new VerifyResult();
        result.setExecuted(true);
        long start = System.currentTimeMillis();
        DataBase dataBase = dataBaseService.getById(databaseId);
        if (dataBase == null) {
            result.setError("数据源不存在（id=" + databaseId + "）");
            return result;
        }
        try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
            JdbcSelectResult selectResult = driver.query(sql, MAX_ROWS);
            result.setCostMs(System.currentTimeMillis() - start);
            if (selectResult == null) {
                result.setError("执行未返回结果");
                return result;
            }
            result.setSuccess(selectResult.isSuccess());
            result.setError(selectResult.getError());
            result.setRowCount(selectResult.getRowData() == null ? 0 : selectResult.getRowData().size());
            if (!selectResult.isSuccess() && StrUtil.isBlank(selectResult.getError())) {
                result.setError("SQL 执行失败（数据源未返回具体错误）");
            }
            return result;
        } catch (Exception e) {
            result.setCostMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            result.setError("执行异常：" + e.getMessage());
            return result;
        }
    }

    /** 去除 SQL 中的注释，避免注释里的关键字干扰语句分级 */
    private String stripComments(String sql) {
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("--[^\\n]*", " ");
    }

    /**
     * 从大模型输出中提取待校验的 SQL：取<b>最后一个</b>代码块（模型常在解释后再给出最终 SQL）。
     *
     * @param content 大模型输出的完整文本
     * @return 提取到的 SQL；无代码块时返回 null
     */
    public String extractSql(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        java.util.regex.Matcher matcher = SQL_BLOCK_PATTERN.matcher(content);
        String last = null;
        while (matcher.find()) {
            String block = StrUtil.trim(matcher.group(1));
            if (StrUtil.isNotBlank(block)) {
                last = block;
            }
        }
        if (last == null) {
            return null;
        }
        // 去掉结尾分号，便于统一拼接与展示
        return last.endsWith(";") ? last.substring(0, last.length() - 1).trim() : last;
    }

    /** 校验结果 */
    @Getter
    @Setter
    public static class VerifyResult {
        /** 语句类型（SELECT / METADATA / DML / DDL / UNKNOWN） */
        private String sqlType;
        /** 是否真正执行了（false 表示被策略拒绝或无法执行） */
        private boolean executed;
        /** 是否被安全策略拒绝 */
        private boolean rejected;
        /** 执行是否成功 */
        private boolean success;
        /** 返回行数（仅 SELECT 有意义） */
        private int rowCount;
        /** 执行耗时（毫秒） */
        private long costMs;
        /** 数据源返回的原始错误 */
        private String error;

        public VerifyResult() {}

        /** 是否可用于"回传大模型修复"：执行了但失败，且拿到了具体错误 */
        public boolean isRepairable() {
            return executed && !success && StrUtil.isNotBlank(error);
        }
    }

    @PreDestroy
    public void destroy() {
        verifyExecutor.shutdownNow();
    }
}
