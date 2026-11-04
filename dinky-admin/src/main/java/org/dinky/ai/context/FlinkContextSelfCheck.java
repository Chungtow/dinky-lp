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

import java.util.ArrayList;
import java.util.List;

/**
 * FlinkSQL 上下文「7 段自检」。
 *
 * <p><b>要解决的问题</b>：上下文是不是给到位，过去只能让模型"原样贴出来"再人肉看（M1-17），
 * 既花 token，模型还可能改写/省略，判据不稳定。这里把判据固化成 7 条**纯文本断言**：
 * 装配完就自检，缺哪段直接说缺哪段，人不用问模型。
 *
 * <p>这 7 条是"充分性下限"，不是"充分性上限"：
 *
 * <ol>
 *   <li>L1 集群能力（Flink 版本 + connector 白名单）
 *   <li>L2 平台默认（Kafka 地址 + 默认 sink 数据源）
 *   <li>链路形态（canal 变更流 —— 写错 format 就解析不出字段）
 *   <li>口径约束（Top-N 不落库 —— CH jdbc sink 是 insert-only）
 *   <li>外部资源清单（数据源 id/名称/类型）
 *   <li>规则红线（规则库渲染出的条目）
 *   <li>输出协议（flinksql-job 四段）
 * </ol>
 *
 * <p>第 1~7 条与"是否绑定作业"无关：新建/未保存的 tab 也必须全绿，否则模型只能编。
 *
 * @since 2026/11/03
 */
public final class FlinkContextSelfCheck {

    /** 检查项条数（UAT 断言用；改这个数就要同步改 UAT 文档与单测） */
    public static final int EXPECTED_ITEMS = 7;

    public static final String PASS = "pass";

    public static final String FAIL = "fail";

    /**
     * 对已装配的上下文文本做 7 条断言。
     *
     * @param text 上下文文本（null 视为全缺）
     * @return 7 条检查项（顺序固定）
     */
    public static List<Item> check(String text) {
        String t = text == null ? "" : text;
        List<Item> items = new ArrayList<>(EXPECTED_ITEMS);
        items.add(item(
                "capability",
                "L1 集群能力（Flink 版本 + connector 白名单）",
                contains(t, "### 集群能力") && contains(t, "可用 connector"),
                "缺「### 集群能力 / 可用 connector」——模型会凭通用知识写 hive/doris"));
        items.add(item(
                "defaults",
                "L2 平台默认（Kafka 接入地址 + 默认 sink）",
                contains(t, "### 平台默认配置") && contains(t, "Kafka 接入地址") && contains(t, "默认 sink 数据源"),
                "缺「### 平台默认配置」或其 Kafka / 默认 sink 行——模型会编连接串"));
        items.add(item(
                "pipeline",
                "链路形态（canal 变更流）",
                containsIgnoreCase(t, "canal"),
                "缺 canal 链路说明——模型会写 debezium-json / raw 多列"));
        items.add(item(
                "conventions",
                "口径约束（Top-N 不落库）",
                containsIgnoreCase(t, "top-n"),
                "缺「Top-N 不落库」——模型会把 ROW_NUMBER 结果落进 insert-only sink"));
        items.add(item(
                "resources",
                "外部资源清单（数据源 id / 名称 / 类型）",
                contains(t, "### 外部资源清单") && contains(t, "已启用的数据源"),
                "缺「### 外部资源清单」——模型不知道有哪些库可用"));
        items.add(item(
                "rules", "规则红线（规则库渲染）", contains(t, "### FlinkSQL 作业红线"), "缺「### FlinkSQL 作业红线」——参数放置/命名/时区红线全失效"));
        items.add(item(
                "protocol",
                "输出协议（flinksql-job 四段）",
                contains(t, "### 输出协议") && containsIgnoreCase(t, FlinkJobProtocol.FENCE_TAG),
                "缺「### 输出协议」——模型不会给作业卡片"));
        return items;
    }

    /** 是否 7 条全绿 */
    public static boolean allPass(List<Item> items) {
        if (items == null || items.size() != EXPECTED_ITEMS) {
            return false;
        }
        for (Item item : items) {
            if (!PASS.equals(item.getStatus())) {
                return false;
            }
        }
        return true;
    }

    /** 把未通过的项拼成一行（日志 / 接口提示用） */
    public static String describeFailures(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return "（无检查项）";
        }
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            if (PASS.equals(item.getStatus())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(item.getId()).append('=').append(item.getEvidence());
        }
        return sb.length() == 0 ? "（全部通过）" : sb.toString();
    }

    private static Item item(String id, String name, boolean pass, String failEvidence) {
        return new Item(id, name, pass ? PASS : FAIL, pass ? "" : failEvidence);
    }

    private static boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return text.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT));
    }

    /** 单条检查结果 */
    public static class Item {

        private final String id;

        private final String item;

        /** pass / fail */
        private final String status;

        /** fail 时说明缺什么（pass 时为空串） */
        private final String evidence;

        public Item(String id, String item, String status, String evidence) {
            this.id = id;
            this.item = item;
            this.status = status;
            this.evidence = evidence;
        }

        public String getId() {
            return id;
        }

        public String getItem() {
            return item;
        }

        public String getStatus() {
            return status;
        }

        public String getEvidence() {
            return evidence;
        }
    }

    private FlinkContextSelfCheck() {}
}
