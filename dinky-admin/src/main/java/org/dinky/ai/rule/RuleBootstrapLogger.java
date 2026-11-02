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

package org.dinky.ai.rule;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时把规则加载结果打进日志（运维与 UAT 的唯一证据来源）。
 *
 * <p><b>为什么要主动预热</b>：{@link RuleLoader} 是懒加载的，规则文件配错时只有"第一次调用对话"
 * 才会暴露问题——而那时用户已经在等答案了。启动即加载 + 一行日志，能让部署脚本和运维
 * 在部署阶段就看出"覆盖文件没生效"。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleBootstrapLogger implements ApplicationRunner {

    private final RuleLoader ruleLoader;

    @Override
    public void run(ApplicationArguments args) {
        try {
            RuleSet ruleSet = ruleLoader.ruleSet();
            String external = StrUtil.blankToDefault(ruleLoader.externalPath(), "(未配置)");
            log.info(
                    "AI rule engine ready: {} rules, overlay={}, prompted={}",
                    ruleSet.size(),
                    external,
                    ruleSet.promptedRules().size());
        } catch (Exception e) {
            log.warn("AI rule engine warm-up failed (聊天仍可用，规则校验降级为内置基线)", e);
        }
    }
}
