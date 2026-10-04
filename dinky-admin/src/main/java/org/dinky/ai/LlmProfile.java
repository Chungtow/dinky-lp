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

import cn.hutool.core.util.StrUtil;
import lombok.Builder;
import lombok.Getter;

/**
 * LLM 实例（profile）值对象（阶段 3：多 LLM 实例配置）。
 *
 * <p><b>不可变</b>：由 {@link LlmProfileResolver} 解析一次，之后作为<b>参数</b>在调用链上传递。
 * <b>绝不</b>写回 {@code SystemConfiguration}——它是静态单例（{@code LlmClient} 每次调用都
 * {@code getInstances()}），若用"临时改写全局值"实现选 profile，并发会话会互相串号
 * （A 用户选 A 模型，B 用户的请求打到 A 模型）。
 *
 * <p><b>安全</b>：{@link #apiKey} 只在本进程内用于拼 HTTP 请求头，<b>不得</b>进入任何对外返回
 * （前端只见 {@link #hasApiKey()}）或日志。
 *
 * @since 2026/10/04
 */
@Getter
@Builder
public class LlmProfile {

    /** 默认 profile 的固定 id：「缺省 / 未指定」等价于它 */
    public static final String DEFAULT_ID = "default";

    /** 实例 id（在 profiles 列表内唯一；默认 profile 恒为 {@link #DEFAULT_ID}） */
    private final String id;

    /** 展示名 */
    private final String name;

    private final String baseUrl;

    /** 仅服务端使用；对外以 {@link #hasApiKey()} 呈现 */
    private final String apiKey;

    private final String model;

    private final String completionsPath;

    private final int timeoutSeconds;

    private final int maxTokens;

    private final boolean stream;

    /**
     * 该模型是否支持 function calling（tools）。
     *
     * <p>为 {@code false} 时<b>禁用工具循环</b>、退化为纯问答。原因：现状对"模型不支持 tools"
     * <b>没有任何兜底</b>——{@code LlmClient} 会把 HTTP 非 200 统一转成异常，
     * {@code AiToolLoop} 又会吞掉单轮异常直接结束循环，若不声明，工具链会<b>静默失效</b>。
     */
    private final boolean supportsTools;

    /** 备注（仅供配置阅读） */
    private final String note;

    /** 是否配置了 API Key（对外只暴露这个布尔，不回明文） */
    public boolean hasApiKey() {
        return StrUtil.isNotBlank(apiKey);
    }

    /** 是否为「默认 profile」（由 {@code sys.llm.*} 单组字段构造） */
    public boolean isDefaultProfile() {
        return DEFAULT_ID.equals(id);
    }
}
