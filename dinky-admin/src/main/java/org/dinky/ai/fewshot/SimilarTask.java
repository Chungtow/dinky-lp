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

package org.dinky.ai.fewshot;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条 few-shot 语料（四段式骨架）。
 *
 * <p><b>只留骨架</b>：字段级映射属于别人的业务，进 prompt 会让模型照抄别人的字段名
 * （计划 §4.4 的脱敏要求）。
 *
 * @since 2026/11/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarTask {

    private String id;

    private String title;

    @Builder.Default
    private List<String> topics = new ArrayList<>();

    @Builder.Default
    private List<String> tables = new ArrayList<>();

    @Builder.Default
    private List<String> keywords = new ArrayList<>();

    private String sql;

    private String customConfig;

    private String ddl;

    private String precheck;

    /** 来源：L1 = jar 内置语料；L2 = 本租户线上作业 */
    private String source;

    private Integer taskId;

    public String blob() {
        return String.join(" ", title == null ? "" : title, String.join(" ", keywords), sql == null ? "" : sql);
    }
}
