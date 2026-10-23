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

import org.dinky.ai.semantic.SemanticAssetProvider;
import org.dinky.data.dto.SemanticFeedbackDTO;
import org.dinky.data.dto.SemanticFeedbackResolveDTO;
import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SemanticFeedback;
import org.dinky.data.vo.SemanticFeedbackSummaryVO;
import org.dinky.mapper.SemanticFeedbackMapper;
import org.dinky.mybatis.service.impl.SuperServiceImpl;
import org.dinky.resource.semantic.SemanticHdfsClient;
import org.dinky.service.SemanticFeedbackService;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SemanticFeedbackService} 实现（P4 一期）。
 *
 * <p><b>为什么版本与指纹由后端解析</b>：反馈的价值在于「针对哪一版口径」。
 * 若交给前端传，客户端可以随手写一个版本号，使「已随版本失效」这条判断彻底失效；
 * 因此这里统一按提交时刻的<b>最新发布版本</b>解析 {@code version} 与 {@code yaml_sha256}，
 * 解析不出来就留空——<b>收得到反馈</b>永远优先于「元数据填满」。
 *
 * <p><b>为什么 summary 直接全表读</b>：本表是「意见箱」，量级在百条内（期初为 0），
 * 用 4 次 count + 1 次全表读换来实现简单、可与手工 SQL 对照，比引入聚合 Mapper 更划算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticFeedbackServiceImpl extends SuperServiceImpl<SemanticFeedbackMapper, SemanticFeedback>
        implements SemanticFeedbackService {

    /** 列表默认 / 最大条数：列表是「待办」视图，不需要全量翻页 */
    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 500;

    /** 反馈正文上限（TEXT 列，但没必要存一整篇文档） */
    private static final int CONTENT_MAX = 4000;

    /** 处理说明上限 */
    private static final int NOTE_MAX = 2000;

    /** 列宽上限：与 V20261023.1.4.9 的 DDL 对齐，避免严格模式下超长导致整条反馈插不进去 */
    private static final int PROJECT_MAX = 64;

    private static final int ASSET_MAX = 128;

    private static final int SESSION_MAX = 64;

    /** 近 N 天新增（运营看「有没有人在用」） */
    private static final int RECENT_DAYS = 7;

    private static final String INDEX_TABLE_DIR = "index/tables/";

    private static final String INDEX_METRIC_DIR = "index/metrics/";

    private static final String INDEX_SUFFIX = ".json";

    private static final String TYPE_TABLE = "TABLE";

    private static final String TYPE_METRIC = "METRIC";

    private static final String UNSPECIFIED = "UNSPECIFIED";

    private final SemanticAssetProvider semanticAssetProvider;

    private final SemanticHdfsClient client = SemanticHdfsClient.getInstance();

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Long submit(SemanticFeedbackDTO dto) {
        if (dto == null || StrUtil.isBlank(dto.getProjectName()) || StrUtil.isBlank(dto.getAssetName())) {
            throw new BusException(Status.SEMANTIC_FEEDBACK_ASSET_REQUIRED);
        }
        Integer rating = dto.getRating();
        if (rating == null || (rating != SemanticFeedback.RATING_UP && rating != SemanticFeedback.RATING_DOWN)) {
            throw new BusException(Status.SEMANTIC_FEEDBACK_RATING_ILLEGAL);
        }

        SemanticFeedback entity = new SemanticFeedback();
        entity.setProjectName(truncate(dto.getProjectName(), PROJECT_MAX));
        entity.setAssetName(truncate(dto.getAssetName(), ASSET_MAX));
        entity.setAssetType(normalizeAssetType(dto.getAssetType()));
        entity.setRating(rating);
        entity.setFeedbackType(normalizeFeedbackType(dto.getFeedbackType()));
        entity.setContent(truncate(dto.getContent(), CONTENT_MAX));
        entity.setSessionId(truncate(dto.getSessionId(), SESSION_MAX));
        entity.setStatus(SemanticFeedback.STATUS_OPEN);
        entity.setUserId(currentUserId());
        fillVersionAndSha(entity);

        save(entity);
        log.info(
                "semantic feedback submitted, id={}, project={}, asset={}, type={}, rating={}, version={}",
                entity.getId(),
                entity.getProjectName(),
                entity.getAssetName(),
                entity.getAssetType(),
                entity.getRating(),
                entity.getVersion());
        return entity.getId();
    }

    @Override
    public List<SemanticFeedback> listFeedback(String projectName, String assetName, String status, Integer limit) {
        LambdaQueryWrapper<SemanticFeedback> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(projectName)) {
            wrapper.eq(SemanticFeedback::getProjectName, projectName.trim());
        }
        if (StrUtil.isNotBlank(assetName)) {
            wrapper.eq(SemanticFeedback::getAssetName, assetName.trim());
        }
        if (StrUtil.isNotBlank(status)) {
            wrapper.eq(SemanticFeedback::getStatus, status.trim().toUpperCase());
        }
        // 待办视图：最新提交的在最前；task_id 无关，故用 create_time + id 双键保证稳定顺序
        wrapper.orderByDesc(SemanticFeedback::getCreateTime).orderByDesc(SemanticFeedback::getId);
        wrapper.last("LIMIT " + normalizeLimit(limit));
        return list(wrapper);
    }

    @Override
    public void resolve(Long id, SemanticFeedbackResolveDTO dto) {
        if (id == null) {
            throw new BusException(Status.SEMANTIC_FEEDBACK_NOT_FOUND);
        }
        SemanticFeedback entity = getById(id);
        if (entity == null) {
            throw new BusException(Status.SEMANTIC_FEEDBACK_NOT_FOUND);
        }
        String status = StrUtil.blankToDefault(StrUtil.trim(dto == null ? null : dto.getStatus()), "")
                .toUpperCase();
        // 只允许收敛到「已处理 / 已忽略」；不接受把状态改回 OPEN（要重开就再提一条，保留历史）
        if (!SemanticFeedback.STATUS_RESOLVED.equals(status) && !SemanticFeedback.STATUS_IGNORED.equals(status)) {
            throw new BusException(Status.SEMANTIC_FEEDBACK_STATUS_ILLEGAL);
        }
        entity.setStatus(status);
        entity.setResolveNote(truncate(dto.getNote(), NOTE_MAX));
        entity.setResolverId(currentUserId());
        entity.setResolveTime(LocalDateTime.now());
        updateById(entity);
        log.info("semantic feedback resolved, id={}, status={}, resolver={}", id, status, entity.getResolverId());
    }

    @Override
    public SemanticFeedbackSummaryVO summary(String projectName) {
        LambdaQueryWrapper<SemanticFeedback> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(projectName)) {
            wrapper.eq(SemanticFeedback::getProjectName, projectName.trim());
        }
        List<SemanticFeedback> all = list(wrapper);

        SemanticFeedbackSummaryVO vo = new SemanticFeedbackSummaryVO();
        LocalDateTime recentFrom = LocalDateTime.now().minusDays(RECENT_DAYS);
        for (SemanticFeedback item : all) {
            vo.setTotal(vo.getTotal() + 1);
            String status = StrUtil.nullToEmpty(item.getStatus());
            if (SemanticFeedback.STATUS_OPEN.equals(status)) {
                vo.setOpen(vo.getOpen() + 1);
            } else if (SemanticFeedback.STATUS_RESOLVED.equals(status)) {
                vo.setResolved(vo.getResolved() + 1);
            } else if (SemanticFeedback.STATUS_IGNORED.equals(status)) {
                vo.setIgnored(vo.getIgnored() + 1);
            }
            if (item.getCreateTime() != null && item.getCreateTime().isAfter(recentFrom)) {
                vo.setRecent7d(vo.getRecent7d() + 1);
            }
            Integer rating = item.getRating();
            if (rating != null && rating == SemanticFeedback.RATING_DOWN) {
                vo.setNegative(vo.getNegative() + 1);
            } else if (rating != null && rating == SemanticFeedback.RATING_UP) {
                vo.setPositive(vo.getPositive() + 1);
            }
            String type = StrUtil.blankToDefault(item.getFeedbackType(), UNSPECIFIED);
            vo.getByFeedbackType().merge(type, 1L, Long::sum);
        }
        return vo;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 解析「这条反馈针对哪一版口径」：项目最新发布版本 + 该资产 YAML 的 sha256。
     *
     * <p>任何一步取不到都只是留空，不抛异常——HDFS 抖动不该让用户提交不了意见。
     */
    private void fillVersionAndSha(SemanticFeedback entity) {
        try {
            SemanticAssetProvider.Meta meta = semanticAssetProvider.meta(entity.getProjectName());
            if (meta == null || StrUtil.isBlank(meta.getVersion())) {
                // 项目尚未发布：反馈仍然有效（针对编辑区口径），只是没有版本可记
                return;
            }
            entity.setVersion(meta.getVersion());

            String slicePath = resolveSlicePath(entity.getProjectName(), meta.getVersion(), entity);
            if (slicePath == null) {
                return;
            }
            JsonNode slice =
                    mapper.readTree(client.readPublished(entity.getProjectName(), meta.getVersion(), slicePath));
            String sha = slice.path("yaml_sha256").asText("");
            if (StrUtil.isNotBlank(sha)) {
                entity.setYamlSha256(sha);
            }
        } catch (Exception e) {
            log.warn(
                    "resolve semantic asset version/sha256 failed (feedback is still saved): project={}, asset={}",
                    entity.getProjectName(),
                    entity.getAssetName(),
                    e);
        }
    }

    /**
     * 定位该资产在发布索引里的切片文件。
     *
     * <p>索引未就绪（{@code index_pending}）时不存在切片文件 → 返回 null，只记版本。
     * 资产类型未知时先试 TABLE 再试 METRIC（同名表与指标确实可能并存，见 {@code SEMANTIC_HANDLE_AMBIGUOUS}）。
     */
    private String resolveSlicePath(String project, String version, SemanticFeedback entity) {
        String name = entity.getAssetName();
        String type = entity.getAssetType();
        if (TYPE_METRIC.equals(type)) {
            String path = INDEX_METRIC_DIR + name + INDEX_SUFFIX;
            return client.publishedExists(project, version, path) ? path : null;
        }
        if (TYPE_TABLE.equals(type)) {
            String path = INDEX_TABLE_DIR + name + INDEX_SUFFIX;
            return client.publishedExists(project, version, path) ? path : null;
        }
        String tablePath = INDEX_TABLE_DIR + name + INDEX_SUFFIX;
        if (client.publishedExists(project, version, tablePath)) {
            return tablePath;
        }
        String metricPath = INDEX_METRIC_DIR + name + INDEX_SUFFIX;
        return client.publishedExists(project, version, metricPath) ? metricPath : null;
    }

    /** 资产类型：只认 TABLE / METRIC，其它（含空）留空以便「表/指标都能试」 */
    private String normalizeAssetType(String assetType) {
        String value = StrUtil.trim(assetType);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String upper = value.toUpperCase();
        return (TYPE_TABLE.equals(upper) || TYPE_METRIC.equals(upper)) ? upper : null;
    }

    /** 问题类型：约定的四类之外不拒绝（避免新增类型要改后端），只做长度与大小写归一 */
    private String normalizeFeedbackType(String feedbackType) {
        String value = StrUtil.trim(feedbackType);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String upper = value.toUpperCase();
        return upper.length() > 32 ? upper.substring(0, 32) : upper;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 当前登录用户 id。
     *
     * <p>取不到不报错：反馈是「意见箱」，落不到提交人也不该丢内容
     * （AI 工具等异步线程里 Sa-Token 上下文本就为空）。
     */
    private Integer currentUserId() {
        try {
            return StpUtil.getLoginIdAsInt();
        } catch (Exception e) {
            log.debug("current user id unavailable when submitting semantic feedback", e);
            return null;
        }
    }

    private String truncate(String text, int max) {
        String value = StrUtil.trim(text);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
