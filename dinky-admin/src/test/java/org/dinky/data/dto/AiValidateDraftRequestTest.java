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

package org.dinky.data.dto;

import org.dinky.ai.draft.FlinkJobDraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * 「校验当前 SQL」请求 → 草稿的转换：前端传 {@code [{key,value}]}，脚本可能传 {@code {k:v}}。
 *
 * <p>这里只测纯转换（不碰规则引擎 / 数据库），因为规则行为已有
 * {@code FlinkSqlRuleEngineTest} 覆盖；此处的风险点是"坐标系不一致"——
 * customConfig 的 key 大小写没归一，会让 {@code FLINK-PARAM-001} 误判"没配"。
 *
 * @since 2026/11/03
 */
public class AiValidateDraftRequestTest {

    @Test
    public void customConfigArrayIsNormalizedToLowercaseMap() {
        AiValidateDraftRequest request = new AiValidateDraftRequest();
        request.setSql("SELECT 1");
        Map<String, String> item = new LinkedHashMap<>();
        item.put("key", "EXECUTION.checkpointing.min-pause");
        item.put("value", "10s");
        request.setCustomConfig(new ArrayList<>(Arrays.asList(item)));

        FlinkJobDraft draft = request.toDraft();

        Assert.assertTrue(draft.hasSql());
        Assert.assertEquals("10s", draft.getCustomConfig().get("execution.checkpointing.min-pause"));
    }

    @Test
    public void customConfigObjectIsAlsoAccepted() {
        AiValidateDraftRequest request = new AiValidateDraftRequest();
        request.setSql("SELECT 1");
        Map<String, String> object = new LinkedHashMap<>();
        object.put("table.exec.state.ttl", "48h");
        request.setCustomConfig(object);

        FlinkJobDraft draft = request.toDraft();

        Assert.assertEquals("48h", draft.getCustomConfig().get("table.exec.state.ttl"));
    }

    @Test
    public void taskFieldsArePassedThrough() {
        AiValidateDraftRequest request = new AiValidateDraftRequest();
        request.setSql("SELECT 1");
        request.setName("bench_01_devices_ods");
        request.setParallelism(2);
        request.setTaskId(124);
        request.setEnvId(3);

        FlinkJobDraft draft = request.toDraft();

        Assert.assertEquals("bench_01_devices_ods", draft.getName());
        Assert.assertEquals(Integer.valueOf(2), draft.getParallelism());
        Assert.assertEquals(Integer.valueOf(124), draft.getTaskId());
        Assert.assertEquals(Integer.valueOf(3), draft.getEnvId());
        Assert.assertTrue("ddl 缺省必须是空列表而不是 null", draft.getDdl().isEmpty());
        Assert.assertTrue("precheck 缺省必须是空列表而不是 null", draft.getPrecheck().isEmpty());
    }

    @Test
    public void emptyCustomConfigYieldsEmptyMap() {
        AiValidateDraftRequest request = new AiValidateDraftRequest();
        request.setSql("SELECT 1");
        request.setCustomConfig(new ArrayList<>());

        FlinkJobDraft draft = request.toDraft();

        Assert.assertTrue(draft.getCustomConfig().isEmpty());
    }

    @Test
    public void nullCustomConfigYieldsEmptyMap() {
        AiValidateDraftRequest request = new AiValidateDraftRequest();
        request.setSql("SELECT 1");

        List<String> keys = new ArrayList<>(request.toDraft().getCustomConfig().keySet());

        Assert.assertTrue(keys.isEmpty());
    }
}
