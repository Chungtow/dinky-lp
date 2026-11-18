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

package org.dinky.data.model;

import org.dinky.mybatis.model.SuperEntity;

import java.util.List;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 自定义报表：一条记录 = 一个托管在注册中心（HDFS）里的 HTML 静态页。
 *
 * <p>与 {@link Dashboard}（Flink 指标看板）完全独立：那边靠 layouts 存 ECharts 图表布局，
 * 这边只存"资源引用 + 名称/备注 + 角色授权"。
 *
 * @since 2026-11-18
 */
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dinky_report")
@Data
@ApiModel(value = "Report", description = "Custom report backed by a html resource")
public class Report extends SuperEntity<Report> {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "Tenant Id", dataType = "Integer", notes = "显式赋值，禁止依赖列默认值")
    private Integer tenantId;

    @ApiModelProperty(value = "Remark", dataType = "String", notes = "报表说明")
    private String remark;

    @ApiModelProperty(value = "Resource Id", dataType = "Integer", notes = "注册中心资源ID")
    private Integer resourceId;

    @ApiModelProperty(value = "Resource Path", dataType = "String", notes = "冗余：资源 full_name，用于失效识别")
    private String resourcePath;

    @ApiModelProperty(value = "Sort Num", dataType = "Integer", notes = "排序（预留）")
    private Integer sortNum;

    /** 授权角色（不落库，随详情返回/随保存替换） */
    @TableField(exist = false)
    private List<Integer> roleIds;

    /** 资源文件名（不落库，列表展示用） */
    @TableField(exist = false)
    private String resourceName;
}
