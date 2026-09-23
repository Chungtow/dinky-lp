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

package org.dinky.utils;

import org.dinky.data.model.HadoopConf;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import lombok.extern.slf4j.Slf4j;

/**
 * 读取 dinky 容器内挂载的 hadoop 配置（/opt/hadoop/conf），
 * 解析 DataX hdfswriter 所需的 defaultFS + HA 配置，实现与集群配置的共识，避免手抄。
 */
@Slf4j
public class HadoopConfReader {

    /** 容器内 hadoop 配置目录（部署脚本挂载自 config/hadoop） */
    private static final String HADOOP_CONF_DIR = "/opt/hadoop/conf";

    /** hdfswriter 需要的 HA 配置 key 前缀（选择性提取，不塞 dfs.replication 等无关字段） */
    private static final String[] HA_KEY_PREFIXES = {
        "dfs.nameservices", "dfs.ha.namenodes", "dfs.namenode.rpc-address", "dfs.client.failover.proxy.provider"
    };

    /**
     * 读取 hadoop 配置。
     *
     * @return {@link HadoopConf}
     */
    public static HadoopConf read() {
        HadoopConf conf = new HadoopConf();
        conf.setDefaultFS(readProperty(HADOOP_CONF_DIR + "/core-site.xml", "fs.defaultFS"));
        conf.setHadoopConfig(readHaConfig(HADOOP_CONF_DIR + "/hdfs-site.xml"));
        return conf;
    }

    private static String readProperty(String filePath, String key) {
        try {
            Document doc = parseXml(filePath);
            if (doc == null) {
                return "";
            }
            NodeList props = doc.getElementsByTagName("property");
            for (int i = 0; i < props.getLength(); i++) {
                Element prop = (Element) props.item(i);
                if (key.equals(getChildText(prop, "name"))) {
                    return getChildText(prop, "value");
                }
            }
        } catch (Exception e) {
            log.warn("读取 hadoop 配置失败: {} key={}", filePath, key, e);
        }
        return "";
    }

    private static Map<String, String> readHaConfig(String filePath) {
        Map<String, String> config = new HashMap<>();
        try {
            Document doc = parseXml(filePath);
            if (doc == null) {
                return config;
            }
            NodeList props = doc.getElementsByTagName("property");
            for (int i = 0; i < props.getLength(); i++) {
                Element prop = (Element) props.item(i);
                String name = getChildText(prop, "name");
                if (name == null) {
                    continue;
                }
                for (String prefix : HA_KEY_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        config.put(name, getChildText(prop, "value"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取 hdfs-site.xml 失败: {}", filePath, e);
        }
        return config;
    }

    private static Document parseXml(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("hadoop 配置文件不存在: {}", filePath);
            return null;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 禁用外部实体，防止 XXE（FEATURE_SECURE_PROCESSING 会限制 DTD/外部实体）
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }
}
