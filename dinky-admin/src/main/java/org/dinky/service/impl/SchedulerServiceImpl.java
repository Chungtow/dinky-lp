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

package org.dinky.service.impl;

import org.dinky.config.Dialect;
import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Catalogue;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Task;
import org.dinky.data.model.ext.TaskExtConfig;
import org.dinky.init.SystemInit;
import org.dinky.mapper.TaskMapper;
import org.dinky.scheduler.client.ProcessClient;
import org.dinky.scheduler.client.ResourceClient;
import org.dinky.scheduler.client.TaskClient;
import org.dinky.scheduler.enums.ReleaseState;
import org.dinky.scheduler.exception.SchedulerException;
import org.dinky.scheduler.model.DagData;
import org.dinky.scheduler.model.DagNodeLocation;
import org.dinky.scheduler.model.DinkyTaskParams;
import org.dinky.scheduler.model.DinkyTaskRequest;
import org.dinky.scheduler.model.ProcessDefinition;
import org.dinky.scheduler.model.ProcessTaskRelation;
import org.dinky.scheduler.model.Project;
import org.dinky.scheduler.model.Property;
import org.dinky.scheduler.model.ShellTaskParams;
import org.dinky.scheduler.model.TaskDefinition;
import org.dinky.scheduler.model.TaskGroup;
import org.dinky.scheduler.model.TaskMainInfo;
import org.dinky.scheduler.model.TaskRequest;
import org.dinky.service.SchedulerService;
import org.dinky.service.catalogue.CatalogueService;
import org.dinky.utils.JsonUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SchedulerServiceImpl implements SchedulerService {

    public static final String TASK_TYPE = "DINKY";
    public static final String SHELL_TASK_TYPE = "SHELL";
    private final ProcessClient processClient;
    private final ResourceClient resourceClient;
    private final TaskClient taskClient;
    private final CatalogueService catalogueService;
    private final TaskMapper taskMapper;

    /**
     * Pushes the specified DinkyTaskRequest to the task queue.
     *
     * @param  dinkyTaskRequest  the DinkyTaskRequest to be added to the task queue
     * @return                  true if the task was successfully added, false otherwise
     */
    @Override
    public boolean pushAddTask(DinkyTaskRequest dinkyTaskRequest) {
        // Use root catalog as process (workflow) name.
        Catalogue catalogue = catalogueService.getOne(
                new LambdaQueryWrapper<Catalogue>().eq(Catalogue::getTaskId, dinkyTaskRequest.getTaskId()));
        if (catalogue == null) {
            log.error(Status.DS_GET_NODE_LIST_ERROR.getMessage());
            throw new BusException(Status.DS_GET_NODE_LIST_ERROR);
        }

        // DataX tasks are pushed as native DS SHELL tasks (executed on DS workers
        // via the resource center), instead of DINKY callback tasks.
        Task task = taskMapper.selectById(Integer.valueOf(dinkyTaskRequest.getTaskId()));
        if (task != null && Dialect.DATAX.name().equalsIgnoreCase(task.getDialect())) {
            return pushDataXTask(catalogue, task, dinkyTaskRequest);
        }

        DinkyTaskParams dinkyTaskParams = new DinkyTaskParams();
        dinkyTaskParams.setTaskId(dinkyTaskRequest.getTaskId());
        dinkyTaskParams.setAddress(
                SystemConfiguration.getInstances().getDinkyAddr().getValue());

        // Inject task parameters from config_json.taskParams as DS localParams
        // (e.g. pt=$[yyyyMMdd-1], tag=${system.biz.curdate})
        injectTaskLocalParams(dinkyTaskRequest.getTaskId(), dinkyTaskParams);

        return pushTaskToDs(catalogue, dinkyTaskRequest, TASK_TYPE, JsonUtils.toJsonString(dinkyTaskParams));
    }

    /**
     * Push a DataX task to DolphinScheduler as a native SHELL task.
     */
    private boolean pushDataXTask(Catalogue catalogue, Task task, DinkyTaskRequest dinkyTaskRequest) {
        ShellTaskParams shellTaskParams = prepareDataXShellParams(catalogue, task);
        return pushTaskToDs(catalogue, dinkyTaskRequest, SHELL_TASK_TYPE, JsonUtils.toJsonString(shellTaskParams));
    }

    /**
     * Push the given Dinky task to DolphinScheduler with the given task type and params.
     */
    private boolean pushTaskToDs(
            Catalogue catalogue, DinkyTaskRequest dinkyTaskRequest, String taskType, String taskParamsJson) {
        dinkyTaskRequest.setTaskParams(taskParamsJson);
        dinkyTaskRequest.setTaskType(taskType);

        String processName = getDinkyNames(catalogue, 0);
        long projectCode = SystemInit.getProject().getCode();
        // Get process from dolphin scheduler
        ProcessDefinition process = processClient.getProcessDefinitionInfo(projectCode, processName);

        String taskName = catalogue.getName();
        dinkyTaskRequest.setName(taskName);

        TaskRequest taskRequest = new TaskRequest();
        Long taskCode = taskClient.genTaskCode(projectCode);

        // If the process does not exist, a process needs to be created.
        if (process == null) {
            dinkyTaskRequest.setCode(taskCode);
            BeanUtil.copyProperties(dinkyTaskRequest, taskRequest);
            taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
            taskRequest.setFlag(dinkyTaskRequest.getFlag());
            taskRequest.setIsCache(dinkyTaskRequest.getIsCache());
            taskRequest.setTaskGroupId(dinkyTaskRequest.getTaskGroupId());
            taskRequest.setTaskGroupPriority(dinkyTaskRequest.getTaskGroupPriority());
            JSONObject jsonObject = JsonUtils.toBean(taskRequest, JSONObject.class);
            JSONArray taskArray = new JSONArray();
            taskArray.set(jsonObject);
            log.info(Status.DS_ADD_WORK_FLOW_DEFINITION_SUCCESS.getMessage());

            DagNodeLocation dagNodeLocation = new DagNodeLocation();
            dagNodeLocation.setTaskCode(taskCode);
            dagNodeLocation.setX(RandomUtil.randomLong(200, 800));
            dagNodeLocation.setY(RandomUtil.randomLong(100, 600));
            log.info("DagNodeLocation Info: {}", dagNodeLocation);

            ProcessTaskRelation processTaskRelation = ProcessTaskRelation.generateProcessTaskRelation(taskCode);
            JSONObject processTaskRelationJson = JsonUtils.toBean(processTaskRelation, JSONObject.class);
            JSONArray taskRelationArray = new JSONArray();
            taskRelationArray.set(processTaskRelationJson);

            processClient.createOrUpdateProcessDefinition(
                    projectCode,
                    null,
                    processName,
                    taskCode,
                    taskRelationArray.toString(),
                    taskArray.toString(),
                    Collections.singletonList(dagNodeLocation),
                    false);
            return true;
        }

        // If the workflow is in an online state, it cannot be updated.
        if (process.getReleaseState() == ReleaseState.ONLINE) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_ONLINE.getMessage(), processName);
        }

        TaskMainInfo taskMainInfo = taskClient.getTaskMainInfo(projectCode, processName, taskName, taskType);
        // If task name exist, update task definition.
        if (taskMainInfo != null) {
            log.warn(Status.DS_WORK_FLOW_DEFINITION_TASK_NAME_EXIST.getMessage(), processName, taskName);
            return pushUpdateTask(
                    projectCode, taskMainInfo.getProcessDefinitionCode(), taskMainInfo.getTaskCode(), dinkyTaskRequest);
        }
        // If the task does not exist, a dinky task needs to be created.
        dinkyTaskRequest.setCode(taskCode);
        BeanUtil.copyProperties(dinkyTaskRequest, taskRequest);
        taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
        taskRequest.setFlag(dinkyTaskRequest.getFlag());
        taskRequest.setIsCache(dinkyTaskRequest.getIsCache());
        taskRequest.setTaskGroupId(dinkyTaskRequest.getTaskGroupId());
        taskRequest.setTaskGroupPriority(dinkyTaskRequest.getTaskGroupPriority());

        String taskDefinitionJsonObj = JsonUtils.toJsonString(taskRequest);
        taskClient.createTaskDefinition(
                projectCode, process.getCode(), dinkyTaskRequest.getUpstreamCodes(), taskDefinitionJsonObj);
        // update the location of process
        updateProcessDefinition(process, taskCode, taskRequest, dinkyTaskRequest.getUpstreamCodes(), projectCode);

        log.info(Status.DS_ADD_TASK_DEFINITION_SUCCESS.getMessage());
        return true;
    }

    /**
     * Prepare the DS SHELL task params for a DataX task: generate the wrapper script,
     * upload the json + script to the DS resource center, and build the resource list.
     */
    private ShellTaskParams prepareDataXShellParams(Catalogue catalogue, Task task) {
        String taskName = catalogue.getName();
        String dataxJson = task.getStatement();
        if (dataxJson == null || dataxJson.trim().isEmpty()) {
            log.error("DataX task statement (datax json) is empty: {}", taskName);
            throw new BusException("DataX task statement (datax json) is empty");
        }

        String jsonFileName = taskName + ".json";
        String pyFileName = "run_" + taskName + ".py";

        // 从 json 提取参数名（按出现顺序去重），从 taskParams 匹配参数值
        List<String> paramNames = extractParamNames(dataxJson);
        Map<String, String> paramValues = buildParamValueMap(task);
        // 资源目录路径（远→近，与工作流名一致）
        String dirPath = getDinkyNames(catalogue, 0);

        File jsonFile = null;
        File pyFile = null;
        try {
            jsonFile = writeTempFile(jsonFileName, dataxJson);
            pyFile = writeTempFile(pyFileName, buildDataXRunScript(taskName, jsonFileName, paramNames));

            Integer jsonResourceId = uploadDataXResource(dirPath, jsonFileName, jsonFile);
            Integer pyResourceId = uploadDataXResource(dirPath, pyFileName, pyFile);

            ShellTaskParams params = new ShellTaskParams();
            params.setRawScript(buildRawScript(dirPath, pyFileName, paramNames));
            params.setLocalParams(buildDataXLocalParams(paramNames, paramValues));
            params.setResourceList(Arrays.asList(
                    buildResourceInfo(jsonResourceId, dirPath, jsonFileName),
                    buildResourceInfo(pyResourceId, dirPath, pyFileName)));
            return params;
        } finally {
            if (jsonFile != null && jsonFile.exists()) {
                jsonFile.delete();
            }
            if (pyFile != null && pyFile.exists()) {
                pyFile.delete();
            }
        }
    }

    /**
     * Build the DataX wrapper python script (relative-path edition).
     */
    private String buildDataXRunScript(String taskName, String jsonFileName, List<String> paramNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env python3\n");
        sb.append("\"\"\"DataX runtime wrapper generated by Dinky (task: ")
                .append(taskName)
                .append(")\"\"\"\n");
        sb.append("import os, sys, json, subprocess, time\n\n");
        sb.append("CONFIG_DIR = os.path.dirname(os.path.abspath(__file__))\n");
        sb.append("JSON_FILE = \"").append(jsonFileName).append("\"\n");
        sb.append("PARAM_NAMES = ")
                .append(paramNames.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", ", "[", "]")))
                .append("\n\n");
        sb.append("def main():\n");
        sb.append("    if len(sys.argv) < len(PARAM_NAMES) + 1:\n");
        sb.append("        print('Error: missing params, expect ' + str(len(PARAM_NAMES)), file=sys.stderr)\n");
        sb.append("        sys.exit(1)\n");
        sb.append("    params = dict(zip(PARAM_NAMES, sys.argv[1:]))\n");
        sb.append("    template_file = os.path.join(CONFIG_DIR, JSON_FILE)\n");
        sb.append("    with open(template_file, 'r') as f:\n");
        sb.append("        text = f.read()\n");
        sb.append("    for name, value in params.items():\n");
        sb.append("        text = text.replace('${' + name + '}', value)\n");
        sb.append("    config = json.loads(text)\n");
        sb.append("    content = config['job']['content'][0]\n");
        sb.append("    writer_params = content['writer']['parameter']\n");
        sb.append("    hdfs_path = writer_params['path']\n");
        sb.append("    table_dir = hdfs_path.rsplit('/pt=', 1)[0]\n");
        sb.append("    hadoop_home = os.environ.get('HADOOP_HOME', '/opt/bigdata/hadoop/latest')\n");
        sb.append("    hdfs = os.path.join(hadoop_home, 'bin', 'hdfs')\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-mkdir', '-p', table_dir], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-chown', 'hadoop-user:hadoop-user', table_dir], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-mkdir', '-p', hdfs_path], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-chown', 'hadoop-user:hadoop-user', hdfs_path], check=True)\n");
        sb.append("    temp_file = '/tmp/datax_' + str(int(time.time())) + '.json'\n");
        sb.append("    with open(temp_file, 'w') as f:\n");
        sb.append("        json.dump(config, f, indent=2)\n");
        sb.append("    datax_home = os.environ.get('DATAX_HOME', '/opt/bigdata/dolphinscheduler/datax')\n");
        sb.append("    datax_py = os.path.join(datax_home, 'bin', 'datax.py')\n");
        sb.append("    result = subprocess.run(['python3', datax_py, temp_file])\n");
        sb.append("    sys.exit(result.returncode)\n\n");
        sb.append("if __name__ == '__main__':\n");
        sb.append("    main()\n");
        return sb.toString();
    }

    /**
     * Write the given content to a temporary file (used only for uploading to DS).
     */
    private File writeTempFile(String fileName, String content) {
        try {
            // 直接以目标文件名创建临时文件，不能用 File.createTempFile：
            // 后者会生成「dinky-datax-<随机数>-<fileName>」这种名字，hutool 上传时 multipart 的
            // filename 取 file.getName()，导致 DS 的 t_ds_resources.file_name (varchar 64) 超长，
            // 触发 "Data too long"，并被 DS 的 catch(Exception) 误包装成 "resource already exists"。
            File file = new File(System.getProperty("java.io.tmpdir"), fileName);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
                writer.flush();
            }
            return file;
        } catch (IOException e) {
            log.error("Failed to write DataX temp file {}", fileName, e);
            throw new SchedulerException("Failed to write DataX temp file " + fileName);
        }
    }

    /**
     * Build a DS {@code resourceList} entry for an uploaded resource.
     */
    private ShellTaskParams.ResourceInfo buildResourceInfo(Integer id, String dirPath, String fileName) {
        ShellTaskParams.ResourceInfo info = new ShellTaskParams.ResourceInfo();
        info.setId(id);
        String fullName = dirPath == null || dirPath.isEmpty() ? "/" + fileName : "/" + dirPath + "/" + fileName;
        info.setResourceName(fullName);
        info.setRes(fileName);
        return info;
    }

    /**
     * Extract {@code ${param}} placeholder names from the datax json (dedup, keep order).
     */
    private List<String> extractParamNames(String json) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\$\\{(\\w+)\\}").matcher(json);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Build a prop→value map from the task's {@code config_json.taskParams}.
     */
    private Map<String, String> buildParamValueMap(Task task) {
        Map<String, String> map = new HashMap<>();
        if (task.getConfigJson() == null || task.getConfigJson().getTaskParams() == null) {
            return map;
        }
        for (TaskExtConfig.TaskParam p : task.getConfigJson().getTaskParams()) {
            map.put(p.getProp(), p.getValue());
        }
        return map;
    }

    /**
     * Build DS localParams from the extracted param names and user-configured values.
     */
    private List<Property> buildDataXLocalParams(List<String> paramNames, Map<String, String> paramValues) {
        List<Property> localParams = new ArrayList<>();
        for (String name : paramNames) {
            Property property = new Property();
            property.setProp(name);
            property.setValue(paramValues.getOrDefault(name, ""));
            property.setDirect(org.dinky.scheduler.enums.Direct.IN);
            property.setType(org.dinky.scheduler.enums.DataType.VARCHAR);
            localParams.add(property);
        }
        return localParams;
    }

    /**
     * Build the rawScript: {@code python3 run_<task>.py ${param1} ${param2} ...}.
     */
    private String buildRawScript(String dirPath, String pyFileName, List<String> paramNames) {
        String pyPath = dirPath == null || dirPath.isEmpty() ? pyFileName : dirPath + "/" + pyFileName;
        StringBuilder sb = new StringBuilder("python3 ").append(pyPath);
        for (String name : paramNames) {
            sb.append(" ${").append(name).append("}");
        }
        return sb.toString();
    }

    /**
     * Ensure the directory path exists (idempotent), then upload the file into that directory.
     *
     * <p>覆盖策略：文件已存在时走 DS updateResource（原地覆盖 HDFS + 元数据），而非「删除再上传」，
     * 因为后者在资源被已发布流程定义引用时会被 DS 以 {@code RESOURCE_IS_USED} 拒绝。</p>
     */
    private Integer uploadDataXResource(String dirPath, String fileName, File file) {
        int pid = ensureDirectoryPath(dirPath);
        String currentDir = dirPath == null || dirPath.isEmpty() ? "/" : "/" + dirPath;
        String fullName = "/".equals(currentDir) ? "/" + fileName : currentDir + "/" + fileName;
        Integer existingId = resourceClient.queryResourceId(fullName);
        if (existingId != null) {
            // 文件已存在 → 原地覆盖（避免 delete 时被「资源被引用」拒绝）
            return resourceClient.updateFile(existingId, fileName, file, currentDir);
        }
        return resourceClient.uploadFile(fileName, file, pid, currentDir);
    }

    /**
     * Ensure the directory path exists (create level by level, idempotent), return the leaf directory id.
     */
    private int ensureDirectoryPath(String dirPath) {
        int pid = -1;
        if (dirPath == null || dirPath.isEmpty()) {
            return pid;
        }
        String currentDir = "/";
        for (String part : dirPath.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            String fullName = "/".equals(currentDir) ? "/" + part : currentDir + "/" + part;
            Integer existingId = resourceClient.queryResourceId(fullName);
            if (existingId != null) {
                pid = existingId;
            } else {
                pid = resourceClient.createDirectory(part, pid, currentDir);
            }
            currentDir = fullName;
        }
        return pid;
    }

    private void updateProcessDefinition(
            ProcessDefinition process, Long taskCode, TaskRequest task, List<String> upstreamCodes, long projectCode) {

        DagData dagData = processClient.getProcessDefinitionInfo(projectCode, process.getCode());
        if (dagData == null) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST.getMessage());
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST);
        }
        List<ProcessTaskRelation> processTaskRelationList = dagData.getProcessTaskRelationList();
        List<TaskDefinition> taskDefinitionList = dagData.getTaskDefinitionList();
        List<DagNodeLocation> locations =
                new ArrayList<>(JsonUtils.toList(process.getLocations(), DagNodeLocation.class));

        if (CollUtil.isNotEmpty(locations)) {
            boolean matched = locations.stream().anyMatch(location -> location.getTaskCode() == taskCode);
            // 获取最大的 x y 坐标
            long xMax =
                    locations.stream().mapToLong(DagNodeLocation::getX).max().getAsLong();

            long yMax =
                    locations.stream().mapToLong(DagNodeLocation::getY).max().getAsLong();

            // if not matched, add a new location
            if (matched) {
                // 随机出一个 x y 坐标
                DagNodeLocation dagNodeLocation = new DagNodeLocation();
                dagNodeLocation.setTaskCode(taskCode);
                dagNodeLocation.setX(RandomUtil.randomLong(xMax - 200, xMax));
                dagNodeLocation.setY(RandomUtil.randomLong(yMax - 150, yMax));
                locations.add(dagNodeLocation);
            }
        } else {
            // 随机出一个 x y 坐标
            DagNodeLocation dagNodeLocation = new DagNodeLocation();
            dagNodeLocation.setTaskCode(taskCode);
            dagNodeLocation.setX(RandomUtil.randomLong(200, 800));
            dagNodeLocation.setY(RandomUtil.randomLong(100, 600));
            locations.add(dagNodeLocation);
        }

        JSONArray taskArray = new JSONArray();
        taskDefinitionList.removeIf(taskDefinition -> (task.getName()).equalsIgnoreCase(taskDefinition.getName()));

        taskArray.addAll(taskDefinitionList);
        taskArray.add(task);

        // 重建当前任务（taskCode）的上游关系：每个上游生成一条关系，支持多上游。
        // 否则 updateTaskWithUpstream / save-single 刚设置的多上游会被这里的旧关系覆盖，
        // 导致多选的前置任务全部变成第一个。
        List<ProcessTaskRelation> rebuiltRelations = new ArrayList<>();
        for (ProcessTaskRelation relation : processTaskRelationList) {
            if (relation.getPostTaskCode() != taskCode) {
                rebuiltRelations.add(relation);
            }
        }
        if (CollUtil.isNotEmpty(upstreamCodes)) {
            for (String upstreamCode : upstreamCodes) {
                long upstreamCodeVal = Long.parseLong(upstreamCode);
                ProcessTaskRelation newRelation = ProcessTaskRelation.generateProcessTaskRelation(taskCode);
                newRelation.setPreTaskCode(upstreamCodeVal);
                taskDefinitionList.stream()
                        .filter(td -> td.getCode() == upstreamCodeVal)
                        .findFirst()
                        .ifPresent(td -> newRelation.setPreTaskVersion(td.getVersion()));
                rebuiltRelations.add(newRelation);
            }
        } else {
            rebuiltRelations.add(ProcessTaskRelation.generateProcessTaskRelation(taskCode));
        }
        processTaskRelationList = rebuiltRelations;

        String processTaskRelationListJson = JsonUtils.toJsonString(processTaskRelationList);

        processClient.createOrUpdateProcessDefinition(
                projectCode,
                process.getCode(),
                process.getName(),
                taskCode,
                processTaskRelationListJson,
                taskArray.toString(),
                locations,
                true);
        log.info(Status.DS_PROCESS_DEFINITION_UPDATE.getMessage(), process.getName(), taskCode, taskArray, locations);
    }

    /**
     * Pushes an update task to the API.
     *
     * @param  projectCode           the project code
     * @param  processCode           the process code
     * @param  taskCode              the task code
     * @param  dinkyTaskRequest      the DinkyTaskRequest object containing task details
     * @return                       true if the task is successfully updated, false otherwise
     */
    @Override
    public boolean pushUpdateTask(
            long projectCode, long processCode, long taskCode, DinkyTaskRequest dinkyTaskRequest) {
        TaskDefinition taskDefinition = taskClient.getTaskDefinition(projectCode, taskCode);
        if (taskDefinition == null) {
            log.error(Status.DS_TASK_NOT_EXIST.getMessage());
            throw new BusException(Status.DS_TASK_NOT_EXIST);
        }

        // DataX tasks are pushed as DS SHELL tasks: re-prepare and update as SHELL.
        Task task = taskMapper.selectById(Integer.valueOf(dinkyTaskRequest.getTaskId()));
        if (task != null && Dialect.DATAX.name().equalsIgnoreCase(task.getDialect())) {
            return pushUpdateDataXTask(projectCode, processCode, taskCode, taskDefinition, task, dinkyTaskRequest);
        }

        if (!TASK_TYPE.equals(taskDefinition.getTaskType())) {
            log.error(Status.DS_TASK_TYPE_NOT_SUPPORT.getMessage(), taskDefinition.getTaskType());
            throw new BusException(Status.DS_TASK_TYPE_NOT_SUPPORT, taskDefinition.getTaskType());
        }

        DagData dagData = processClient.getProcessDefinitionInfo(projectCode, processCode);
        if (dagData == null) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST.getMessage());
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST);
        }

        ProcessDefinition process = dagData.getProcessDefinition();
        if (process == null) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST.getMessage());
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST);
        }

        if (process.getReleaseState() == ReleaseState.ONLINE) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_ONLINE.getMessage(), process.getName());
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_ONLINE, process.getName());
        }
        TaskRequest taskRequest = new TaskRequest();

        dinkyTaskRequest.setName(taskDefinition.getName());
        dinkyTaskRequest.setTaskType(TASK_TYPE);

        // Merge localParams from config_json.taskParams into existing DS task params
        DinkyTaskParams existingParams = JsonUtils.toBean(taskDefinition.getTaskParams(), DinkyTaskParams.class);
        injectTaskLocalParams(dinkyTaskRequest.getTaskId(), existingParams);
        dinkyTaskRequest.setTaskParams(JsonUtils.toJsonString(existingParams));
        BeanUtil.copyProperties(dinkyTaskRequest, taskRequest);
        // 回填既有任务的 taskCode，避免 updateProcessDefinition 时任务关系与任务定义 code 对不上
        taskRequest.setCode(taskCode);
        taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
        taskRequest.setFlag(dinkyTaskRequest.getFlag());
        taskRequest.setIsCache(dinkyTaskRequest.getIsCache());

        String taskDefinitionJsonObj = JsonUtils.toJsonString(taskRequest);
        Long updatedTaskDefinition = taskClient.updateTaskDefinition(
                projectCode, taskCode, dinkyTaskRequest.getUpstreamCodes(), taskDefinitionJsonObj);

        updateProcessDefinition(process, taskCode, taskRequest, dinkyTaskRequest.getUpstreamCodes(), projectCode);
        if (updatedTaskDefinition != null && updatedTaskDefinition > 0) {
            log.info(Status.MODIFY_SUCCESS.getMessage());
            return true;
        }
        log.error(Status.MODIFY_FAILED.getMessage());
        return false;
    }

    /**
     * Update a DataX task in DolphinScheduler (re-prepare SHELL params and re-upload resources).
     */
    private boolean pushUpdateDataXTask(
            long projectCode,
            long processCode,
            long taskCode,
            TaskDefinition taskDefinition,
            Task task,
            DinkyTaskRequest dinkyTaskRequest) {
        if (!SHELL_TASK_TYPE.equals(taskDefinition.getTaskType())) {
            log.error(Status.DS_TASK_TYPE_NOT_SUPPORT.getMessage(), taskDefinition.getTaskType());
            throw new BusException(Status.DS_TASK_TYPE_NOT_SUPPORT, taskDefinition.getTaskType());
        }

        Catalogue catalogue = catalogueService.getOne(
                new LambdaQueryWrapper<Catalogue>().eq(Catalogue::getTaskId, dinkyTaskRequest.getTaskId()));
        if (catalogue == null) {
            log.error(Status.DS_GET_NODE_LIST_ERROR.getMessage());
            throw new BusException(Status.DS_GET_NODE_LIST_ERROR);
        }

        DagData dagData = processClient.getProcessDefinitionInfo(projectCode, processCode);
        if (dagData == null) {
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST);
        }
        ProcessDefinition process = dagData.getProcessDefinition();
        if (process == null) {
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_NOT_EXIST);
        }
        if (process.getReleaseState() == ReleaseState.ONLINE) {
            log.error(Status.DS_WORK_FLOW_DEFINITION_ONLINE.getMessage(), process.getName());
            throw new BusException(Status.DS_WORK_FLOW_DEFINITION_ONLINE, process.getName());
        }

        ShellTaskParams shellTaskParams = prepareDataXShellParams(catalogue, task);

        TaskRequest taskRequest = new TaskRequest();
        dinkyTaskRequest.setName(taskDefinition.getName());
        dinkyTaskRequest.setTaskType(SHELL_TASK_TYPE);
        dinkyTaskRequest.setTaskParams(JsonUtils.toJsonString(shellTaskParams));
        BeanUtil.copyProperties(dinkyTaskRequest, taskRequest);
        // 关键：把既有任务的 taskCode 回填到任务定义，否则 updateProcessDefinition 时
        // taskRelationJson 里的 postTaskCode 与 taskDefinitionJson 里的 code 对不上，
        // DS 会报 "task definition [xxx] does not exist"。
        taskRequest.setCode(taskCode);
        taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
        taskRequest.setFlag(dinkyTaskRequest.getFlag());
        taskRequest.setIsCache(dinkyTaskRequest.getIsCache());

        String taskDefinitionJsonObj = JsonUtils.toJsonString(taskRequest);
        Long updatedTaskDefinition = taskClient.updateTaskDefinition(
                projectCode, taskCode, dinkyTaskRequest.getUpstreamCodes(), taskDefinitionJsonObj);
        updateProcessDefinition(process, taskCode, taskRequest, dinkyTaskRequest.getUpstreamCodes(), projectCode);
        if (updatedTaskDefinition != null && updatedTaskDefinition > 0) {
            log.info(Status.MODIFY_SUCCESS.getMessage());
            return true;
        }
        log.error(Status.MODIFY_FAILED.getMessage());
        return false;
    }

    /**
     * Retrieves the list of TaskMainInfo objects for a given dinkyTaskId.
     *
     * @param  dinkyTaskId   the id of the dinky task
     * @return               the list of TaskMainInfo objects
     */
    @Override
    public List<TaskMainInfo> getTaskMainInfos(long dinkyTaskId) {
        Catalogue catalogue =
                catalogueService.getOne(new LambdaQueryWrapper<Catalogue>().eq(Catalogue::getTaskId, dinkyTaskId));
        if (catalogue == null) {
            log.error(Status.DS_GET_NODE_LIST_ERROR.getMessage());
            throw new BusException(Status.DS_GET_NODE_LIST_ERROR);
        }
        long projectCode = SystemInit.getProject().getCode();
        List<TaskMainInfo> taskMainInfos = taskClient.getTaskMainInfos(projectCode, "", "", "");
        // 去掉本身
        taskMainInfos.removeIf(taskMainInfo -> (catalogue.getName()).equalsIgnoreCase(taskMainInfo.getTaskName()));
        return taskMainInfos;
    }

    /**
     * Retrieves the task definition information for a given dinkyTaskId.
     *
     * @param  dinkyTaskId   the ID of the dinky task
     * @return               the task definition information
     */
    @Override
    public TaskDefinition getTaskDefinitionInfo(long dinkyTaskId) {
        Catalogue catalogue =
                catalogueService.getOne(new LambdaQueryWrapper<Catalogue>().eq(Catalogue::getTaskId, dinkyTaskId));
        if (catalogue == null) {
            log.error(Status.DS_GET_NODE_LIST_ERROR.getMessage());
            throw new BusException(Status.DS_GET_NODE_LIST_ERROR);
        }

        Project dinkyProject = SystemInit.getProject();
        long projectCode = dinkyProject.getCode();

        String processName = getDinkyNames(catalogue, 0);
        String taskName = catalogue.getName();
        TaskMainInfo taskMainInfo = taskClient.getTaskMainInfo(projectCode, processName, taskName, TASK_TYPE);
        TaskDefinition taskDefinition;
        if (taskMainInfo == null) {
            return null;
        }

        taskDefinition = taskClient.getTaskDefinition(projectCode, taskMainInfo.getTaskCode());
        if (taskDefinition == null) {
            return null;
        }

        taskDefinition.setProcessDefinitionCode(taskMainInfo.getProcessDefinitionCode());
        taskDefinition.setProcessDefinitionName(taskMainInfo.getProcessDefinitionName());
        taskDefinition.setProcessDefinitionVersion(taskMainInfo.getProcessDefinitionVersion());
        taskDefinition.setUpstreamTaskMap(taskMainInfo.getUpstreamTaskMap());
        return taskDefinition;
    }

    /**
     * Retrieves the list of task groups from DolphinScheduler.
     *
     * @param  projectCode   the project code
     * @return               the list of task groups
     */
    @Override
    public List<TaskGroup> getTaskGroupsFromDolphinScheduler(long projectCode) {
        return taskClient.getTaskGroupList(projectCode);
    }

    /**
     * Retrieves the dinky names from the given catalogue and index.
     *
     * @param  catalogue    the catalogue object to retrieve the names from
     * @param  i            the index to start retrieving the names from
     * @return              the dinky names retrieved from the catalogue
     */
    private String getDinkyNames(Catalogue catalogue, int i) {
        // 收集从任务向上的父目录名（近→远），反转得到远→近的工作流名
        List<String> names = new ArrayList<>();
        Catalogue current = catalogue;
        int depth = 0;
        while (depth < 3 && current != null && !current.getParentId().equals(0)) {
            current = catalogueService.getById(current.getParentId());
            if (current == null) {
                break;
            }
            names.add(current.getName());
            depth++;
        }
        Collections.reverse(names);
        return String.join("/", names);
    }

    /**
     * Read task parameters from {@code config_json.taskParams} and inject them
     * into the DS DinkyTaskParams {@code localParams} as {@link Property} objects.
     * <p>
     * The original DS time-placeholder expression (e.g. {@code $[yyyyMMdd-1]}) is
     * preserved as-is; resolution happens at DS scheduling time.
     */
    private void injectTaskLocalParams(String taskId, DinkyTaskParams dinkyTaskParams) {
        Task task = taskMapper.selectById(Integer.valueOf(taskId));
        dinkyTaskParams.setLocalParams(buildLocalParams(task));
        if (!dinkyTaskParams.getLocalParams().isEmpty()) {
            log.info(
                    "Injected {} localParams from config_json.taskParams into DinkyTaskParams",
                    dinkyTaskParams.getLocalParams().size());
        }
    }

    /**
     * Build DS localParams ({@link Property}) from {@code config_json.taskParams}.
     */
    private List<Property> buildLocalParams(Task task) {
        if (task == null || task.getConfigJson() == null) {
            return Collections.emptyList();
        }
        TaskExtConfig extConfig = task.getConfigJson();
        if (extConfig.getTaskParams() == null || extConfig.getTaskParams().isEmpty()) {
            return Collections.emptyList();
        }
        List<Property> localParams = new ArrayList<>();
        for (TaskExtConfig.TaskParam p : extConfig.getTaskParams()) {
            Property property = new Property();
            property.setProp(p.getProp());
            property.setDirect(org.dinky.scheduler.enums.Direct.valueOf(p.getDirect()));
            property.setType(org.dinky.scheduler.enums.DataType.valueOf(p.getType()));
            property.setValue(p.getValue());
            localParams.add(property);
        }
        return localParams;
    }
}
