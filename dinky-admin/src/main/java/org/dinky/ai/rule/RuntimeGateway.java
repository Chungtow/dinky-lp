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

/**
 * runtime 档需要的 Flink REST 事实。
 *
 * <p>与 {@link PrecheckGateway} 同样约定：拿不到返回 {@code null}（→ UNKNOWN），不返回 false。
 *
 * @since 2026/11/02
 */
public interface RuntimeGateway {

    /** Flink 作业计划全文（{@code GET /jobs/:jid/plan}）；拿不到返回 null */
    String jobPlan(String jobId);

    /** 已完成的 checkpoint 数；拿不到返回 null */
    Integer completedCheckpoints(String jobId);

    /** 失败的 checkpoint 数；拿不到返回 null */
    Integer failedCheckpoints(String jobId);

    /** 当前空闲 slot 数（试跑节流用）；拿不到返回 null */
    Integer freeSlots();
}
