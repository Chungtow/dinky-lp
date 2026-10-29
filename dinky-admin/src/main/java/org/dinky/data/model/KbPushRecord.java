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

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 推送台账：一条 {@code (来源, 业务键, doc_id)} 记录（批次 C，迁移 {@code .1.4.15}）。
 *
 * <p><b>为什么需要这张表</b>：三种来源里只有 skills 有业务表可挂 {@code kb_doc_id}；
 * 语义层是 HDFS 文件（Dinky 元数据库里没有任何表），资源中心虽有 {@code dinky_resources}
 * 但那是"文件管理"语义的列，硬加一列会让"资源"表承担知识库的状态。没有台账时，
 * 推送完 doc_id 只活在响应里——"这个语义文件以前推过哪几版、要清旧版该删哪个 id"全凭记忆。
 *
 * <p><b>{@code bizKey} 的取值</b>：skill = 数字 id；资源 = 资源 id；语义层 = {@code 项目/路径}。
 * 语义文件被重命名/移动后会断链，这是<b>刻意接受</b>的：重命名后本来就是一个"新文件"，
 * 旧记录留着反而能查"上一版是谁推的"。
 */
@Getter
@Setter
@TableName("dinky_kb_push_record")
@ApiModel(value = "KbPushRecord", description = "Ledger of documents pushed into knowledge bases")
public class KbPushRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "Source", notes = "semantic / skill / resource / editor")
    private String source;

    @ApiModelProperty(value = "Biz Key", notes = "业务键：skill id / 资源 id / 语义层 项目/路径")
    @TableField("biz_key")
    private String bizKey;

    @ApiModelProperty(value = "Doc Id", notes = "sha256(正文)；内容一改就是新 id")
    @TableField("doc_id")
    private String docId;

    @ApiModelProperty(value = "Collections", notes = "推送后的最终归属（逗号分隔，只加不减）")
    private String collections;

    @ApiModelProperty(value = "Filename", notes = "推送时的文件名（同一 bizKey 改过名时用来区分版本）")
    private String filename;

    @ApiModelProperty(value = "Pushed By", notes = "推送人 user id")
    @TableField("pushed_by")
    private Integer pushedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
