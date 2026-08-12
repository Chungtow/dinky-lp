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

package org.dinky.service.task;

import org.dinky.aop.ProcessAspect;
import org.dinky.config.Dialect;
import org.dinky.context.ConsoleContextHolder;
import org.dinky.data.annotations.SupportDialect;
import org.dinky.data.dto.SqlDTO;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.result.SqlExplainResult;
import org.dinky.job.Job;
import org.dinky.job.JobResult;
import org.dinky.metadata.result.JdbcSelectResult;
import org.dinky.service.DataBaseService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;

import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * SparkSqlTask executes Spark SQL statements through the spark-sql CLI.
 * Uses spark-sql --master yarn --deploy-mode client to submit to YARN.
 *
 * <p>YARN cluster connectivity is determined by the HADOOP_CONF_DIR environment
 * variable, which is mounted from the host's Hadoop configuration directory.
 * This follows the same convention as Apache Zeppelin and DolphinScheduler,
 * where Spark configuration is handled at the deployment/infrastructure layer
 * rather than the application layer.
 *
 * @since 1.2.5-lp
 */
@Slf4j
@SupportDialect(Dialect.SPARK_SQL)
public class SparkSqlTask extends BaseTask {

    private static final String DEFAULT_SPARK_HOME = "/opt/spark";
    private static final String DEFAULT_HADOOP_CONF_DIR = "/opt/hadoop/conf";
    private static final long DEFAULT_TIMEOUT_MINUTES = 60;
    private static final int DESTROY_WAIT_SECONDS = 10;

    /**
     * Task-level execution mode key stored in {@code configJson.customConfig}.
     * Value: {@code cli} (default, spark-sql sub-process) or {@code jdbc} (Spark ThriftServer).
     */
    private static final String EXECUTION_MODE_KEY = "spark.sql.execution.mode";

    private static final String EXECUTION_MODE_JDBC = "jdbc";

    /**
     * Static registry of running SparkSqlTask instances, keyed by task name.
     *
     * <p>{@link BaseTask#getTask()} creates a new instance per execution, so {@link #stop()}
     * must look up the live instance through this registry to interrupt/kill its process.
     */
    private static final Map<String, SparkSqlTask> RUNNING_TASKS = new ConcurrentHashMap<>();

    /**
     * The underlying spark-sql process. Declared as an instance field so the finally block
     * (and {@link #destroyProcess()}) can always reach and clean it up, regardless of how
     * {@link #execute()} exits (normal / interrupted / exception / timeout).
     */
    private volatile Process process;

    /**
     * The thread running the blocking JDBC call in JDBC (ThriftServer) mode.
     * Used by {@link #stop()} to interrupt the call. Unlike CLI mode there is no
     * sub-process to destroy, so the YARN job on the ThriftServer may keep running.
     */
    private volatile Thread execThread;

    public SparkSqlTask(TaskDTO task) {
        super(task);
    }

    @Override
    public List<SqlExplainResult> explain() {
        return Collections.emptyList();
    }

    @Override
    public JobResult StreamExecute() {
        // Spark SQL runs synchronously via CLI, support "debug" (StreamExecute) by delegating to execute()
        try {
            return execute();
        } catch (Exception e) {
            log.error("Spark SQL StreamExecute failed", e);
            JobResult result = new JobResult();
            result.setError("Spark SQL execution error: " + e.getMessage());
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }
    }

