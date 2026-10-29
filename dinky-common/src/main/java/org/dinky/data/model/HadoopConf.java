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

import java.util.Map;

import lombok.Data;

/**
 * Hadoop 配置：由 HadoopConfReader 从容器内 /opt/hadoop/conf 解析，供 DataX hdfswriter 使用。
 */
@Data
public class HadoopConf {

    /** fs.defaultFS，如 hdfs://mycluster */
    private String defaultFS;

    /** HDFS HA 关键配置（dfs.nameservices / dfs.ha.namenodes / dfs.namenode.rpc-address / failover.proxy.provider） */
    private Map<String, String> hadoopConfig;
}
