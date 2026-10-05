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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 写操作二次确认的<b>通用载荷</b>（阶段 4b）。
 *
 * <p><b>为什么需要它</b>：2c 的确认通道 {@link AiToolContext.ConfirmRequester#request(String, ChangeRisk)}
 * 的语义<b>绑定 SQL</b>（参数名就叫 {@code sql}），确认帧里也只有 {@code sql/sqlType/risk}。
 * 4b 引入的 skill 写操作<b>不是 SQL</b>——若把 skill 内容硬塞进 {@code sql} 字段，
 * 前端会按 SQL 语法高亮渲染一段 Markdown，语义完全错位。
 *
 * <p><b>向后兼容</b>：{@code ConfirmRequester} 通过<b>新增 default 方法</b>接纳本对象，
 * 旧签名原样保留 → {@code ExecSqlTool} 一行不用改，2c 的 SQL 确认链路<b>行为不变</b>。
 *
 * @since 2026/10/05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPayload {

    /** 确认类型：SQL 写语句 */
    public static final String KIND_SQL = "sql";

    /** 确认类型：写 skill / doc 内的文件 */
    public static final String KIND_SKILL_FILE = "skill_file";

    /** 确认类型：删除整个 skill / doc（破坏性，需强化确认） */
    public static final String KIND_SKILL_DELETE = "skill_delete";

    /** 确认类型（见上面的常量） */
    private String kind;

    /** 展示标题（如「写入 skill 文件」） */
    private String title;

    /** 目标资产名（skill / doc 名；仅 skill 类确认有值） */
    private String targetName;

    /** 目标相对路径（仅 {@link #KIND_SKILL_FILE} 有值） */
    private String relativePath;

    /** 变更前内容（覆盖 / 编辑场景；新建时为空，前端据此显示「新建」） */
    private String beforeContent;

    /** 变更后内容（前端展示 diff 用） */
    private String afterContent;

    /** 风险摘要（展示用；skill 场景可为空） */
    private String riskSummary;

    /**
     * 是否要求用户<b>手动输入目标名称</b>才能确认（阶段 4b：删除类破坏性操作的强化确认）。
     *
     * <p>前端据此渲染输入框；<b>后端也必须比对回投值</b>——只靠前端禁用按钮等于没有这道闸。
     */
    private boolean requireTypedName;

    /** SQL 原文（仅 {@link #KIND_SQL}；保留旧字段名以兼容既有前端逻辑） */
    private String sql;

    /** SQL 类型（仅 {@link #KIND_SQL}） */
    private String sqlType;

    /** 风险对象（仅 {@link #KIND_SQL}） */
    private ChangeRisk risk;

    /** 由既有 SQL 参数构造（2c 链路复用） */
    public static ConfirmPayload ofSql(String sql, ChangeRisk risk) {
        return ConfirmPayload.builder()
                .kind(KIND_SQL)
                .title("执行写语句")
                .sql(sql)
                .sqlType(risk == null ? null : risk.getSqlType())
                .risk(risk)
                .riskSummary(risk == null ? null : risk.toSummary())
                .build();
    }

    /** 写 skill / doc 内文件 */
    public static ConfirmPayload ofSkillFile(String targetName, String relativePath, String before, String after) {
        return ConfirmPayload.builder()
                .kind(KIND_SKILL_FILE)
                .title("写入 " + targetName + " 的文件 " + relativePath)
                .targetName(targetName)
                .relativePath(relativePath)
                .beforeContent(before)
                .afterContent(after)
                .build();
    }

    /** 删除整个 skill / doc（破坏性） */
    public static ConfirmPayload ofSkillDelete(String targetName) {
        return ConfirmPayload.builder()
                .kind(KIND_SKILL_DELETE)
                .title("删除 " + targetName)
                .targetName(targetName)
                .build();
    }
}
