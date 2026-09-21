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
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.result.SqlExplainResult;
import org.dinky.job.Job;
import org.dinky.job.JobResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;

import lombok.extern.slf4j.Slf4j;

/**
 * ShellTask executes a shell script through a {@code /bin/bash} sub-process.
 *
 * <p>The script is the task's statement (either the full script or the selected
 * lines when the user runs a selection in the editor). It is written to a
 * temporary {@code .sh} file and executed with {@code /bin/bash <file>}, so the
 * child process inherits the container environment — including
 * {@code $HADOOP_HOME}, {@code $FLINK_HOME}, {@code $SPARK_HOME} and
 * {@code $HADOOP_CONF_DIR} — enabling the script to invoke the hdfs / flink /
 * spark CLIs directly via RPC (no ssh needed).
 *
 * <p>Unlike {@link SparkSqlTask}, shell output is plain text rather than a
 * tabular result set, so there is no stdout parsing; stdout/stderr are streamed
 * to the frontend console in real time and echoed into the result statement.
 *
 * @since 1.2.5-lp
 */
@Slf4j
@SupportDialect(Dialect.SHELL)
public class ShellTask extends BaseTask {

    private static final String DEFAULT_SHELL = "/bin/bash";
    private static final long DEFAULT_TIMEOUT_MINUTES = 60;
    private static final int DESTROY_WAIT_SECONDS = 10;

    /**
     * Static registry of running ShellTask instances, keyed by task name.
     *
     * <p>{@link BaseTask#getTask()} creates a new instance per execution, so
     * {@link #stop()} must look up the live instance through this registry to
     * interrupt/kill its process.
     */
    private static final Map<String, ShellTask> RUNNING_TASKS = new ConcurrentHashMap<>();

    /** The underlying bash process, reachable from the finally block and {@link #stop()}. */
    private volatile Process process;

    public ShellTask(TaskDTO task) {
        super(task);
    }

    @Override
    public List<SqlExplainResult> explain() {
        return Collections.emptyList();
    }

    @Override
    public JobResult StreamExecute() {
        // Shell runs synchronously; support "debug" (StreamExecute) by delegating to execute()
        try {
            return execute();
        } catch (Exception e) {
            log.error("Shell task StreamExecute failed", e);
            JobResult result = new JobResult();
            result.setError("Shell execution error: " + e.getMessage());
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }
    }

