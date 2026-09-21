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
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.base.Strings;

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
        updateProcessDefinition(process, taskCode, taskRequest, projectCode);

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

        File jsonFile = null;
        File pyFile = null;
        try {
            jsonFile = writeTempFile(jsonFileName, dataxJson);
            pyFile = writeTempFile(pyFileName, buildDataXRunScript(taskName, jsonFileName));

            Integer jsonResourceId = resourceClient.uploadFile(jsonFileName, jsonFile);
            Integer pyResourceId = resourceClient.uploadFile(pyFileName, pyFile);

            ShellTaskParams params = new ShellTaskParams();
            params.setRawScript("python3 " + pyFileName + " ${SYNC_DATE}");
            params.setLocalParams(buildLocalParams(task));
            params.setResourceList(Arrays.asList(
                    buildResourceInfo(jsonResourceId, jsonFileName),
                    buildResourceInfo(pyResourceId, pyFileName)));
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
    private String buildDataXRunScript(String taskName, String jsonFileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env python3\n");
        sb.append("\"\"\"DataX runtime wrapper generated by Dinky (task: ").append(taskName).append(")\"\"\"\n");
        sb.append("import os, sys, json, subprocess\n\n");
        sb.append("CONFIG_DIR = os.path.dirname(os.path.abspath(__file__))\n");
        sb.append("JSON_FILE = \"").append(jsonFileName).append("\"\n\n");
        sb.append("def main():\n");
        sb.append("    sync_date = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('SYNC_DATE', '')\n");
        sb.append("    if not sync_date:\n");
        sb.append("        print('Error: SYNC_DATE not provided', file=sys.stderr)\n");
        sb.append("        sys.exit(1)\n");
        sb.append("    template_file = os.path.join(CONFIG_DIR, JSON_FILE)\n");
        sb.append("    with open(template_file, 'r') as f:\n");
        sb.append("        config = json.load(f)\n");
        sb.append("    content = config['job']['content'][0]\n");
        sb.append("    writer_params = content['writer']['parameter']\n");
        sb.append("    hdfs_path = writer_params['path'].replace('${SYNC_DATE}', sync_date)\n");
        sb.append("    writer_params['path'] = hdfs_path\n");
        sb.append("    table_dir = hdfs_path.rsplit('/pt=', 1)[0]\n");
        sb.append("    hadoop_home = os.environ.get('HADOOP_HOME', '/opt/bigdata/hadoop/latest')\n");
        sb.append("    hdfs = os.path.join(hadoop_home, 'bin', 'hdfs')\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-mkdir', '-p', table_dir], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-chown', 'hadoop-user:hadoop-user', table_dir], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-mkdir', '-p', hdfs_path], check=True)\n");
        sb.append("    subprocess.run([hdfs, 'dfs', '-chown', 'hadoop-user:hadoop-user', hdfs_path], check=True)\n");
        sb.append("    temp_file = '/tmp/datax_' + sync_date + '.json'\n");
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
            File file = File.createTempFile("dinky-datax-", "-" + fileName);
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
    private ShellTaskParams.ResourceInfo buildResourceInfo(Integer id, String fileName) {
        ShellTaskParams.ResourceInfo info = new ShellTaskParams.ResourceInfo();
        info.setId(id);
        info.setResourceName("/" + fileName);
        info.setRes(fileName);
        return info;
    }

    private void updateProcessDefinition(ProcessDefinition process, Long taskCode, TaskRequest task, long projectCode) {

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
        taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
        taskRequest.setFlag(dinkyTaskRequest.getFlag());
        taskRequest.setIsCache(dinkyTaskRequest.getIsCache());

        String taskDefinitionJsonObj = JsonUtils.toJsonString(taskRequest);
        Long updatedTaskDefinition = taskClient.updateTaskDefinition(
                projectCode, taskCode, dinkyTaskRequest.getUpstreamCodes(), taskDefinitionJsonObj);

        updateProcessDefinition(process, taskCode, taskRequest, projectCode);
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
            long projectCode, long processCode, long taskCode, TaskDefinition taskDefinition, Task task,
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
        taskRequest.setTimeoutFlag(dinkyTaskRequest.getTimeoutFlag());
        taskRequest.setFlag(dinkyTaskRequest.getFlag());
        taskRequest.setIsCache(dinkyTaskRequest.getIsCache());

        String taskDefinitionJsonObj = JsonUtils.toJsonString(taskRequest);
        Long updatedTaskDefinition = taskClient.updateTaskDefinition(
                projectCode, taskCode, dinkyTaskRequest.getUpstreamCodes(), taskDefinitionJsonObj);
        updateProcessDefinition(process, taskCode, taskRequest, projectCode);
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
        if (i == 3 || catalogue.getParentId().equals(0)) {
            return "";
        }

        catalogue = catalogueService.getById(catalogue.getParentId());
        if (catalogue == null) {
            throw new SchedulerException("Get Node List Error");
        }

        String name = i == 0 ? catalogue.getName() : catalogue.getName();
        String next = getDinkyNames(catalogue, ++i);

        if (Strings.isNullOrEmpty(next)) {
            return name;
        }
        return name + "/" + next;
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
