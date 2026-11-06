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

import lombok.Data;

/**
 * 召回查询条件（由前端/模型给出，全部可选）。
 *
 * @since 2026/11/02
 */
@Data
public class SimilarTasksQuery {

    /** 自然语言关键词（如 "Top5"、"窗口"、"维表"） */
    private List<String> keywords = new ArrayList<>();

    /** 涉及的表（如 {@code traccar.dws_device_position_window}） */
    private List<String> tables = new ArrayList<>();

    /** 涉及的 Kafka topic */
    private List<String> topics = new ArrayList<>();

    /** 返回条数上限，默认 2（多给会撑爆 prompt） */
    private Integer limit = 2;
}
