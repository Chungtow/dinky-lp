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

import org.dinky.ai.context.RequestSnapshot;

import java.util.List;
import java.util.Set;

/**
 * precheck 档需要的外部事实（查库 / 查接口）。
 *
 * <p><b>约定</b>：任何一项拿不到都返回 {@code null}，由 check 器转成 {@code UNKNOWN} ——
 * 绝不允许"查不到就当通过"（计划 §4.2 的失败处理）。
 *
 * <p><b>为什么要传 tenantId</b>：工具循环跑在异步线程，租户上下文已丢失；
 * 用请求线程算好的 tenantId 显式包裹查询，才不会"查不到就报误报"。
 *
 * @since 2026/11/02
 */
public interface PrecheckGateway {

    /** 环境的 SQL 正文（{@code FlinkSqlEnv} 任务的 statement）；找不到返回 null */
    String envStatement(Integer tenantId, Integer envId);

    /** 任务的 {@code step} 字段（2 = 已发布）；找不到返回 null */
    Integer taskStep(Integer tenantId, Integer taskId);

    /** 当前租户可用的 Flink 集群 id 集合；查不到返回 null */
    Set<Integer> tenantClusterIds(Integer tenantId);

    /** 目标物理表是否存在；无法判定返回 null */
    Boolean tableExists(RequestSnapshot snapshot, String database, String table);

    /** 本租户已占用的命名（group.id / label-prefix / pipeline.name 等）；查不到返回 null */
    List<String> occupiedNames(Integer tenantId);
}
