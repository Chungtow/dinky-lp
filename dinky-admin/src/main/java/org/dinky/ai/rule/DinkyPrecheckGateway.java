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

package org.dinky.ai.rule;

import org.dinky.ai.context.NameRegistryService;
import org.dinky.ai.context.RequestSnapshot;
import org.dinky.context.TenantContextHolder;
import org.dinky.data.dto.TaskDTO;
import org.dinky.data.model.ClusterInstance;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.Schema;
import org.dinky.data.model.Table;
import org.dinky.data.model.Task;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.ClusterInstanceService;
import org.dinky.service.TaskService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link PrecheckGateway} 的 Dinky 实现。
 *
 * <p><b>只读、只做查询</b>：规则引擎是"写入前校验"，任何一步都不允许产生副作用；
 * 所有异常都被吞掉并转成 {@code null}（→ UNKNOWN），因为"查不到"不等于"不合格"。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DinkyPrecheckGateway implements PrecheckGateway {

    private final TaskService taskService;

    private final ClusterInstanceService clusterInstanceService;

    private final NameRegistryService nameRegistryService;

    @Override
    public String envStatement(Integer tenantId, Integer envId) {
        if (envId == null) {
            return null;
        }
        return withTenant(tenantId, () -> {
            List<Task> envs = taskService.listFlinkSQLEnv();
            if (envs == null) {
                return null;
            }
            for (Task env : envs) {
                if (env != null && envId.equals(env.getId())) {
                    return env.getStatement();
                }
            }
            return null;
        });
    }

    @Override
    public Integer taskStep(Integer tenantId, Integer taskId) {
        if (taskId == null) {
            return null;
        }
        return withTenant(tenantId, () -> {
            TaskDTO task = taskService.getTaskInfoById(taskId);
            return task == null ? null : task.getStep();
        });
    }

    @Override
    public Set<Integer> tenantClusterIds(Integer tenantId) {
        return withTenant(tenantId, () -> {
            List<ClusterInstance> clusters = clusterInstanceService.listEnabledAllClusterInstance();
            if (clusters == null) {
                return null;
            }
            Set<Integer> ids = new LinkedHashSet<>();
            for (ClusterInstance cluster : clusters) {
                if (cluster != null && cluster.getId() != null) {
                    ids.add(cluster.getId());
                }
            }
            return ids;
        });
    }

    @Override
    public Boolean tableExists(RequestSnapshot snapshot, String database, String table) {
        if (snapshot == null || database == null || table == null) {
            return null;
        }
        for (DataBase dataBase : snapshot.getDataBasesByName().values()) {
            if (dataBase == null) {
                continue;
            }
            try {
                Driver driver = Driver.build(dataBase.getDriverConfig());
                List<Schema> schemas = driver.listSchemas();
                boolean schemaHit = false;
                for (Schema schema : schemas) {
                    if (schema != null && database.equalsIgnoreCase(schema.getName())) {
                        schemaHit = true;
                        break;
                    }
                }
                if (!schemaHit) {
                    continue;
                }
                List<Table> tables = driver.listTables(database);
                for (Table item : tables) {
                    if (item != null && table.equalsIgnoreCase(item.getName())) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            } catch (Exception e) {
                log.debug("precheck tableExists: {} / {}.{} failed", dataBase.getName(), database, table, e);
            }
        }
        return null;
    }

    @Override
    public List<String> occupiedNames(Integer tenantId) {
        return withTenant(tenantId, () -> {
            NameRegistryService.Snapshot snapshot = nameRegistryService.getSnapshot();
            if (snapshot == null) {
                return null;
            }
            return new ArrayList<>(snapshot.all());
        });
    }

    /** 在指定租户上下文内执行查询，结束后必定清理（避免污染同线程后续查询） */
    private <T> T withTenant(Integer tenantId, Supplier<T> supplier) {
        Object origin = TenantContextHolder.get();
        try {
            if (tenantId != null) {
                TenantContextHolder.set(tenantId);
            }
            return supplier.get();
        } catch (Exception e) {
            log.debug("precheck gateway query failed", e);
            return null;
        } finally {
            if (tenantId != null) {
                if (origin != null) {
                    TenantContextHolder.set(origin);
                } else {
                    TenantContextHolder.clear();
                }
            }
        }
    }
}
