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

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Registry of SQL statements currently executing, one entry per executing thread.
 *
 * <p>Dinky's JDBC data source is a "one thread, one execution" model: {@link AbstractJdbcDriver#conn}
 * is a {@link ThreadLocal}, and a task's SQL runs synchronously on its own thread. This registry
 * therefore keys on the <b>executing thread id</b>, so that a stop request coming from another
 * thread (the stop API thread) can issue the standard JDBC {@link Statement#cancel()} against the
 * very statement that is blocking, and thus cancel the job on the server side
 * (e.g. {@code SparkContext.cancelJobGroup} on a Spark ThriftServer).
 *
 * <p>Lifecycle: a top-level entry point ({@link AbstractJdbcDriver#executeSql}) calls
 * {@link #begin}/{@link #end} around the whole execution; the statement level handle is maintained by
 * {@link #bindStatement}/{@link #unbindStatement}. Entries are only kept while a statement is
 * executing, so no connection is held by this class.
 *
 * @since 1.2.5-lp
 */
@Slf4j
public class RunningSqlRegistry {

    /** threadId -> execution context of the statement currently running on that thread. */
    private static final Map<Long, Entry> RUNNING = new ConcurrentHashMap<>();

    /**
     * Whether the current thread's latest execution was canceled. Used by callers to map the
     * result status to CANCEL instead of FAILED; reset on every {@link #begin()}.
     */
    private static final ThreadLocal<Boolean> CANCELED = new ThreadLocal<>();

    private static final long POLL_INTERVAL_MILLIS = 100L;

    private static final int SQL_DIGEST_LENGTH = 120;

    private RunningSqlRegistry() {}

    /**
     * Execution context of one thread.
     */
    public static class Entry {

        private final long threadId;
        private final Connection connection;
        private final String dataSourceName;
        private final long startedAt;
        private volatile Statement statement;
        private volatile String sql;
        private volatile boolean cancelRequested;

        Entry(long threadId, Connection connection, String dataSourceName) {
            this.threadId = threadId;
            this.connection = connection;
            this.dataSourceName = dataSourceName;
            this.startedAt = System.currentTimeMillis();
        }

        public long getThreadId() {
            return threadId;
        }

        public Connection getConnection() {
            return connection;
        }

        public String getDataSourceName() {
            return dataSourceName;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public String getSql() {
            return sql;
        }

        public boolean isCancelRequested() {
            return cancelRequested;
        }
    }

    /**
     * Start an execution scope for the current thread. Must be paired with {@link #end()}.
     *
     * @param connection     the physical connection used by the current thread; may be null,
     *                       in which case the cancel fallback is skipped
     * @param dataSourceName data source name, used for logging only
     */
    public static void begin(Connection connection, String dataSourceName) {
        CANCELED.set(Boolean.FALSE);
        long threadId = Thread.currentThread().getId();
        RUNNING.put(threadId, new Entry(threadId, connection, dataSourceName));
    }

    /**
     * Bind the statement that is about to run on the current thread (no-op when the current
     * thread is not inside an execution scope, e.g. metadata browsing).
     */
    public static void bindStatement(Statement statement, String sql) {
        Entry entry = RUNNING.get(Thread.currentThread().getId());
        if (entry == null) {
            return;
        }
        entry.statement = statement;
        entry.sql = sql;
    }

    /**
     * Release the statement bound by {@link #bindStatement(Statement, String)}. Called in a
     * finally block so that every exit path releases the handle.
     */
    public static void unbindStatement() {
        Entry entry = RUNNING.get(Thread.currentThread().getId());
        if (entry == null) {
            return;
        }
        if (entry.cancelRequested) {
            CANCELED.set(Boolean.TRUE);
        }
        entry.statement = null;
        entry.sql = null;
    }

    /**
     * Close the execution scope of the current thread.
     */
    public static void end() {
        Entry entry = RUNNING.remove(Thread.currentThread().getId());
        if (entry != null && entry.cancelRequested) {
            CANCELED.set(Boolean.TRUE);
        }
    }

    /**
     * Whether a cancel has been requested for the execution running on the current thread.
     * Multi-statement scripts use this to stop executing the remaining statements.
     */
    public static boolean isCancelRequested() {
        Entry entry = RUNNING.get(Thread.currentThread().getId());
        return entry != null && entry.cancelRequested;
    }

    /**
     * Whether the current thread's latest execution scope was canceled (kept readable after
     * {@link #end()} so the caller can map the job status to CANCEL).
     */
    public static boolean wasCanceled() {
        return Boolean.TRUE.equals(CANCELED.get());
    }

    /**
     * Cancel the statement running on the given thread.
     *
     * <p>Steps: ① mark the request; ② issue {@link Statement#cancel()} (Hive JDBC turns this into
     * a {@code CancelOperation} RPC, which makes Spark call {@code cancelJobGroup(statementId)});
     * ③ wait up to {@code waitMillis} for the execution to leave the registry; ④ if it is still
     * running, fall back to physically aborting the connection, which closes the server side
     * session and cancels the operation as well.
     *
     * <p>This method returns {@code true} when the cancel request has been accepted (including the
     * case that nothing was running), so the caller can treat stop as idempotent.
     *
     * @param threadId   id of the thread that is running the SQL
     * @param waitMillis how long to wait for the execution to finish before aborting the connection
     */
    public static boolean cancelByThreadId(long threadId, long waitMillis) {
        Entry entry = RUNNING.get(threadId);
        if (entry == null) {
            log.info("[cancel] no running SQL on thread {}, nothing to cancel", threadId);
            return true;
        }
        entry.cancelRequested = true;
        log.info(
                "[cancel] cancel requested: thread={}, dataSource={}, elapsed={}ms, sql={}",
                threadId,
                entry.dataSourceName,
                System.currentTimeMillis() - entry.startedAt,
                digest(entry.sql));

        Statement statement = entry.statement;
        if (statement == null) {
            log.info("[cancel] no statement bound yet on thread {}, waiting for it to start", threadId);
        } else {
            try {
                statement.cancel();
                log.info("[cancel] Statement.cancel() sent to server, thread={}", threadId);
            } catch (Throwable t) {
                log.warn("[cancel] Statement.cancel() failed: thread={}, error={}", threadId, t.getMessage());
            }
        }

        if (awaitFinish(threadId, waitMillis)) {
            log.info("[cancel] execution finished after cancel request, thread={}", threadId);
            return true;
        }

        Entry stillRunning = RUNNING.get(threadId);
        if (stillRunning != null && stillRunning.connection != null) {
            log.warn(
                    "[cancel] still running after {}ms, aborting physical connection: thread={}", waitMillis, threadId);
            try {
                stillRunning.connection.abort(Runnable::run);
            } catch (Throwable t) {
                log.warn("[cancel] abort connection failed: thread={}, error={}", threadId, t.getMessage());
            }
        }
        return true;
    }

    private static boolean awaitFinish(long threadId, long waitMillis) {
        long deadline = System.currentTimeMillis() + Math.max(waitMillis, 0L);
        while (System.currentTimeMillis() < deadline) {
            if (!RUNNING.containsKey(threadId)) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !RUNNING.containsKey(threadId);
    }

    private static String digest(String sql) {
        if (sql == null) {
            return "-";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= SQL_DIGEST_LENGTH ? oneLine : oneLine.substring(0, SQL_DIGEST_LENGTH) + "...";
    }
}
