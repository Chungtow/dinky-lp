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
 * 修改 Skill / Doc 可见性的入参（2026-10-14 三档可见性）。
 *
 * @since 2026/10/14
 */
@Getter
@Setter
@ApiModel(value = "SkillVisibilityDTO", description = "Update skill visibility")
public class SkillVisibilityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Skill Id", dataType = "Long", required = true, example = "1")
    private Long id;

    @ApiModelProperty(
            value = "Visibility",
            dataType = "String",
            required = true,
            example = "tenant",
            notes = "private（仅自己）/ tenant（同租户共享）/ public（全体公开，需 registration:skill:share 权限）")
    private String visibility;
}
