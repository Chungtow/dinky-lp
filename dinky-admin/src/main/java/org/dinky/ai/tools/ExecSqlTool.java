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

package org.dinky.ai.tools;

import org.dinky.ai.AiChatRunRegistry;
import org.dinky.ai.AiTool;
import org.dinky.ai.AiToolContext;
import org.dinky.ai.AiToolResult;
import org.dinky.ai.AiToolSpec;
import org.dinky.ai.ChangeRisk;
import org.dinky.ai.SqlVerifier;
import org.dinky.data.model.SystemConfiguration;

import java.util.Collections;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行 SQL（阶段 2c-1：执行类工具 + 变更风险评估）。
 *
 * <p><b>这是第一个「有副作用」的工具</b>，因此比只读工具多两道闸门：
 * <ol>
 *   <li><b>语句分级</b>：{@link SqlVerifier#rejectReason} 判定——多语句 / 无法识别 / DML·DDL 开关未开
 *       一律直接拒绝，<b>不进入确认</b>；</li>
 *   <li><b>执行前二次确认</b>：写语句（DML / DDL）必须先经用户拍板（阶段 2c-0 的确认闸门），
 *       并把 {@link ChangeRisk} 风险信息一并展示。拒绝 / 超时一律不执行。</li>
 * </ol>
 *
 * <p><b>开关</b>：默认<b>关闭</b>（{@code sys.llm.settings.toolExecSqlEnable}）——关闭时本工具
 * 不出现在下发给模型的 tools 清单里（见 {@link org.dinky.ai.AiToolRegistry}）。
 *
 * <p><b>安全约定</b>：返回值只含执行结果摘要（行数 / 受影响行数），<b>不回传数据行</b>，也不含任何
 * 数据源信息（对齐 {@link AiTool} 的约定）。需要看业务数据请使用编辑器或 sample_rows 工具。
 *
 * @since 2026/10/03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecSqlTool implements AiTool {

    public static final String NAME = "exec_sql";

    /** SQL 长度上限：与编辑器上下文口径一致，过长直接拒绝（避免拖垮连接与审计） */
    private static final int MAX_SQL_CHARS = 8000;

    private final SqlVerifier sqlVerifier;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isEnabled(SystemConfiguration config) {
        return config != null && config.isLlmToolExecSqlEnable();
    }

    /**
     * 写类工具的超时。
     *
     * <p>必须 ≥ 二次确认的等待时长，否则「等用户拍板」的过程会被 {@code AiToolExecutor} 的超时先掐断
     * （详见 {@link AiChatRunRegistry#writeToolTimeoutSeconds(int)}）。
     */
    @Override
    public int timeoutSeconds() {
        return AiChatRunRegistry.writeToolTimeoutSeconds(
                SystemConfiguration.getInstances().getLlmToolWriteTimeoutSeconds());
    }

    @Override
    public AiToolSpec spec() {
        JSONObject properties = new JSONObject();
        properties.set("sql", AiToolSpec.stringProperty("要执行的单条 SQL（不支持多语句）"));
        properties.set("expectedImpact", AiToolSpec.stringProperty("可选：预计影响范围（如'更新约 3 条设备记录'）。写操作会展示给用户确认，请如实估计"));
        properties.set("reason", AiToolSpec.stringProperty("可选：为什么需要执行这条语句"));
        return AiToolSpec.builder()
                .name(NAME)
                .description("执行一条 SQL 并返回执行结果摘要（查询返回行数 / 写操作返回受影响行数）。"
                        + "只读语句（SELECT 等）直接执行；写语句（INSERT/UPDATE/DELETE/CREATE/ALTER/DROP 等）"
                        + "会向用户请求二次确认——用户拒绝或未确认时不会执行，且不要重试。"
                        + "仅在用户明确要求执行（尤其是写操作）时使用；"
                        + "问'有哪些表''表结构是什么'请改用 list_tables / describe_table。")
                .parameters(AiToolSpec.objectSchema(null, properties, Collections.singletonList("sql")))
                .build();
    }

    @Override
    public AiToolResult execute(JSONObject args, AiToolContext context) {
        long start = System.currentTimeMillis();
        if (context == null || context.getDataBase() == null) {
            return AiToolResult.failure("未绑定数据源，无法执行 SQL", 0L);
        }
        String sql = args == null ? null : StrUtil.trimToNull(args.getStr("sql"));
        if (sql == null) {
            return AiToolResult.failure("缺少 sql 参数", System.currentTimeMillis() - start);
        }
        if (sql.length() > MAX_SQL_CHARS) {
            return AiToolResult.failure(
                    "SQL 过长（超过 " + MAX_SQL_CHARS + " 字符），已拒绝执行", System.currentTimeMillis() - start);
        }

        SqlVerifier.SqlType type = sqlVerifier.classify(sql);
        // ① 语句分级：被策略禁止（多语句 / 无法识别 / DML·DDL 开关未开）→ 直接拒绝，不进入确认
        String reject = sqlVerifier.rejectReason(type);
        if (reject != null) {
            return AiToolResult.failure(reject, System.currentTimeMillis() - start);
        }

        boolean write = type == SqlVerifier.SqlType.DML || type == SqlVerifier.SqlType.DDL;
        ChangeRisk risk = ChangeRisk.of(type, sql, args.getStr("expectedImpact"));
        if (write) {
            // ② 二次确认（阶段 2c-0 闸门）：未注入确认通道时一律拒绝——fail-safe，绝不能"自行执行"
            AiToolContext.ConfirmRequester requester = context.getConfirmRequester();
            if (requester == null) {
                log.warn("exec_sql write rejected: no confirm channel, runId={}", context.getRunId());
                return AiToolResult.failure("当前会话不支持写操作二次确认，已拒绝执行", System.currentTimeMillis() - start);
            }
            log.info(
                    "exec_sql requesting confirmation, runId={}, risk={}, reason={}",
                    context.getRunId(),
                    risk.toSummary(),
                    StrUtil.sub(StrUtil.trimToEmpty(args.getStr("reason")), 0, 200));
            if (!requester.request(sql, risk)) {
                return AiToolResult.builder()
                        .success(false)
                        .errorMessage("用户未确认执行该写语句，已取消；请勿重试，可改为只读方案或提示用户手动执行")
                        .costMs(System.currentTimeMillis() - start)
                        .riskSummary(risk.toSummary())
                        .build();
            }
        }

        // ③ 执行：读 / 写两条通道由 SqlVerifier 内部分流（写通道仅在分级通过后被调用）
        SqlVerifier.VerifyResult result = sqlVerifier.verify(context.getDataBase(), sql);
        long cost = System.currentTimeMillis() - start;
        if (result.isRejected()) {
            return AiToolResult.failure(StrUtil.blankToDefault(result.getError(), "语句被安全策略拒绝"), cost);
        }
        if (!result.isExecuted()) {
            return AiToolResult.failure(StrUtil.blankToDefault(result.getError(), "语句未执行"), cost);
        }
        if (!result.isSuccess()) {
            return AiToolResult.failure("执行失败：" + StrUtil.blankToDefault(result.getError(), "数据源未返回具体错误"), cost);
        }

        if (write) {
            int affected = result.getAffectedRows();
            return AiToolResult.builder()
                    .success(true)
                    .content("执行成功：" + risk.getSqlType() + "，受影响行数 "
                            + (affected < 0 ? "未知" : String.valueOf(affected)) + "（目标："
                            + StrUtil.blankToDefault(risk.getTarget(), "未解析出表名") + "）")
                    .costMs(cost)
                    .affectedRows(affected < 0 ? null : affected)
                    .riskSummary(risk.toSummary() + ", affected=" + affected)
                    .build();
        }
        // 只读：只回报行数，不取回数据行（业务数据不出库；要看数据请用编辑器或 sample_rows）
        return AiToolResult.success("查询成功：返回 " + result.getRowCount() + " 行（此处仅回报行数，未取回数据行）", cost);
    }
}
