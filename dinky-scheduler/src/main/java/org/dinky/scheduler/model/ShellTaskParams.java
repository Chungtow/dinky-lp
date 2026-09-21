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

import java.util.ArrayList;
import java.util.List;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * DolphinScheduler SHELL task parameters.
 *
 * <p>Used when Dinky pushes a DataX task to DolphinScheduler as a native SHELL
 * task: the DataX wrapper script is placed in {@code rawScript}, and the uploaded
 * {@code datax_*.json} + {@code run_datax_*.py} resources are referenced through
 * {@code resourceList} (the DS worker downloads them into the execution directory
 * before running the script).
 */
@Data
public class ShellTaskParams {

    @ApiModelProperty(value = "自定义参数")
    private List<Property> localParams = new ArrayList<>();

    @ApiModelProperty(value = "shell 脚本内容")
    private String rawScript;

    @ApiModelProperty(value = "资源列表")
    private List<ResourceInfo> resourceList = new ArrayList<>();

    /** A resource reference in a DS task's {@code resourceList}. */
    @Data
    public static class ResourceInfo {

        @ApiModelProperty(value = "资源 id")
        private Integer id;

        @ApiModelProperty(value = "资源全名")
        private String resourceName;

        @ApiModelProperty(value = "资源名")
        private String res;
    }
}
