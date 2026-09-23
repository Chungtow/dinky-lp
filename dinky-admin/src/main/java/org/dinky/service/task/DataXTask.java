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

import org.dinky.config.Dialect;
import org.dinky.data.annotations.SupportDialect;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.result.SqlExplainResult;
import org.dinky.job.Job;
import org.dinky.job.JobResult;

import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * DataXTask is a metadata-only task: the actual DataX synchronization runs on a
 * DolphinScheduler worker (pushed as a native SHELL task), not in Dinky.
 *
 * <p>Running this task locally is intentionally a no-op that returns a hint. The
 * user writes the {@code datax_*.json} configuration in the editor and pushes the
 * task to DolphinScheduler, where {@code SchedulerServiceImpl} turns it into a DS
 * SHELL task (script + resource center upload).
 *
 * @since 1.2.5-lp
 */
@Slf4j
@SupportDialect(Dialect.DATAX)
public class DataXTask extends BaseTask {

    public DataXTask(TaskDTO task) {
        super(task);
    }

    @Override
    public List<SqlExplainResult> explain() {
        return Collections.emptyList();
    }

    @Override
    public JobResult StreamExecute() {
        return buildHintResult();
    }

    @Override
    public JobResult execute() throws Exception {
        return buildHintResult();
    }

    private JobResult buildHintResult() {
        log.info("DataX task does not execute locally, push it to DolphinScheduler instead: {}", task.getName());
        JobResult result = new JobResult();
        result.setStatus(Job.JobStatus.SUCCESS);
        result.setSuccess(true);
        result.setStatement("DataX 任务在 DolphinScheduler 上执行。请在编辑区编写 datax JSON 配置，" + "然后推送至 DolphinScheduler 调度执行。");
        return result;
    }

    @Override
    public boolean stop() {
        return false;
    }
}
