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

import lombok.Getter;

/**
 * 当前对话绑定的<b>服务端权威</b>作业信息（请求线程解析后注入）。
 *
 * <p><b>为什么必须是服务端的</b>：2026-11-03 实测，模型调 {@code validate_task_draft} 时把
 * {@code name} 传成自己刚写的 {@code pipeline.name}（{@code traccar_device_position_top5_daily}），
 * 于是 {@code FLINK-PARAM-004}（pipeline.name 必须与作业名一致）被"自证通过"；直到用户点
 * 「校验 SQL」（服务端拿到真实作业名 {@code uat_device_top5}）才判 fail。
 *
 * <p>结论：<b>校验依据不能由被校验方提供</b>。作业名与并行度一律以本对象为准，模型自报只作参考。
 *
 * @since 2026/11/03
 */
@Getter
public class AiTaskBinding {

    /** 当前作业 id（新建作业场景为空） */
    private final Integer taskId;

    /** 作业名（来自 {@code dinky_task.name}；解析失败时为空，表示"以模型传入值为准"） */
    private final String taskName;

    /** 作业并行度（同上） */
    private final Integer parallelism;

    private AiTaskBinding(Integer taskId, String taskName, Integer parallelism) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.parallelism = parallelism;
    }

    public static AiTaskBinding of(Integer taskId, String taskName, Integer parallelism) {
        if (taskId == null) {
            return null;
        }
        return new AiTaskBinding(taskId, taskName, parallelism);
    }

    /** 是否拿到了可用于覆盖的真实作业名 */
    public boolean hasTaskName() {
        return taskName != null && !taskName.trim().isEmpty();
    }
}