    @Override
    public JobResult execute() throws Exception {
        String script = replaceTaskVariables(task.getStatement());
        if (script == null || script.trim().isEmpty()) {
            JobResult result = new JobResult();
            result.setError("Shell script is empty");
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        }

        log.info("Executing Shell task: {}", task.getName());

        File scriptFile = null;
        try {
            // Register this instance so stop()/cancel can reach the live process
            RUNNING_TASKS.put(task.getName(), this);

            scriptFile = File.createTempFile("dinky-shell-", ".sh");
            try (FileWriter writer = new FileWriter(scriptFile)) {
                writer.write(script);
                writer.flush();
            }
            log.info("Shell script written to temp file: {}", scriptFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(DEFAULT_SHELL, scriptFile.getAbsolutePath());
            pb.redirectErrorStream(false);

            process = pb.start();

            // Capture MDC context for real-time log streaming to the frontend console.
            String processName = MDC.get(ProcessAspect.PROCESS_NAME);
            String stepPid = MDC.get(ProcessAspect.PROCESS_STEP);

            StringBuilder stdoutData = new StringBuilder();
            StringBuilder stderrData = new StringBuilder();

            Thread stdoutThread = new Thread(
                    () -> {
                        // Clear the MDC inherited from the main thread
                        // (log4j2.isThreadContextMapInheritable=true), otherwise every
                        // log.info below would ALSO be captured by LogSseAppender and
                        // streamed to the frontend console with a full log4j2 pattern
                        // prefix, duplicating the clean "[Shell] ..." lines pushed via
                        // the explicit appendLog call.
                        MDC.clear();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stdoutData.append(line).append("\n");
                                log.info("[Shell] {}", line);
                                if (processName != null && stepPid != null) {
                                    ConsoleContextHolder.getInstances()
                                            .appendLog(processName, stepPid, "[Shell] " + line, true);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Error reading shell stdout", e);
                        }
                    },
                    "shell-stdout");
            stdoutThread.setDaemon(true);

            Thread stderrThread = new Thread(
                    () -> {
                        // Same as stdoutThread: avoid LogSseAppender double-capture
                        MDC.clear();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stderrData.append(line).append("\n");
                                log.info("[Shell] {}", line);
                                if (processName != null && stepPid != null) {
                                    ConsoleContextHolder.getInstances()
                                            .appendLog(processName, stepPid, "[Shell] " + line, true);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Error reading shell stderr", e);
                        }
                    },
                    "shell-stderr");
            stderrThread.setDaemon(true);

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(5000);
                stderrThread.join(5000);
                JobResult result = new JobResult();
                result.setError("Shell execution timeout after " + DEFAULT_TIMEOUT_MINUTES + " minutes");
                result.setStatus(Job.JobStatus.FAILED);
                result.setSuccess(false);
                return result;
            }

            stdoutThread.join(30000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String stdout = stdoutData.toString();

            if (exitCode == 0) {
                log.info("Shell task completed successfully: {}", task.getName());
                JobResult result = new JobResult();
                result.setStatus(Job.JobStatus.SUCCESS);
                result.setSuccess(true);
                result.setStatement(stdout.isEmpty() ? "Shell executed successfully (no output)." : stdout);
                return result;
            } else {
                String fullOutput = stderrData.toString() + "\n" + stdout;
                log.error("Shell task failed with exit code {}: {}", exitCode, fullOutput);
                JobResult result = new JobResult();
                result.setError("Shell exited with code " + exitCode);
                result.setStatus(Job.JobStatus.FAILED);
                result.setSuccess(false);
                result.setStatement(fullOutput);
                return result;
            }
        } catch (InterruptedException e) {
            // UI stop/cancel → killProcess → t.interrupt() arrives here
            log.info("Shell task interrupted (stop/cancel), process cleaned up: {}", task.getName());
            Thread.currentThread().interrupt(); // restore interrupt flag
            JobResult result = new JobResult();
            result.setError("Shell execution interrupted");
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        } catch (Exception e) {
            log.error("Shell execution error, process cleaned up: {}", task.getName(), e);
            JobResult result = new JobResult();
            result.setError("Shell execution error: " + e.getMessage());
            result.setStatus(Job.JobStatus.FAILED);
            result.setSuccess(false);
            return result;
        } finally {
            // Unified cleanup on every exit path (normal / interrupted / exception / timeout)
            destroyProcess();
            if (scriptFile != null && scriptFile.exists()) {
                scriptFile.delete();
            }
            RUNNING_TASKS.remove(task.getName());
        }
    }

    @Override
    public boolean stop() {
        ShellTask running = RUNNING_TASKS.get(task.getName());
        if (running == null) {
            return false; // no live process for this task
        }
        running.destroyProcess();
        return true;
    }

    /**
     * Terminate the underlying bash process: SIGTERM first, then SIGKILL as a
     * fallback after {@link #DESTROY_WAIT_SECONDS} seconds. Safe to call from any
     * thread and on any exit path (no-op when the process is null or already dead).
     */
    private void destroyProcess() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        log.info("Shell task stopping, destroying process: {}", task.getName());
        p.destroy(); // SIGTERM
        try {
            if (!p.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn(
                        "Shell process alive after {}s SIGTERM, force killing: {}",
                        DESTROY_WAIT_SECONDS,
                        task.getName());
                p.destroyForcibly(); // SIGKILL fallback
            }
        } catch (InterruptedException ie) {
            log.warn("Interrupted while waiting for shell process to exit, force killing: {}", task.getName());
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
