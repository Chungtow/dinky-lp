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

package org.dinky.data.dto;

import java.io.Serializable;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 语义资产反馈提交（P4 一期）。
 *
 * <p><b>刻意不接收 {@code version} / {@code yaml_sha256}</b>：这两个值是「这条反馈针对哪一版口径」的
 * 判据，必须由后端按提交时的最新发布版本解析——否则客户端可以随手填一个版本号，
 * 让「已随版本失效」的判断失效（见 {@code SemanticFeedbackServiceImpl#submit}）。
 */
@Getter
@Setter
@ApiModel(value = "SemanticFeedbackDTO", description = "Submit a feedback on a semantic asset")
public class SemanticFeedbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Project Name", notes = "语义项目名", required = true)
    private String projectName;

    @ApiModelProperty(value = "Asset Name", notes = "资产名（语义模型名 / 指标名）", required = true)
    private String assetName;

    @ApiModelProperty(value = "Asset Type", notes = "TABLE | METRIC（用于定位发布索引里的 yaml_sha256）")
    private String assetType;

    @ApiModelProperty(value = "Rating", notes = "1=有用 / -1=有误", required = true)
    private Integer rating;

    @ApiModelProperty(value = "Feedback Type", notes = "WRONG_DEFINITION | WRONG_DIMENSION | OUTDATED | OTHER")
    private String feedbackType;

    @ApiModelProperty(value = "Content", notes = "反馈正文（rating=-1 时建议非空，但不强制）")
    private String content;

    @ApiModelProperty(value = "Session Id", notes = "来源 AI 会话 id（可空）")
    private String sessionId;
}
