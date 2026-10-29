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

import org.dinky.data.model.DataBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * {@code @source/} 引用的路径解析与数据源解析（AI Chat FlinkSQL 上下文 P1）。
 *
 * <p><b>语法</b>：{@code @source/<数据源名>[/<库>[/<表>[.<字段>]]]}，四级粒度，严格递进
 * （不写库就不展开表、不写表就不展开字段）——避免"一句话把 380 张表全拉进上下文"。
 *
 * <p><b>为什么用 {@code /} 分隔、用 {@code .} 分隔字段</b>：库名/表名里可能出现点号（Doris 允许），
 * 但几乎不会是斜杠；把 {@code .} 专门留给"表.字段"，人与模型都不需要算"第几个点"。
 *
 * <p><b>为什么解析器不持有任何 Spring 依赖</b>：它同时服务两条链路——{@code @source/} 提及
 * （HTTP 请求线程）与 {@code describe_source} 工具（<b>异步线程</b>，租户上下文已丢失，
 * 见 {@code AiToolContext} 头注释）。前者从 {@code DataBaseService.listEnabledAll()} 建索引，
 * 后者用请求线程预解析好的快照——<b>两条链路共用同一份解析与渲染实现</b>，口径不会漂移。
 *
 * @since 2026/10/08
 */
public final class SourceRefResolver {

    /** 单段允许的字符：字母 / 数字 / 下划线 / 连字符 / 美元符 / 点（供"表.字段"）/ 中文（库表名可能含中文） */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_\\-$.\\u4e00-\\u9fa5]+");

    private static final int MAX_SEGMENT_CHARS = 64;

    /** 路径最多 4 段（数据源 / 库 / 表 / 字段） */
    private static final int MAX_SEGMENTS = 4;

    private SourceRefResolver() {}

    /** 解析结果（<b>未做存在性校验</b>，只保证语法合法、各段已清洗）。 */
    @Getter
    public static class Ref {

        /** 原始路径（已去掉首尾空白与句末点号） */
        private final String raw;

        private final String datasource;
        private final String database;
        private final String table;
        private final String column;

        Ref(String raw, String datasource, String database, String table, String column) {
            this.raw = raw;
            this.datasource = datasource;
            this.database = database;
            this.table = table;
            this.column = column;
        }

        /** 粒度层级：1=数据源 / 2=库 / 3=表 / 4=字段 */
        public int level() {
            if (column != null) {
                return 4;
            }
            if (table != null) {
                return 3;
            }
            if (database != null) {
                return 2;
            }
            return 1;
        }

        /** 归一化路径（回显 / 去重用；不含 {@code @source/} 前缀） */
        public String getNormalized() {
            StringBuilder sb = new StringBuilder(datasource);
            if (database != null) {
                sb.append('/').append(database);
            }
            if (table != null) {
                sb.append('/').append(table);
            }
            if (column != null) {
                sb.append('.').append(column);
            }
            return sb.toString();
        }
    }

    /**
     * 解析 {@code @source/} 之后的路径。
     *
     * @param path 形如 {@code traccar-mysql/traccar/tc_positions}（可含句末点号，会被剥离）
     * @return 解析结果；段数或字符不合法时返回 {@code null}
     */
    public static Ref parse(String path) {
        String trimmed = stripTrailingDots(StrUtil.trimToEmpty(path));
        if (StrUtil.isBlank(trimmed)) {
            return null;
        }
        String[] segments = trimmed.split("/");
        if (segments.length > MAX_SEGMENTS) {
            return null;
        }
        String datasource = clean(segments[0]);
        String database = segments.length > 1 ? clean(segments[1]) : null;
        String table = segments.length > 2 ? clean(segments[2]) : null;
        String column = segments.length > 3 ? clean(segments[3]) : null;
        // 允许「第 3 段写成 表.字段」：表名不允许含点，故按第一个点切分无歧义
        // （表名若真的含点，用户改用 @source/<ds>/<db>/<表>/<字段> 四段写法）
        if (table != null && column == null) {
            int dot = table.indexOf('.');
            if (dot > 0 && dot < table.length() - 1) {
                column = clean(table.substring(dot + 1));
                table = clean(table.substring(0, dot));
            }
        }
        if (datasource == null
                || (segments.length > 1 && database == null)
                || (segments.length > 2 && table == null)
                || (segments.length > 3 && column == null)) {
            return null;
        }
        return new Ref(trimmed, datasource, database, table, column);
    }

    /** 单段清洗：去掉首尾空白与点号，再做字符白名单校验（不合法返回 {@code null}）。 */
    private static String clean(String segment) {
        String value = stripTrailingDots(StrUtil.trimToEmpty(segment));
        if (StrUtil.isBlank(value) || value.length() > MAX_SEGMENT_CHARS) {
            return null;
        }
        return SEGMENT.matcher(value).matches() ? value : null;
    }

    private static String stripTrailingDots(String value) {
        String result = value;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 把「本租户启用中的数据源」建成 {@code 名字 → 数据源} 索引（保序，便于给出可选名提示）。 */
    public static Map<String, DataBase> index(List<DataBase> databases) {
        Map<String, DataBase> byName = new LinkedHashMap<>();
        if (databases == null) {
            return byName;
        }
        for (DataBase dataBase : databases) {
            if (dataBase != null && StrUtil.isNotBlank(dataBase.getName())) {
                byName.put(dataBase.getName(), dataBase);
            }
        }
        return byName;
    }

    /**
     * 按名字解析数据源：<b>精确匹配优先</b>，未命中时退回"忽略大小写且唯一"的匹配。
     *
     * <p>租户隔离由调用方保证（索引本身只含本租户的数据源），此处只解决"用户手打时大小写不一致"
     * 的可用性问题；若出现仅大小写不同的多个同名数据源，宁可返回 {@code null}（提示歧义）也不猜。
     */
    public static DataBase resolve(Map<String, DataBase> sourcesByName, String name) {
        if (sourcesByName == null || StrUtil.isBlank(name)) {
            return null;
        }
        DataBase exact = sourcesByName.get(name);
        if (exact != null) {
            return exact;
        }
        DataBase hit = null;
        for (Map.Entry<String, DataBase> entry : sourcesByName.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                if (hit != null) {
                    return null;
                }
                hit = entry.getValue();
            }
        }
        return hit;
    }

    /** 可选数据源名清单（用于失败提示；只含名字，不含连接信息）。 */
    public static String availableNames(Map<String, DataBase> sourcesByName) {
        if (sourcesByName == null || sourcesByName.isEmpty()) {
            return "（当前租户下没有启用中的数据源）";
        }
        StringBuilder sb = new StringBuilder("（可选：");
        int limit = Math.min(sourcesByName.size(), 12);
        int i = 0;
        for (String name : sourcesByName.keySet()) {
            if (i++ >= limit) {
                sb.append(" …等 ").append(sourcesByName.size()).append(" 个");
                break;
            }
            if (i > 1) {
                sb.append(" / ");
            }
            sb.append(name);
        }
        return sb.append("）").toString();
    }
}
