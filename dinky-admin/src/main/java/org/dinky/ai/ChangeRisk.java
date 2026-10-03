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

package org.dinky.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.Getter;

/**
 * 变更风险评估（阶段 2c-1，简版）。
 *
 * <p>在「写语句执行前」产出可读的风险信息，随二次确认框展示，并写入审计：语句类型、是否 DDL、
 * 目标对象（粗解析）、模型自报的影响范围。
 *
 * <p><b>如实边界</b>：本期<b>不做</b>精确影响行数预估（那需要把 {@code UPDATE/DELETE} 改写成
 * {@code SELECT COUNT(*)} 预跑，成本与风险都高）。因此这里的 {@link #modelEstimate} 是<b>模型自报</b>的
 * 估计值，<b>不是</b>精确值；真实受影响行数在执行后由 {@code SqlVerifier.VerifyResult#getAffectedRows()}
 * 回报。展示层必须标注「AI 估计」，不得渲染成确定值。
 *
 * @since 2026/10/03
 */
@Getter
public class ChangeRisk {

    /** 目标对象展示上限：粗解析只用于提示，不追求精确 */
    private static final int MAX_TARGET_CHARS = 120;

    /** 模型自报影响范围的长度上限 */
    private static final int MAX_ESTIMATE_CHARS = 200;

    /** DML 目标对象：UPDATE / INSERT INTO / DELETE FROM / MERGE INTO / REPLACE INTO 表名 */
    private static final Pattern DML_TARGET = Pattern.compile(
            "(?is)\\b(?:insert\\s+into|update|delete\\s+from|merge\\s+into|replace\\s+into)\\s+([A-Za-z0-9_$.\\\"`\\[\\]]+)");

    /** DDL 目标对象：ALTER / DROP / CREATE / TRUNCATE TABLE 表名 */
    private static final Pattern DDL_TARGET = Pattern.compile(
            "(?is)\\b(?:alter\\s+table|drop\\s+table|create\\s+table|truncate\\s+table)\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?([A-Za-z0-9_$.\\\"`\\[\\]]+)");

    /** 语句类型名（SELECT / METADATA / DML / DDL / UNKNOWN） */
    private final String sqlType;

    /** 是否为结构变更（DDL）——确认框按最高级别警示 */
    private final boolean ddl;

    /** 目标对象（表名，粗解析；解析不出为空串） */
    private final String target;

    /** 模型自报的影响范围（可选，非精确值） */
    private final String modelEstimate;

    private ChangeRisk(String sqlType, boolean ddl, String target, String modelEstimate) {
        this.sqlType = sqlType;
        this.ddl = ddl;
        this.target = target;
        this.modelEstimate = modelEstimate;
    }

    /**
     * 依据语句类型与原文构造风险信息。
     *
     * @param type 语句类型（来自 {@link SqlVerifier#classify(String)}）
     * @param sql 语句原文
     * @param modelEstimate 模型自报的影响范围（可空）
     */
    public static ChangeRisk of(SqlVerifier.SqlType type, String sql, String modelEstimate) {
        String typeName = type == null ? SqlVerifier.SqlType.UNKNOWN.name() : type.name();
        boolean ddl = type == SqlVerifier.SqlType.DDL;
        return new ChangeRisk(
                typeName,
                ddl,
                resolveTarget(ddl, sql),
                StrUtil.sub(StrUtil.trimToEmpty(modelEstimate), 0, MAX_ESTIMATE_CHARS));
    }

    /** 粗解析目标对象；解析不出返回空串（不阻断执行，仅展示用） */
    static String resolveTarget(boolean ddl, String sql) {
        if (StrUtil.isBlank(sql)) {
            return "";
        }
        Matcher matcher = (ddl ? DDL_TARGET : DML_TARGET).matcher(sql);
        if (!matcher.find()) {
            return "";
        }
        return StrUtil.sub(matcher.group(1).replaceAll("[\"`\\[\\]]", ""), 0, MAX_TARGET_CHARS);
    }

    /** 下发给确认框的结构化风险信息（前端据此渲染风险块） */
    public JSONObject toJson() {
        return new JSONObject()
                .set("sqlType", StrUtil.nullToEmpty(sqlType))
                .set("ddl", ddl)
                .set("target", StrUtil.nullToEmpty(target))
                .set("modelEstimate", StrUtil.nullToEmpty(modelEstimate));
    }

    /** 审计用一句话摘要 */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("type=").append(StrUtil.nullToEmpty(sqlType));
        sb.append(", ddl=").append(ddl);
        if (StrUtil.isNotBlank(target)) {
            sb.append(", target=").append(target);
        }
        if (StrUtil.isNotBlank(modelEstimate)) {
            sb.append(", estimate=").append(modelEstimate);
        }
        return sb.toString();
    }
}
