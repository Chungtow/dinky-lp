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
import org.dinky.scheduler.exception.SchedulerException;
import org.dinky.scheduler.model.Resource;
import org.dinky.scheduler.result.Result;
import org.dinky.scheduler.utils.MyJSONUtil;

import java.io.File;

import org.springframework.stereotype.Component;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * DolphinScheduler resource center client.
 *
 * <p>Uploads files (e.g. {@code datax_*.json} and {@code run_datax_*.py}) to the
 * DS resource center through the REST API, so that a pushed SHELL task can
 * reference them via {@code resourceList} and the DS worker downloads them into
 * the execution directory at runtime (no SSH involved).
 */
@Slf4j
@Component
public class ResourceClient {

    private String baseUrl() {
        return SystemConfiguration.getInstances().getDolphinschedulerUrl().getValue();
    }

    private String token() {
        return SystemConfiguration.getInstances().getDolphinschedulerToken().getValue();
    }

    /**
     * Upload a file to the DS resource center root directory.
     *
     * @param fileName resource file name
     * @param file     file to upload
     * @return the created resource id
     */
    public Integer uploadFile(String fileName, File file) {
        return uploadFile(fileName, file, -1, "/");
    }

    /**
     * Upload a file to a specific directory.
     *
     * @param fileName   resource file name
     * @param file       file to upload
     * @param pid        parent directory id (-1 for root)
     * @param currentDir current directory path (e.g. {@code /gmalldw/subfolder})
     * @return the created resource id
     */
    public Integer uploadFile(String fileName, File file, int pid, String currentDir) {
        String url = baseUrl() + "/resources";

        try (HttpResponse httpResponse = HttpRequest.post(url)
                .header(Constants.TOKEN, token())
                .form("type", "FILE")
                .form("name", fileName)
                .form("pid", pid)
                .form("currentDir", currentDir)
                .form("file", file)
                .timeout(20000)
                .execute()) {
            Resource resource = MyJSONUtil.verifyResult(
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {}));
            if (resource == null || resource.getId() == null) {
                log.error("Upload resource failed, response: {}", httpResponse.body());
                throw new SchedulerException("Upload resource failed: no id returned");
            }
            log.info(
                    "Uploaded resource {} to DS, id={}, fullName={}",
                    fileName,
                    resource.getId(),
                    resource.getFullName());
            return resource.getId();
        }
    }

    /**
     * Create a directory under the given parent directory.
     *
     * @param name       directory name
     * @param pid        parent directory id (-1 for root)
     * @param currentDir parent directory path (e.g. {@code /})
     * @return the created directory id
     */
    public Integer createDirectory(String name, int pid, String currentDir) {
        String url = baseUrl() + "/resources/directory";

        try (HttpResponse httpResponse = HttpRequest.post(url)
                .header(Constants.TOKEN, token())
                .form("type", "FILE")
                .form("name", name)
                .form("pid", pid)
                .form("currentDir", currentDir)
                .timeout(20000)
                .execute()) {
            Resource resource = MyJSONUtil.verifyResult(
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {}));
            if (resource == null || resource.getId() == null) {
                log.error("Create directory failed, response: {}", httpResponse.body());
                throw new SchedulerException("Create directory failed: no id returned");
            }
            log.info("Created DS resource directory {} (id={})", name, resource.getId());
            return resource.getId();
        }
    }

    /**
     * Query a resource id by its full name (e.g. {@code /gmalldw/subfolder}).
     * Returns {@code null} when the resource does not exist.
     *
     * @param fullName resource full name
     * @return the resource id, or {@code null} if not exist
     */
    public Integer queryResourceId(String fullName) {
        String url = baseUrl() + "/resources/0";

        try (HttpResponse httpResponse = HttpRequest.get(url)
                .header(Constants.TOKEN, token())
                .form("type", "FILE")
                .form("fullName", fullName)
                .timeout(10000)
                .execute()) {
            Resource resource = MyJSONUtil.verifyResult(
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {}));
            return resource != null ? resource.getId() : null;
        } catch (SchedulerException e) {
            // resource not exist
            return null;
        }
    }

    /**
     * Delete a resource by id.
     *
     * @param resourceId resource id
     */
    public void deleteResource(Integer resourceId) {
        String url = baseUrl() + "/resources/" + resourceId;

        try (HttpResponse httpResponse = HttpRequest.delete(url)
                .header(Constants.TOKEN, token())
                .timeout(10000)
                .execute()) {
            MyJSONUtil.verifyResult(MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Object>>() {}));
            log.info("Deleted DS resource id={}", resourceId);
        }
    }
}
