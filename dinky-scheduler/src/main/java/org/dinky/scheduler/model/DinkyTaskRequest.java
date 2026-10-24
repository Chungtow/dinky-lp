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

package org.dinky.scheduler.model;

import org.dinky.data.model.SystemConfiguration;

import java.util.Arrays;
import java.util.List;

import javax.validation.constraints.NotNull;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class DinkyTaskRequest extends TaskRequest {

    @ApiModelProperty(value = "自定义参数")
    private List<Property> localParams;

    @ApiModelProperty(value = "dinky地址")
    @NotNull
    private String address = SystemConfiguration.getInstances().getDinkyAddr().getValue();

    @ApiModelProperty(value = "上游任务编号")
    List<String> upstreamCodes = Arrays.asList();

    @ApiModelProperty(value = "dinky任务id", required = true)
    @NotNull
    private String taskId;

    /**
     * DS 工作流描述（租户 / 目录 / 作业清单），由 dinky-admin 构造。
     *
     * <p>不参与 {@code BeanUtil.copyProperties(dinkyTaskRequest, taskRequest)} 的任务参数复制
     * （DS 的 TaskRequest 无此字段），仅用于回写 process definition。
     */
    @ApiModelProperty(value = "工作流描述（租户/目录/作业清单）")
    private String processDescription;
}
