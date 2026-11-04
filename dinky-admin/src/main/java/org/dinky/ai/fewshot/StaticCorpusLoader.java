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

package org.dinky.ai.fewshot;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.IoUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * L1 静态语料加载器（随 jar 打包的 B1~B4 四段式骨架）。
 *
 * <p><b>为什么这是"锚点"而不是"缓存"</b>：L1 的存在意味着"线上作业被删/被改脏"不会让召回功能塌掉
 * （决策 K9）。加载失败只 WARN，返回空列表，由 L2 兜底。
 *
 * @since 2026/11/02
 */
@Slf4j
@Component
public class StaticCorpusLoader {

    private static final String[] RESOURCES = {
        "ai/fewshot/b1.yaml", "ai/fewshot/b2.yaml", "ai/fewshot/b3.yaml", "ai/fewshot/b4.yaml"
    };

    private volatile List<SimilarTask> cached;

    public List<SimilarTask> all() {
        List<SimilarTask> local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    private List<SimilarTask> load() {
        List<SimilarTask> corpus = new ArrayList<>();
        for (String resource : RESOURCES) {
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("few-shot corpus missing: {}", resource);
                    continue;
                }
                corpus.addAll(parse(IoUtil.readUtf8(in), resource));
            } catch (Exception e) {
                log.warn("few-shot corpus load failed: {}", resource, e);
            }
        }
        log.info("few-shot static corpus loaded: {} items", corpus.size());
        return corpus;
    }

    @SuppressWarnings("unchecked")
    List<SimilarTask> parse(String yamlText, String resource) {
        List<SimilarTask> items = new ArrayList<>();
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof List)) {
            log.warn("few-shot corpus is not a list: {}", resource);
            return items;
        }
        for (Object node : (List<Object>) loaded) {
            if (!(node instanceof Map)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) node;
            items.add(SimilarTask.builder()
                    .id(Convert.toStr(map.get("id")))
                    .title(Convert.toStr(map.get("title")))
                    .topics(toStringList(map.get("topics")))
                    .tables(toStringList(map.get("tables")))
                    .keywords(toStringList(map.get("keywords")))
                    .sql(Convert.toStr(map.get("sql"), ""))
                    .customConfig(Convert.toStr(map.get("customConfig"), ""))
                    .ddl(Convert.toStr(map.get("ddl"), ""))
                    .precheck(Convert.toStr(map.get("precheck"), ""))
                    .source("L1")
                    .build());
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    list.add(Convert.toStr(item));
                }
            }
        } else if (value != null) {
            list.add(Convert.toStr(value));
        }
        return list.isEmpty() ? Collections.emptyList() : list;
    }
}
