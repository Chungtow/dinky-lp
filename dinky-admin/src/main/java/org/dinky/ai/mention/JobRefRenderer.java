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

import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Task;
import org.dinky.service.TaskService;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @job/<作业名或 id>} 引用的渲染器（P2，2026-11-04）。
 *
 * <p><b>解决什么问题</b>：{@code @topic/} 让我们能引用"某个 topic 长什么样"，
 * 但真正决定"作业该怎么写"的往往是**另一个作业**——"照 #102 再加一个设备维度"。
 * 自动 few-shot 召回是"猜你想参考哪个"，{@code @job/} 是"我明确要参考这个"，
 * 两者互补：召回负责兜底，句柄负责精确控制。
 *
 * <p><b>为什么注入整段 SQL 而不是只给结构</b>：这类参考的价值就在细节——
 * canal 报文用 {@code format='raw'} 还是 {@code canal-json}、字段怎么用 {@code JSON_VALUE} 拆、
 * 窗口怎么写、sink 的 {@code table-name} 带不带库名。抽掉细节就退化成"又一个模板"。
 * 因此这里给的是**脱敏后的完整 SQL**（口令类字面量替换为 {@code ***}），只受长度预算约束。
 *
 * <p><b>安全边界</b>：只读、只查本租户、绝不返回连接串口令。
 *
 * @since 2026/11/04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRefRenderer {

    public static final String PREFIX = "@job/";

    /** 口令类字面量（含 WITH 里的 jdbc 用户/密码） */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)('?)(password|passwd|pwd|secret|token|api[-_]?key|username|user)(\\1\\s*=\\s*')([^']*)(')");

    private final TaskService taskService;

    /**
     * 渲染一条 {@code @job/} 引用（<b>永不抛异常</b>，失败即降级为可读说明）。
     *
     * @param rawName 作业名或数字 id（允许带句末点号）
     */
    public String render(String rawName) {
        String name = StrUtil.trimToEmpty(rawName);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (StrUtil.isBlank(name)) {
            return "### " + PREFIX + "\n- 未指定作业名或 id，用法：`@job/<作业名>` 或 `@job/<id>`\n";
        }
        TaskDTO task;
        try {
            task = load(name);
        } catch (Exception e) {
            log.warn("Render {} failed, name: {}, message: {}", PREFIX, name, e.getMessage());
            return "### " + PREFIX + name + "\n- ❌ 读取作业失败（" + e.getClass().getSimpleName() + "），本次已降级\n";
        }
        if (task == null) {
            return "### " + PREFIX + name + "\n- ⚠️ 本租户内没有找到名为 `" + name + "` 的作业（也不存在该 id）。"
                    + "请确认作业名拼写，或改用 `@job/<数字id>`\n";
        }
        StringBuilder sb = new StringBuilder(2048);
        sb.append("### ").append(PREFIX).append(name).append("（参考作业，用于照骨架改，**不是**当前作业）\n");
        sb.append("- 作业: #")
                .append(task.getId())
                .append(" `")
                .append(StrUtil.nullToEmpty(task.getName()))
                .append("`");
        if (StrUtil.isNotBlank(task.getDialect())) {
            sb.append(" | 方言 ").append(task.getDialect());
        }
        if (task.getParallelism() != null) {
            sb.append(" | 并行度 ").append(task.getParallelism());
        }
        sb.append('\n');
        if (task.getClusterId() != null) {
            sb.append("- 集群: ")
                    .append(StrUtil.nullToEmpty(task.getClusterName()))
                    .append("（clusterId=")
                    .append(task.getClusterId())
                    .append("）\n");
        }
        if (task.getEnvId() != null && task.getEnvId() > 0) {
            sb.append("- FlinkSQL 环境(env): envId=").append(task.getEnvId()).append('\n');
        }
        List<?> customConfig =
                task.getConfigJson() == null ? null : task.getConfigJson().getCustomConfig();
        if (customConfig != null && !customConfig.isEmpty()) {
            sb.append("- 任务自定义配置: ").append(customConfig).append('\n');
        }
        String sql = task.getStatement();
        if (StrUtil.isBlank(sql)) {
            sb.append("- ⚠️ 该作业没有 SQL 正文（可能是空作业或只有 DDL 片段），无法作为参考\n");
            return sb.toString();
        }
        int maxChars = SystemConfiguration.getInstances().getLlmEditorSqlMaxChars();
        String masked = SECRET.matcher(sql).replaceAll("$1$2$3***$5");
        boolean truncated = masked.length() > maxChars;
        if (truncated) {
            masked = masked.substring(0, maxChars) + "\n... (作业 SQL 过长，已截断)";
        }
        sb.append("- SQL（口令类字面量已替换为 `***`）:\n```sql\n").append(masked).append("\n```\n");
        sb.append("- 用法: 照这份 SQL 的**结构**改（源表声明 / canal 报文解析 / 窗口 / sink），"
                + "业务字段、topic、目标表以本次需求为准；**不要**照抄它的作业名与 group.id\n");
        return sb.toString();
    }

    /** 先按 id（纯数字），再按作业名查；都查不到返回 null */
    private TaskDTO load(String name) {
        if (name.matches("\\d+")) {
            // 按 id 走 getTaskInfoById：该链路已带租户上下文过滤，跨租户 id 查不到
            TaskDTO byId = taskService.getTaskInfoById(Integer.valueOf(name));
            if (byId != null) {
                return byId;
            }
        }
        return queryByName(name);
    }

    private TaskDTO queryByName(String name) {
        List<Task> tasks = taskService
                .lambdaQuery()
                .eq(Task::getName, name)
                .last("limit 1")
                .list();
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        Task task = tasks.get(0);
        if (!sameTenant(task.getTenantId())) {
            return null;
        }
        return taskService.getTaskInfoById(task.getId());
    }

    /** 作业表不走租户插件自动过滤，这里显式比对，避免把别的租户的作业当参考 */
    private static boolean sameTenant(Integer taskTenantId) {
        Object current = TenantContextHolder.get();
        if (current == null || taskTenantId == null) {
            return true;
        }
        return String.valueOf(current).equals(String.valueOf(taskTenantId));
    }
}
