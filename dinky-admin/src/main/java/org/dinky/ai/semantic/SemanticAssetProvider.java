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
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 语义资产只读入口（P3b）——AI Chat 的 {@code @semantic/} 引用与项目清单都由它供数。
 *
 * <p><b>职责边界</b>：只读 `_published` 下<b>已发布版本</b>的索引与 YAML；不解析 dbt 语义、
 * 不调 {@code mf}、不碰编辑区（D21）。索引是派生物：读不到索引时按 YAML 原文摘录降级，
 * <b>绝不因为索引问题让 AI 拒答</b>（§4.6）。
 *
 * @since 2026/10/22
 */
public interface SemanticAssetProvider {

    /** 项目清单常驻预算（D18：代码常量，不新增配置键，避免再动设置页与迁移） */
    int PROJECT_LIST_MAX_CHARS = 6000;

    /** 项目内可引用资产清单（索引 {@code names[]}；索引缺失时降级为 YAML 文件级列表） */
    List<SemanticAssetRef> enumerate(String project);

    /**
     * 解析句柄（§4.3 定稿口径，严格不模糊匹配）。
     *
     * @param handlePath {@code "<project>"} / {@code "<project>/<name>"} /
     *     {@code "<project>/table|metric/<name>"}
     * @throws org.dinky.data.exception.BusException 项目未发布、同名歧义、句柄形态非法
     */
    SemanticAssetRef resolve(String handlePath);

    /** 渲染供注入的 Markdown（内部按 {@code maxChars} 截断，超预算时给出截断说明） */
    String read(SemanticAssetRef ref, int maxChars);

    /** 项目最新发布版本的元信息（版本 / 发布时间 / index_state / 源码指纹）；未发布返回 null */
    Meta meta(String project);

    /** 项目发布元信息（对应 `_published/<p>/<ver>/meta.json` 的只读投影） */
    @Getter
    @Setter
    class Meta implements Serializable {

        private static final long serialVersionUID = 1L;

        private String version;

        private String publishedAt;

        /** {@code ready} / {@code index_pending}；空串表示 meta.json 里没有该字段 */
        private String indexState;

        private String sourceSha256;

        private int indexFiles;

        private String indexBuiltAt;

        public boolean indexReady() {
            return "ready".equalsIgnoreCase(indexState);
        }
    }
}