    @Override
    public JobResult execute() throws Exception {
        String sql = replaceTaskVariables(task.getStatement());
        if (sql == null || sql.trim().isEmpty()) {
            JobResult result = new JobResult();
            result.setError("Spark SQL statement is empty");
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }

        // JDBC mode (Spark ThriftServer): no sub-process, warm response.
        // Executes through DataBaseService → Hive JDBC → hivespark03:10015
        if (isJdbcMode()) {
            log.info("Executing Spark SQL task via JDBC (ThriftServer): {}", task.getName());
            RUNNING_TASKS.put(task.getName(), this);
            execThread = Thread.currentThread();
            try {
                return executeViaJdbc(sql);
            } finally {
                RUNNING_TASKS.remove(task.getName());
                execThread = null;
            }
        }

        log.info("Executing Spark SQL task: {}", task.getName());

        // Write SQL to a temporary file
        File sqlFile = null;
        try {
            // Register this instance so stop()/cancel can reach the live process
            RUNNING_TASKS.put(task.getName(), this);

            sqlFile = File.createTempFile("dinky-spark-", ".sql");
            try (FileWriter writer = new FileWriter(sqlFile)) {
                writer.write(sql);
                writer.flush();
            }
            log.info("Spark SQL written to temp file: {}", sqlFile.getAbsolutePath());

            String sparkHome = System.getenv().getOrDefault("SPARK_HOME", DEFAULT_SPARK_HOME);
            String sparkSqlCmd = sparkHome + File.separator + "bin" + File.separator + "spark-sql";
            String hadoopConfDir = System.getenv().getOrDefault("HADOOP_CONF_DIR", DEFAULT_HADOOP_CONF_DIR);

            ProcessBuilder pb = new ProcessBuilder(
                    sparkSqlCmd,
                    "--master",
                    "yarn",
                    "--deploy-mode",
                    "client",
                    "--name",
                    task.getName(),
                    "--hiveconf",
                    "hive.cli.print.header=true",
                    "-f",
                    sqlFile.getAbsolutePath());
            // DO NOT merge stderr into stdout — stdout is for structured results,
            // stderr is for WARN/INFO logs
            pb.redirectErrorStream(false);
            pb.environment().put("HADOOP_CONF_DIR", hadoopConfDir);

            log.info("Spark SQL: HADOOP_CONF_DIR={}", hadoopConfDir);

            process = pb.start();

            // Capture MDC context for real-time log streaming to frontend console.
            // The reader threads are spawned in separate threads and do not inherit MDC,
            // so we capture them here in the main thread (which is within @ExecuteProcess / @ProcessStep).
            String processName = MDC.get(ProcessAspect.PROCESS_NAME);
            String stepPid = MDC.get(ProcessAspect.PROCESS_STEP);

            // Read stdout (structured results) separately from stderr (logs)
            StringBuilder stdoutData = new StringBuilder();
            StringBuilder stderrData = new StringBuilder();

            Thread stdoutThread = new Thread(
                    () -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stdoutData.append(line).append("\n");
                                // Push Spark log lines from stdout to frontend console
                                if (isSparkLogLine(line.trim())) {
                                    log.info("[SparkSQL] {}", line);
                                    if (processName != null && stepPid != null) {
                                        ConsoleContextHolder.getInstances()
                                                .appendLog(processName, stepPid, "[SparkSQL] " + line, true);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Error reading spark-sql stdout", e);
                        }
                    },
                    "spark-sql-stdout");
            stdoutThread.setDaemon(true);

            Thread stderrThread = new Thread(
                    () -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                log.info("[SparkSQL] {}", line);
                                stderrData.append(line).append("\n");
                                // Push stderr to frontend console in real time
                                if (processName != null && stepPid != null) {
                                    ConsoleContextHolder.getInstances()
                                            .appendLog(processName, stepPid, "[SparkSQL] " + line, true);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Error reading spark-sql stderr", e);
                        }
                    },
                    "spark-sql-stderr");
            stderrThread.setDaemon(true);

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(5000);
                stderrThread.join(5000);
                JobResult result = new JobResult();
                result.setError("Spark SQL execution timeout after " + DEFAULT_TIMEOUT_MINUTES + " minutes");
                result.setStatus(Job.JobStatus.FAILED);
                result.setSuccess(false);
                return result;
            }

