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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
            Result<Resource> result =
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {});
            if (result == null || result.getFailed()) {
                String msg = result != null ? result.getMsg() : "no response";
                log.error("Upload DS resource failed: name={}, currentDir={}, msg={}", fileName, currentDir, msg);
                throw new SchedulerException("上传文件失败: " + currentDir + "/" + fileName + " - " + msg);
            }
            Resource resource = result.getData();
            if (resource == null || resource.getId() == null) {
                log.error("Upload resource failed, response: {}", httpResponse.body());
                throw new SchedulerException("上传文件失败: " + currentDir + "/" + fileName + " - 未返回 id");
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
            Result<Resource> result =
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {});
            if (result == null || result.getFailed()) {
                String msg = result != null ? result.getMsg() : "no response";
                log.error(
                        "Create DS resource directory failed: name={}, currentDir={}, msg={}",
                        name,
                        currentDir,
                        msg);
                throw new SchedulerException("创建目录失败: " + currentDir + "/" + name + " - " + msg);
            }
            Resource resource = result.getData();
            if (resource == null || resource.getId() == null) {
                log.error("Create directory failed, response: {}", httpResponse.body());
                throw new SchedulerException("创建目录失败: " + currentDir + "/" + name + " - 未返回 id");
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
        String url = baseUrl() + "/resources/list";

        try (HttpResponse httpResponse = HttpRequest.get(url)
                .header(Constants.TOKEN, token())
                .form("type", "FILE")
                .timeout(10000)
                .execute()) {
            JSONObject root = JSONUtil.parseObj(httpResponse.body());
            if (root.getInt("code", -1) != 0) {
                log.warn("Query DS resource list failed for {}: {}", fullName, root.getStr("msg"));
                return null;
            }
            JSONArray tree = root.getJSONArray("data");
            if (tree == null) {
                return null;
            }
            return findResourceId(tree, normalizeFullName(fullName));
        } catch (Exception e) {
            log.warn("queryResourceId failed for {}: {}", fullName, e.getMessage());
            return null;
        }
    }

    /**
     * 递归在资源树中查找 fullName 匹配的资源 id（文件或目录均可，DS 返回的 fullName 无前导斜杠）。
     */
    private Integer findResourceId(JSONArray nodes, String fullName) {
        if (nodes == null) {
            return null;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (fullName.equals(normalizeFullName(node.getStr("fullName")))) {
                return node.getInt("id");
            }
            JSONArray children = node.getJSONArray("children");
            if (children != null && !children.isEmpty()) {
                Integer found = findResourceId(children, fullName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 去掉前导斜杠，统一 fullName 比较口径（dinky 传的是 /a/b，DS 返回的是 a/b）。
     */
    private String normalizeFullName(String fullName) {
        if (fullName == null) {
            return "";
        }
        String s = fullName.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * Overwrite an existing resource file in place (DS updateResource, {@code PUT /resources/{id}}).
     *
     * <p>Unlike "delete then upload", this approach does NOT fail with {@code RESOURCE_IS_USED}
     * when the resource is already referenced by a released process definition. DS uploads the new
     * file to the same HDFS path (overwriting it) and refreshes the metadata in place.</p>
     *
     * @param resourceId existing resource id
     * @param fileName   resource file name (same as before for in-place overwrite)
     * @param file       new file content
     * @param currentDir directory path for the log message (e.g. {@code /ODS/ods_traccar_tc_users})
     * @return the resource id
     */
    public Integer updateFile(Integer resourceId, String fileName, File file, String currentDir) {
        String url = baseUrl() + "/resources/" + resourceId;

        try (HttpResponse httpResponse = HttpRequest.put(url)
                .header(Constants.TOKEN, token())
                .form("type", "FILE")
                .form("name", fileName)
                .form("file", file)
                .timeout(20000)
                .execute()) {
            Result<Resource> result =
                    MyJSONUtil.toBean(httpResponse.body(), new TypeReference<Result<Resource>>() {});
            if (result == null || result.getFailed()) {
                String msg = result != null ? result.getMsg() : "no response";
                log.error("Update DS resource failed: name={}, currentDir={}, msg={}", fileName, currentDir, msg);
                throw new SchedulerException("覆盖文件失败: " + currentDir + "/" + fileName + " - " + msg);
            }
            Resource resource = result.getData();
            if (resource == null || resource.getId() == null) {
                log.error("Update resource failed, response: {}", httpResponse.body());
                throw new SchedulerException("覆盖文件失败: " + currentDir + "/" + fileName + " - 未返回 id");
            }
            log.info("Overwrote resource {} in DS, id={}", fileName, resource.getId());
            return resource.getId();
        }
    }
}
