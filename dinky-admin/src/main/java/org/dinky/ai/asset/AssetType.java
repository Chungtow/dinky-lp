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

package org.dinky.ai.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 可被 {@code @} 引用的数仓制品类型（{@code @} 候选升级 C1）。
 *
 * <p><b>为什么要有这个枚举</b>：改造前「类型」只以字符串常量散落在 5 处——后端
 * {@code MentionType} 常量类、两条正则（{@code resolveMentions} / {@code buildMentionContext}）、
 * 前端浮层的 9 组 provider 分派、{@code mentionPath} 白名单。每加一类制品要同时改 5 处，
 * 漏一处就表现为「候选能出、注入为空」或反之。本枚举把「类型 → 协议值 → 是否可预取」
 * 收敛成唯一真相，{@code MentionType} 只保留协议值别名。
 *
 * <p><b>为什么带 {@code prefetchable}</b>：C1 的预取边界是<b>刻意割裂</b>的——
 * skill / doc / semantic / kb 可在一次请求内取全量（百级~千级），
 * 表（万级）与字段（30 万级）不能：前者没有「一次列出全量」的现成接口，后者必然撑爆浏览器内存。
 * 因此后端只对 {@code prefetchable} 的类做预取聚合，其余类型仍由前端的上下文候选
 * （当前 schema 表 / 已开作业 / 编辑器选区）或服务端搜索承担。
 *
 * <p>⚠️ <b>协议值不可改</b>：{@link #code} 随请求体与候选响应在前后端之间传递，
 * 与 {@code MentionType} 的常量值必须逐字一致。
 *
 * @since 2026/10/30
 */
public enum AssetType {

    /** skill（{@code @skill/<名>}） */
    SKILL("skill", true),

    /** 知识文档（{@code @doc/<名>}；与 skill 共用存储与权限模型） */
    DOC("doc", true),

    /** 语义资产（{@code @semantic/<project>[/<name>]}，C1 只预取项目级） */
    SEMANTIC("semantic", true),

    /** 知识库检索范围（{@code @kb/<code>}） */
    KB("kb", true),

    /** 跨数据源物理对象（{@code @source/<ds>[/<db>[/<tbl>]]}，路径式按层懒加载） */
    SOURCE("source", false),

    /** 当前面板数据源内的表（{@code @table/<表名>}） */
    TABLE("table", false),

    /** 字段（{@code @表.字段}，只允许命中表后下钻） */
    COLUMN("column", false),

    /** Kafka topic（{@code @topic/<名>}） */
    TOPIC("topic", false),

    /** FlinkSQL 环境任务（{@code @env/<名>}） */
    ENV("env", false);

    /** 前后端协议值（等同 {@code MentionType} 对应常量） */
    private final String code;

    /** 是否参与 C1 的统一预取（false 表示规模过大或依赖请求上下文） */
    private final boolean prefetchable;

    AssetType(String code, boolean prefetchable) {
        this.code = code;
        this.prefetchable = prefetchable;
    }

    public String getCode() {
        return code;
    }

    public boolean isPrefetchable() {
        return prefetchable;
    }

    /**
     * 解析协议值（大小写不敏感）。
     *
     * @param text 类型串，可为 {@code null}
     * @return 命中返回枚举，未命中返回 {@code null}（<b>不抛异常</b>：调用方都是「过滤」语义，
     *     未知类型应当视为"什么都不返回"，而不是让整个候选请求 500）
     */
    public static AssetType of(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        for (AssetType type : values()) {
            if (type.code.equals(value)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 解析逗号分隔的类型串（{@code ?types=skill,doc}）。
     *
     * @param csv 逗号分隔串；空 / {@code null} 表示<b>全类型</b>
     * @return 命中的类型（去重、保序）；空输入返回全部类型
     */
    public static List<AssetType> parseList(String csv) {
        List<AssetType> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            for (AssetType type : values()) {
                result.add(type);
            }
            return result;
        }
        for (String token : csv.split(",")) {
            AssetType type = of(token);
            if (type != null && !result.contains(type)) {
                result.add(type);
            }
        }
        return result;
    }
}
