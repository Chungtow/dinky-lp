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

import org.dinky.data.model.Task;
import org.dinky.mapper.TaskMapper;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 解析当前作业的权威信息（作业名 / 并行度）。
 *
 * <p><b>只能在请求线程调用</b>：{@code dinky_task} 查询带租户过滤，异步线程里租户上下文已丢失，
 * 查不到会静默降级成"模型自报值"，等于把校验又交回给被校验方。
 *
 * <p><b>为什么要用 Mapper 而不是 TaskService</b>：本类会被 {@code AiChatServiceImpl} 依赖，
 * 走服务层容易绕进循环依赖；这里只做一行只读查询，不触发任何业务副作用。
 *
 * <p><b>失败策略</b>：任何异常都降级为"仅有 taskId 的绑定"（{@code taskName} 为空），
 * 校验器随后会以模型传入值为准——即回退到改造前的行为，不会因解析失败而阻断对话。
 *
 * @since 2026/11/03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiTaskBindingResolver {

    private final TaskMapper taskMapper;

    /**
     * @param taskId 当前编辑区绑定的作业 id（新建作业时为 null）
     * @return 绑定信息；{@code taskId} 为空时返回 {@code null}（表示无作业上下文）
     */
    public AiTaskBinding resolve(Integer taskId) {
        if (taskId == null) {
            return null;
        }
        try {
            Task task = taskMapper.selectById(taskId);
            if (task == null) {
                log.debug("ai task binding: task not found, taskId={}", taskId);
                return AiTaskBinding.of(taskId, null, null);
            }
            return AiTaskBinding.of(taskId, task.getName(), task.getParallelism());
        } catch (Exception e) {
            log.warn("ai task binding: resolve failed, fallback to model-provided values, taskId={}", taskId, e);
            return AiTaskBinding.of(taskId, null, null);
        }
    }
}
