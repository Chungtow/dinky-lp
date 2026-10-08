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

import org.dinky.ai.context.FlinkContextProvider;
import org.dinky.config.Dialect;
import org.dinky.data.dto.StudioMetaStoreDTO;
import org.dinky.data.model.Catalog;
import org.dinky.data.model.Schema;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Task;
import org.dinky.service.StudioService;
import org.dinky.service.TaskService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code @env/<env任务名>} 引用的渲染器（AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>只给"枚举结果"，绝不给 env 语句原文</b>：env 里是
 * {@code 'password'='明文口令'} 的连接串（P0 §3 结论），注入 prompt 等于把口令发给第三方模型。
 * 需要的是"这个环境能看见哪些 catalog / 库"，而该信息可以用<b>回放</b>得到——
 * 复用 Cube/Studio 面板同一条链路（{@code buildEnvSql} + {@code StudioService#getMSCatalogs}），
 * 因此与「左侧 Catalog 面板」「P0 的 catalog 区块」口径一致，并共享其 JobManager 缓存。
 *
 * <p><b>必须在 HTTP 请求线程内执行</b>：回放要用到登录态与租户上下文（P0 已记录
 * {@code NotWebContextException} 与"异步线程租户上下文丢失"两处坑），本类只被
 * {@code AiChatServiceImpl} 的请求线程路径调用。
 *
 * @since 2026/10/08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvRefRenderer {

    public static final String PREFIX = "@env/";

    /** 环境任务的方言值（与 {@code SystemInit#initDefaultFlinkSQLEnv} 创建时一致） */
    private static final String ENV_DIALECT = "FlinkSqlEnv";

    private static final int MAX_DATABASES_PER_CATALOG = 50;

    private final TaskService taskService;

    private final StudioService studioService;

    /** 渲染一条 {@code @env/} 引用（<b>永不抛异常</b>）。 */
    public String render(String rawName) {
        String name = StrUtil.trimToEmpty(rawName);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (StrUtil.isBlank(name)) {
            return "### " + PREFIX + "\n- 未指定环境名，用法：`@env/<env任务名>`\n";
        }
        if (name.contains("/")) {
            return "### " + PREFIX + name + "\n- 环境任务名不应包含 `/`（格式：`@env/<env任务名>`）\n";
        }
        List<Task> found;
        try {
            found = taskService.list(new LambdaQueryWrapper<Task>()
                    .select(Task::getId, Task::getName, Task::getDialect, Task::getEnabled)
                    .eq(Task::getDialect, ENV_DIALECT)
                    .eq(Task::getName, name));
        } catch (Exception e) {
            log.warn("Load env task failed, name: {}, message: {}", name, e.getMessage());
            return failure(name, "查询环境任务失败（" + e.getClass().getSimpleName() + "）");
        }
        Task envTask = null;
        boolean disabled = false;
        for (Task task : found == null ? new ArrayList<Task>() : found) {
            if (task == null) {
                continue;
            }
            if (Boolean.FALSE.equals(task.getEnabled())) {
                disabled = true;
            } else {
                envTask = task;
                break;
            }
        }
        if (envTask == null) {
            return failure(
                    name,
                    disabled
                            ? "同名环境任务存在但**已被禁用**（请先在数据开发里启用）"
                            : "未找到启用中的 FlinkSQL 环境任务。环境任务在【数据开发 → 新建任务】把方言选为 `FlinkSqlEnv` 创建");
        }
        return describeEnv(name, envTask);
    }

    /** 枚举并输出该 env 的 catalog / 库清单。 */
    private String describeEnv(String name, Task envTask) {
        StringBuilder sb = new StringBuilder(288);
        sb.append("### ").append(PREFIX).append(name).append("（环境任务 · 只给枚举结果；env 语句原文含连接凭据，不予注入）\n");
        sb.append("- 环境任务: `")
                .append(envTask.getName())
                .append("`（task ")
                .append(envTask.getId())
                .append(", dialect ")
                .append(ENV_DIALECT)
                .append(", 启用中）\n");
        sb.append("- 该 env 注册的 catalog / 库（经「env 回放」枚举 · 快照 ")
                .append(LocalDateTime.now().format(SourceRefRenderer.TS))
                .append("）:\n");
        try {
            List<Catalog> catalogs = studioService.getMSCatalogs(toMetaStoreDTO(envTask.getId()));
            if (CollUtil.isEmpty(catalogs)) {
                sb.append("    - 未枚举到任何 catalog（回放后连 default_catalog 都没有，请检查 env 语句与目标集群）\n");
            } else {
                for (Catalog catalog : catalogs) {
                    if (catalog == null) {
                        continue;
                    }
                    List<String> databases = catalog.getSchemas() == null
                            ? new ArrayList<>()
                            : catalog.getSchemas().stream()
                                    .filter(schema -> schema != null && StrUtil.isNotBlank(schema.getName()))
                                    .map(Schema::getName)
                                    .collect(Collectors.toList());
                    sb.append("    - catalog `")
                            .append(catalog.getName())
                            .append("`: ")
                            .append(joinLimit(databases, MAX_DATABASES_PER_CATALOG))
                            .append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("    - ❌ 枚举失败：")
                    .append(FlinkContextProvider.classifyCatalogFailure(e))
                    .append('\n');
            log.warn("Enumerate env catalogs failed, envId: {}, message: {}", envTask.getId(), e.getMessage());
        }
        sb.append("- 提示: 作业在「FlinkSQL 环境」下拉里绑定该 env 后，即可直接使用上述 catalog/库的短名；")
                .append("该 env 的连接地址与口令**不在**上下文内提供（需要时请到该环境任务里查看）。\n");
        return applyBudget(sb.toString(), SystemConfiguration.getInstances().getLlmSourceExpandMaxChars());
    }

    /**
     * 构造「只为回放该 env」的元数据查询 DTO。
     *
     * <p>{@code statement} 故意留空：{@code buildEnvSql} 会用 {@code envId} 取到 env 任务自己的语句，
     * 于是回放内容<b>恰好只有</b>该 env 的注册语句——这正是"这个 env 里有什么"的定义。
     * {@code fragment=false} 避免再叠加数据源的前置 SQL。
     */
    private StudioMetaStoreDTO toMetaStoreDTO(Integer envId) {
        StudioMetaStoreDTO dto = new StudioMetaStoreDTO();
        dto.setDialect(Dialect.FLINK_SQL.getValue());
        dto.setEnvId(envId);
        dto.setFragment(false);
        dto.setStatement("");
        return dto;
    }

    private static String applyBudget(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... (该 @ 引用内容超预算，已截断)\n";
    }

    private static String failure(String name, String reason) {
        return "### " + PREFIX + name + "\n- ❌ " + reason + "\n";
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
