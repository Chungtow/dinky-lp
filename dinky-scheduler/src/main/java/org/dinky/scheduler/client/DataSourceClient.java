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

package org.dinky.scheduler.client;

import org.dinky.data.model.SystemConfiguration;
import org.dinky.scheduler.constant.Constants;
import org.dinky.scheduler.model.DsDataSource;
import org.dinky.scheduler.result.Result;
import org.dinky.scheduler.utils.MyJSONUtil;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;

/**
 * DS DataSource client - query datasource list from DolphinScheduler by URL
 */
@Component
public class DataSourceClient {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceClient.class);

    /**
     * Find a DS datasource by matching its jdbcUrl against the given url.
     *
     * @param url the jdbc url to match (e.g. jdbc:hive2://hivespark04:10000/gmall_dw)
     * @return the matching {@link DsDataSource} or null if not found
     */
    public DsDataSource findDataSourceByUrl(String url) {
        List<DsDataSource> all = listAll();
        if (all == null || all.isEmpty()) {
            logger.warn("No datasources found in DolphinScheduler");
            return null;
        }

        // Normalize url for comparison: trim, lowercase
        String normalizedUrl = url.trim().toLowerCase();

        for (DsDataSource ds : all) {
            if (ds.getConnectionParams() == null) {
                continue;
            }
            try {
                JSONObject paramsJson = new JSONObject(ds.getConnectionParams());
                // Try jdbcUrl field first, then address
                String jdbcUrl = paramsJson.getStr("jdbcUrl");
                if (jdbcUrl == null) {
                    jdbcUrl = paramsJson.getStr("address");
                }
                if (jdbcUrl != null && jdbcUrl.trim().toLowerCase().equals(normalizedUrl)) {
                    logger.info("Matched DS datasource: id={}, name={}, type={}", ds.getId(), ds.getName(), ds.getType());
                    return ds;
                }
            } catch (Exception e) {
                logger.debug("Failed to parse connectionParams for datasource id={}: {}", ds.getId(), e.getMessage());
            }
        }

        logger.warn("No DS datasource found matching url: {}", url);
        return null;
    }

    /**
     * List all datasources from DolphinScheduler.
     */
    public List<DsDataSource> listAll() {
        String format = StrUtil.format(
                SystemConfiguration.getInstances().getDolphinschedulerUrl().getValue() + "/datasources/list",
                Collections.emptyMap());

        try (HttpResponse httpResponse = HttpRequest.get(format)
                .header(
                        Constants.TOKEN,
                        SystemConfiguration.getInstances()
                                .getDolphinschedulerToken()
                                .getValue())
                .timeout(20000)
                .execute()) {
            String content = httpResponse.body();

            List<DsDataSource> list = MyJSONUtil.verifyResult(
                    MyJSONUtil.toBean(content, new TypeReference<Result<List<DsDataSource>>>() {}));

            logger.debug("Found {} datasources in DolphinScheduler", list != null ? list.size() : 0);
            return list;
        }
    }
}