            stdoutThread.join(30000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                log.info("Spark SQL task completed successfully: {}", task.getName());
                JobResult result = new JobResult();
                result.setStatus(Job.JobStatus.SUCCESS);
                result.setSuccess(true);

                // Parse stdout into structured tabular result for the frontend "结果" tab
                JdbcSelectResult selectResult = parseStdoutToResult(stdoutData.toString());
                int rowCount = selectResult.getRowData() != null
                        ? selectResult.getRowData().size()
                        : 0;
                result.setStatement("Spark SQL executed successfully, fetched " + rowCount + " row(s).");
                result.setResult(selectResult);
                result.setResults(Collections.singletonList(selectResult));
                return result;
            } else {
                String fullOutput = stderrData.toString() + "\n" + stdoutData.toString();
                log.error("Spark SQL task failed with exit code {}: {}", exitCode, fullOutput);
                JobResult result = new JobResult();
                result.setError("Spark SQL exited with code " + exitCode);
                result.setStatus(Job.JobStatus.FAILED);
                result.setSuccess(false);
                result.setStatement(fullOutput);
                return result;
            }
        } catch (InterruptedException e) {
            // UI stop/cancel → killProcess → t.interrupt() arrives here
            log.info("Spark SQL task interrupted (stop/cancel), process cleaned up: {}", task.getName());
            Thread.currentThread().interrupt(); // restore interrupt flag
            JobResult result = new JobResult();
            result.setError("Spark SQL execution interrupted");
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        } catch (Exception e) {
            log.error("Spark SQL execution error, process cleaned up: {}", task.getName(), e);
            JobResult result = new JobResult();
            result.setError("Spark SQL execution error: " + e.getMessage());
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        } finally {
            // Unified cleanup on every exit path (normal / interrupted / exception / timeout):
            // destroyProcess() checks isAlive(), so it is a no-op for already-exited processes.
            destroyProcess();
            if (sqlFile != null && sqlFile.exists()) {
                sqlFile.delete();
            }
            RUNNING_TASKS.remove(task.getName());
        }
    }

    /**
     * Determine whether this task runs in JDBC (Spark ThriftServer) mode.
     *
     * <p>Reads {@code spark.sql.execution.mode} from {@code configJson.customConfig}.
     * Absent or empty means the default {@code cli} mode (spark-sql sub-process).
     */
    private boolean isJdbcMode() {
        if (task.getConfigJson() == null || task.getConfigJson().getCustomConfig() == null) {
            return false;
        }
        String mode = task.getConfigJson().getCustomConfigValue(EXECUTION_MODE_KEY);
        return EXECUTION_MODE_JDBC.equalsIgnoreCase(mode);
    }

    /**
     * Execute the SQL through the Spark ThriftServer via Hive JDBC.
     *
     * <p>This mode requires a data source (Hive type) pointing to the ThriftServer
     * ({@code hivespark03:10015}). The blocking JDBC call runs on {@link #execThread}
     * so {@link #stop()} can interrupt it; note that interrupting does not cancel the
     * underlying YARN job on the ThriftServer.
     */
    private JobResult executeViaJdbc(String sql) {
        // JDBC mode requires a data source pointing to the ThriftServer
        if (task.getDatabaseId() == null) {
            log.warn("Spark SQL task in JDBC mode but no data source assigned: {}", task.getName());
            JobResult result = new JobResult();
            result.setStatement(sql);
            result.setError("JDBC mode (spark.sql.execution.mode=jdbc) requires a data source pointing to "
                    + "Spark ThriftServer (hivespark03:10015). Please select a Hive data source first.");
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }

        // JDBC mode has no per-line real-time logs; first version pushes an execution summary
        String processName = MDC.get(ProcessAspect.PROCESS_NAME);
        String stepPid = MDC.get(ProcessAspect.PROCESS_STEP);
        if (processName != null && stepPid != null) {
            ConsoleContextHolder.getInstances()
                    .appendLog(
                            processName,
                            stepPid,
                            "[SparkSQL] Executing via JDBC (ThriftServer), databaseId=" + task.getDatabaseId(),
                            true);
        }

        try {
            DataBaseService dataBaseService = SpringUtil.getBean(DataBaseService.class);
            SqlDTO sqlDTO = SqlDTO.build(sql, task.getDatabaseId(), task.getMaxRowNum());
            return dataBaseService.executeCommonSql(sqlDTO);
        } catch (Exception e) {
            // stop() → execThread.interrupt() may surface as InterruptedException or be wrapped
            // as the cause of a SQLException by the JDBC driver
            if (Thread.currentThread().isInterrupted() || isInterruptCause(e)) {
                log.info("Spark SQL task (JDBC mode) interrupted (stop/cancel): {}", task.getName());
                Thread.currentThread().interrupt();
                JobResult result = new JobResult();
                result.setStatement(sql);
                result.setError("Spark SQL execution interrupted (JDBC mode)");
                result.setStatus(Job.JobStatus.FAILED);
                result.setSuccess(false);
                return result;
            }
            log.error("Spark SQL execution error (JDBC mode): {}", task.getName(), e);
            JobResult result = new JobResult();
            result.setStatement(sql);
            result.setError("Spark SQL execution error: " + e.getMessage());
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }
    }

    /**
     * Check whether an exception is (or wraps) an {@link InterruptedException}.
     */
    private boolean isInterruptCause(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Parse spark-sql stdout into structured JdbcSelectResult.
     *
     * <p>When multiple SQL statements are executed (e.g. CREATE, INSERT, SELECT),
     * spark-sql CLI outputs multiple result sets sequentially. This method:
     * <ol>
     *   <li>Splits stdout into result sets separated by "Time taken:" lines</li>
     *   <li>Filters out all known non-data lines (logs, DDL response codes, etc.)</li>
     *   <li>Selects the <b>last</b> non-empty result set (usually the final SELECT)</li>
     *   <li>Parses the tab-separated table with header and data rows</li>
     * </ol>
     *
     * <p>With --hiveconf hive.cli.print.header=true, a typical result set looks like:
     * <pre>
     * col1\tcol2
     * val1\tval2
     * val3\tval4
     * Time taken: 0.5 seconds, Fetched 2 row(s)
     * </pre>
     */
    private JdbcSelectResult parseStdoutToResult(String stdout) {
        JdbcSelectResult selectResult = JdbcSelectResult.buildResult();

        if (stdout == null || stdout.trim().isEmpty()) {
            selectResult.success();
            selectResult.setColumns(new ArrayList<>());
            selectResult.setRowData(new ArrayList<>());
            return selectResult;
        }

        List<String> lines = Arrays.asList(stdout.split("\n"));

        // Split stdout into result sets separated by "Time taken:" lines.
        // Each SQL statement produces a result set terminated by a timing summary.
        List<List<String>> resultSets = new ArrayList<>();
        List<String> currentSet = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Timing summary marks the end of the current statement's output
            if (trimmed.startsWith("Time taken")) {
                if (!currentSet.isEmpty()) {
                    resultSets.add(new ArrayList<>(currentSet));
                    currentSet.clear();
                }
                continue;
            }
            // Skip Spark log lines that leak into stdout
            if (isSparkLogLine(trimmed)) {
                continue;
            }
            // Skip Hive CLI response codes for DDL/DML statements
            if (isHiveResponseLine(trimmed)) {
                continue;
            }
            currentSet.add(trimmed);
        }

        // The final statement may not have a trailing "Time taken" line
        if (!currentSet.isEmpty()) {
            resultSets.add(currentSet);
        }

        // Pick the last non-empty result set — this is almost always the final SELECT/SHOW
        List<String> targetSet = null;
        for (int i = resultSets.size() - 1; i >= 0; i--) {
            if (!resultSets.get(i).isEmpty()) {
                targetSet = resultSets.get(i);
                break;
            }
        }

        if (targetSet == null || targetSet.isEmpty()) {
            selectResult.success();
            selectResult.setColumns(new ArrayList<>());
            selectResult.setRowData(new ArrayList<>());
            return selectResult;
        }

        // First line of the target set is the header
        String headerLine = targetSet.get(0);
        List<String> columns = Arrays.asList(headerLine.split("\t", -1));

        // Remaining lines are data rows
        List<LinkedHashMap<String, Object>> rowData = new ArrayList<>();
        for (int i = 1; i < targetSet.size(); i++) {
            String dataLine = targetSet.get(i);
            String[] values = dataLine.split("\t", -1);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int j = 0; j < columns.size(); j++) {
                String val = (j < values.length) ? values[j] : "";
                row.put(columns.get(j), val);
            }
            rowData.add(row);
        }

        selectResult.setColumns(columns);
        selectResult.setRowData(rowData);
        selectResult.setTotal(rowData.size());
        selectResult.success();
        return selectResult;
    }

    /**
     * Heuristic: determine if a line from spark-sql stdout is a log line rather than result data.
     */
    private boolean isSparkLogLine(String line) {
        // Date-prefixed log lines: "26/07/27 03:45:54 WARN ..."
        if (line.matches("^\\d{2}/\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+(WARN|INFO|ERROR|DEBUG|TRACE).*")) {
            return true;
        }
        // Spark startup / runtime messages printed to stdout
        if (line.startsWith("Spark master:")
                || line.startsWith("ps: ")
                || line.startsWith("BusyBox")
                || line.startsWith("Usage:")
                || line.startsWith("Show list of")
                || line.startsWith("To adjust logging level")
                || line.startsWith("Setting default log level")) {
            return true;
        }
        // Spark job progress lines (common when INSERT/CTAS triggers a job)
        if (line.startsWith("Query ID = ")
                || line.startsWith("Total jobs = ")
                || line.startsWith("Launching Job ")
                || line.startsWith("Job ")
                || line.startsWith("Stage-")) {
            return true;
        }
        // Standalone log level lines
        if (line.startsWith("log4j:") || line.startsWith("SLF4J:")) {
            return true;
        }
        return false;
    }

    /**
     * Heuristic: determine if a line is a Hive CLI response code for DDL/DML statements.
     *
     * <p>Hive CLI prints "Response code" or "OK" for non-SELECT statements such as
     * CREATE, INSERT, DROP, etc. These must be filtered out so they are not mistaken
     * for result headers.
     */
    private boolean isHiveResponseLine(String line) {
        return line.equals("Response code") || line.equals("OK") || line.equals("No rows affected");
    }

    @Override
    public boolean stop() {
        SparkSqlTask running = RUNNING_TASKS.get(task.getName());
        if (running == null) {
            return false; // no live process for this task
        }
        if (running.isJdbcMode()) {
            // JDBC mode: interrupt the blocking JDBC call. Unlike CLI mode there is no
            // sub-process to kill, so the YARN job on the ThriftServer may keep running.
            Thread t = running.execThread;
            if (t != null && t.isAlive()) {
                log.info("Spark SQL task (JDBC mode) stopping, interrupting execution thread: {}", task.getName());
                t.interrupt();
            }
            return true;
        }
        running.destroyProcess();
        return true;
    }

    /**
     * Terminate the underlying spark-sql process. SIGTERM first to let Spark run its
     * shutdown hooks (SparkContext.stop → unregister from YARN → release SparkUI port),
     * then SIGKILL as a fallback after {@link #DESTROY_WAIT_SECONDS} seconds.
     *
     * <p>Safe to call from any thread and on any exit path: it is a no-op when the
     * process is null or already dead.
     */
    private void destroyProcess() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        log.info("Spark SQL task stopping, destroying process: {}", task.getName());
        p.destroy(); // SIGTERM → Spark graceful shutdown (unregister YARN app, release SparkUI port)
        try {
            if (!p.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn(
                        "Spark SQL process alive after {}s SIGTERM, force killing: {}",
                        DESTROY_WAIT_SECONDS,
                        task.getName());
                p.destroyForcibly(); // SIGKILL fallback, guarantees cleanup
            }
        } catch (InterruptedException ie) {
            log.warn("Interrupted while waiting for Spark SQL process to exit, force killing: {}", task.getName());
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
