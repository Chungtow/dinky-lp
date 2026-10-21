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

package org.dinky.ai.semantic;

import java.io.Serializable;

import lombok.Getter;
import lombok.Setter;

/**
 * 一条语义资产引用（P3b，只读）。
 *
 * <p><b>为什么要有它</b>：{@code @semantic/<project>[/<name>] } 的解析结果必须带上"从哪个
 * 不可变版本的哪个文件读"，否则注入的元数据无法标注出处（交付物④要求 `semantic://` +
 * `yaml_sha256`），也无法在索引降级时说明"这是 YAML 原文摘录"。
 *
 * <p>{@link #degraded} 是<b>内容来源</b>的标记，不是错误：索引缺失/未就绪时仍要能回答，
 * 只是给人看时要说清"口径未就绪，按原文摘录"（计划 §4.6）。
 *
 * @since 2026/10/22
 */
@Getter
@Setter
public class SemanticAssetRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 整项目引用：{@code @semantic/<project>} */
    public static final String TYPE_PROJECT = "PROJECT";

    /** 语义模型（dbt semantic model，索引句柄即其 name） */
    public static final String TYPE_TABLE = "TABLE";

    /** 指标（dbt metric） */
    public static final String TYPE_METRIC = "METRIC";

    private String type;

    private String project;

    /** 资产名；{@link #TYPE_PROJECT} 时为 null */
    private String name;

    /** 发布版本（{@code yyyyMMddHHmmss}，D16：目录名降序取最新，无 latest 指针） */
    private String version;

    /** 读取正文用的相对路径：索引切片（{@code index/tables/<n>.json}）或降级时的 YAML 路径 */
    private String path;

    /** 出处（YAML 相对路径，来自索引 {@code source_yaml}）；降级时与 {@link #path} 相同 */
    private String sourceYaml;

    /** 一句话口径（清单里显示，来自索引 {@code one_liner}） */
    private String oneLiner;

    /** YAML 指纹前 8 位（出处标注用；来自索引切片，降级时为空） */
    private String yamlSha256;

    /** true = 索引缺失/未就绪/漂移，正文来自 YAML 原文摘录 */
    private boolean degraded;

    public static SemanticAssetRef project(String project, String version) {
        SemanticAssetRef ref = new SemanticAssetRef();
        ref.setType(TYPE_PROJECT);
        ref.setProject(project);
        ref.setVersion(version);
        return ref;
    }

    public static SemanticAssetRef of(String type, String project, String name, String version, String path) {
        SemanticAssetRef ref = new SemanticAssetRef();
        ref.setType(type);
        ref.setProject(project);
        ref.setName(name);
        ref.setVersion(version);
        ref.setPath(path);
        return ref;
    }

    public boolean isProject() {
        return TYPE_PROJECT.equals(type);
    }

    /** 出处串：{@code semantic://<project>/<version>/<path>} */
    public String sourceUri() {
        return "semantic://" + project + "/" + version + "/" + (path == null ? "" : path);
    }
}
