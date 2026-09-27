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

package org.dinky.service.impl;

import org.dinky.data.model.AiChatLog;
import org.dinky.mapper.AiChatLogMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.service.AiChatLogService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 对话审计服务实现。
 *
 * <p>审计是旁路能力：<b>任何写入失败都只记日志，不阻断对话</b>。
 *
 * @since 2026/09/27
 */
@Slf4j
@Service
public class AiChatLogServiceImpl extends SuperServiceImpl<AiChatLogMapper, AiChatLog> implements AiChatLogService {

    @Override
    public void record(AiChatLog auditLog) {
        if (auditLog == null) {
            return;
        }
        try {
            if (auditLog.getCreateTime() == null) {
                auditLog.setCreateTime(LocalDateTime.now());
            }
            save(auditLog);
        } catch (Exception e) {
            log.warn("Save AI chat log failed: {}", e.getMessage());
        }
    }

    @Override
    public DailyUsage todayUsage(Integer userId) {
        if (userId == null) {
            return new DailyUsage(0, 0);
        }
        try {
            LocalDateTime start = LocalDate.now().atStartOfDay();
            LambdaQueryWrapper<AiChatLog> query = new LambdaQueryWrapper<AiChatLog>()
                    .eq(AiChatLog::getUserId, userId)
                    .ge(AiChatLog::getCreateTime, start);
            List<AiChatLog> logs = list(query);
            long tokens = 0;
            if (logs != null) {
                for (AiChatLog item : logs) {
                    tokens += nullToZero(item.getPromptTokens()) + nullToZero(item.getCompletionTokens());
                }
            }
            return new DailyUsage(logs == null ? 0 : logs.size(), tokens);
        } catch (Exception e) {
            log.warn("Query AI chat daily usage failed, userId: {}, message: {}", userId, e.getMessage());
            return new DailyUsage(0, 0);
        }
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
