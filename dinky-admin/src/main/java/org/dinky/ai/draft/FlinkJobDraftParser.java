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

package org.dinky.ai.draft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 四段式输出解析器：从模型回复里提取 {@code ```flinksql-job} 围栏内的 JSON。
 *
 * <p><b>为什么不要求模型输出严格 JSON</b>：它会加解释、会换行、会漏引号。因此解析失败
 * <b>降级为纯文本</b>（返回 null），由前端照旧渲染 Markdown，不判失败（计划 §4.6 R7）。
 *
 * @since 2026/11/02
 */
@Slf4j
public final class FlinkJobDraftParser {

    /** 围栏标记：```flinksql-job */
    private static final Pattern FENCE =
            Pattern.compile("```\\s*flinksql-job\\s*\\r?\\n(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 兜底：模型可能只给 ```json */
    private static final Pattern FENCE_JSON =
            Pattern.compile("```\\s*json\\s*\\r?\\n(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private FlinkJobDraftParser() {}

    /** 解析一整个回复；没有围栏或 JSON 不可用返回 null（= 降级纯文本） */
    public static FlinkJobDraft parse(String reply) {
        if (StrUtil.isBlank(reply)) {
            return null;
        }
        JSONObject json = parseJson(reply, FENCE);
        if (json == null) {
            json = parseJson(reply, FENCE_JSON);
        }
        if (json == null) {
            return null;
        }
        JSONObject job = json.getJSONObject("flinksqlJob");
        return FlinkJobDraft.fromJson(job == null ? json : job);
    }

    private static JSONObject parseJson(String reply, Pattern pattern) {
        Matcher matcher = pattern.matcher(reply);
        while (matcher.find()) {
            String body = matcher.group(1).trim();
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                continue;
            }
            try {
                return JSONUtil.parseObj(body.substring(start, end + 1));
            } catch (Exception e) {
                log.debug("flinksql-job fence parse failed", e);
            }
        }
        return null;
    }
}
