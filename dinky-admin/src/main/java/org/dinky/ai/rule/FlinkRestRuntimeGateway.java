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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Flink REST 的 runtime 事实来源（2 秒超时，失败一律返回 null）。
 *
 * <p>为什么不用 Dinky 的 {@code SubmitClient}：那个客户端要为提交作业构造整套 JobConfig，
 * 而这里只需要三个只读端点。规则引擎的 runtime 断言是<b>体检</b>，不该被提交链路的复杂度拖住。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
public class FlinkRestRuntimeGateway implements RuntimeGateway {

    private static final int TIMEOUT_MS = 2000;

    @Value("${dinky.flink.rest:http://flink01:28081}")
    private String flinkRest;

    @Override
    public String jobPlan(String jobId) {
        if (StrUtil.isBlank(jobId)) {
            return null;
        }
        return get("/jobs/" + jobId + "/plan");
    }

    @Override
    public Integer completedCheckpoints(String jobId) {
        return checkpointCount(jobId, "completed");
    }

    @Override
    public Integer failedCheckpoints(String jobId) {
        return checkpointCount(jobId, "failed");
    }

    @Override
    public Integer freeSlots() {
        String body = get("/taskmanagers");
        if (body == null) {
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(body);
            JSONArray managers = json.getJSONArray("taskmanagers");
            if (managers == null) {
                return null;
            }
            int total = 0;
            for (Object item : managers) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject manager = (JSONObject) item;
                Integer free = manager.getInt("freeSlots", manager.getInt("slotsFree", 0));
                total += free == null ? 0 : free;
            }
            return total;
        } catch (Exception e) {
            log.debug("flink rest freeSlots parse failed", e);
            return null;
        }
    }

    private Integer checkpointCount(String jobId, String key) {
        if (StrUtil.isBlank(jobId)) {
            return null;
        }
        String body = get("/jobs/" + jobId + "/checkpoints");
        if (body == null) {
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(body);
            JSONObject counts = json.getJSONObject("counts");
            return counts == null ? null : counts.getInt(key);
        } catch (Exception e) {
            log.debug("flink rest checkpoints parse failed", e);
            return null;
        }
    }

    private String get(String path) {
        if (StrUtil.isBlank(flinkRest)) {
            return null;
        }
        String url = StrUtil.removeSuffix(flinkRest, "/") + path;
        try {
            return HttpUtil.get(url, TIMEOUT_MS);
        } catch (Exception e) {
            log.debug("flink rest call failed: {}", url, e);
            return null;
        }
    }
}
