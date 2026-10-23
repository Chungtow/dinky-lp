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

package org.dinky.service;

import org.dinky.data.dto.SemanticFeedbackDTO;
import org.dinky.data.dto.SemanticFeedbackResolveDTO;
import org.dinky.data.model.SemanticFeedback;
import org.dinky.data.vo.SemanticFeedbackSummaryVO;
import org.dinky.mybatis.service.ISuperService;

import java.util.List;

/**
 * 语义资产反馈服务（P4 一期）。
 *
 * <p><b>权限口径</b>：提交反馈 = 任何有 {@code datastudio:semantic:view} 的人（收得到反馈才有意义）；
 * 处理反馈 = 新增的 {@code datastudio:semantic:feedback}。权限校验在 Controller 层，
 * 本服务只负责业务规则（合法性、状态机、版本/指纹解析）。
 */
public interface SemanticFeedbackService extends ISuperService<SemanticFeedback> {

    /**
     * 提交一条反馈。
     *
     * <p>会顺带解析「这条反馈针对哪一版口径」：项目最新发布版本 + 该资产 YAML 的 sha256。
     * 解析失败不阻断提交（以 null 落库）——<b>收得到反馈</b>比「元数据完整」重要。
     *
     * @param dto 提交内容
     * @return 新记录 id
     */
    Long submit(SemanticFeedbackDTO dto);

    /**
     * 反馈列表（按时间倒序）。
     *
     * @param projectName 项目名（可空 = 全部项目）
     * @param assetName   资产名（可空）
     * @param status      OPEN / RESOLVED / IGNORED（可空 = 全部）
     * @param limit       上限（默认 100，最大 500）
     */
    List<SemanticFeedback> listFeedback(String projectName, String assetName, String status, Integer limit);

    /** 处理反馈：OPEN → RESOLVED / IGNORED，记录处理人、说明与时间 */
    void resolve(Long id, SemanticFeedbackResolveDTO dto);

    /** 汇总计数（可按项目过滤；{@code projectName} 为空表示全局） */
    SemanticFeedbackSummaryVO summary(String projectName);
}
