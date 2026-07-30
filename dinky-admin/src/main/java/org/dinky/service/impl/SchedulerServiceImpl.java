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

import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Catalogue;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.SystemConfiguration;
import org.dinky.data.model.Task;
import org.dinky.init.SystemInit;
import org.dinky.scheduler.client.DataSourceClient;
import org.dinky.scheduler.client.ProcessClient;
import org.dinky.scheduler.client.TaskClient;
import org.dinky.scheduler.enums.ReleaseState;
import org.dinky.scheduler.exception.SchedulerException;
import org.dinky.scheduler.model.DagData;
import org.dinky.scheduler.model.DagNodeLocation;
import org.dinky.scheduler.model.DinkyTaskParams;
import org.dinky.scheduler.model.DinkyTaskRequest;
import org.dinky.scheduler.model.DsDataSource;
import org.dinky.scheduler.model.ProcessDefinition;
import org.dinky.scheduler.model.ProcessTaskRelation;
import org.dinky.scheduler.model.Project;
import org.dinky.scheduler.model.TaskDefinition;
import org.dinky.scheduler.model.TaskGroup;
import org.dinky.scheduler.model.TaskMainInfo;
import org.dinky.scheduler.model.TaskRequest;
import org.dinky.service.DataBaseService;
import org.dinky.service.SchedulerService;
import org.dinky.service.TaskService;
import org.dinky.service.catalogue.CatalogueService;
import org.dinky.utils.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final ProcessClient processClient;
    private final TaskClient taskClient;
    private final CatalogueService catalogueService;
    private final TaskService taskService;
    private final DataBaseService dataBaseService;
    private final DataSourceClient dataSourceClient;

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

        DinkyTaskParams dinkyTaskParams = new DinkyTaskParams();
        dinkyTaskParams.setTaskId(dinkyTaskRequest.getTaskId());
        dinkyTaskParams.setAddress(
                SystemConfiguration.getInstances().getDinkyAddr().getValue());
        dinkyTaskRequest.setTaskParams(JsonUtils.toJsonString(dinkyTaskParams));
        dinkyTaskRequest.setTaskType(TASK_TYPE);

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

        TaskMainInfo taskMainInfo = taskClient.getTaskMainInfo(projectCode, processName, taskName, TASK_TYPE);
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
        dinkyTaskRequest.setTaskParams(taskDefinition.getTaskParams());
        dinkyTaskRequest.setTaskType(TASK_TYPE);
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
     * Submit a Hive task to DolphinScheduler as a native SQL (HIVE type) workflow.
     * <p>
     * Workflow:
     * 1. Read task info (name, statement, datasource URL) from dinky_task
     * 2. Map datasource URL from dev to prod (strip _dev suffix)
     * 3. Query DS datasource by the mapped URL to get datasource ID
     * 4. Delete existing same-name workflow in DS (if any)
     * 5. Create new workflow with one HIVE SQL task
     * 6. Release (ONLINE) and start the workflow instance
     *
     * @param taskId Dinky task id
     * @return result map containing processCode, processName, taskName
     */
    @Override
    public Map<String, Object> submitHiveToDS(Long taskId) {
        // 1. Get task info
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BusException(Status.DS_TASK_NOT_EXIST);
        }
        String taskName = task.getName();
        String statement = task.getStatement();
        if (Strings.isNullOrEmpty(statement)) {
            throw new BusException(Status.DS_WORK_FLOW_NOT_SAVE);
        }

        // 2. Get datasource URL from Dinky DataBase
        String devUrl = getDataSourceUrl(task.getDatabaseId());
        if (devUrl == null) {
            throw new BusException(Status.DS_HIVE_SUBMIT_NO_DATASOURCE, "");
        }
        log.info("Dinky datasource URL (dev): {}", devUrl);

        // 3. Map dev URL to prod URL (strip _dev suffix from database name)
        String prodUrl = mapDevToProdUrl(devUrl);
        if (!prodUrl.equals(devUrl)) {
            log.info(Status.DS_HIVE_SUBMIT_URL_MAPPED.getMessage(), devUrl, prodUrl);
        }

        // 4. Query DS datasource by URL
        DsDataSource dsDataSource = dataSourceClient.findDataSourceByUrl(prodUrl);
        if (dsDataSource == null) {
            log.error("No DS datasource found for URL: {}", prodUrl);
            throw new BusException(Status.DS_HIVE_SUBMIT_NO_DATASOURCE, prodUrl);
        }
        log.info("Matched DS datasource: id={}, name={}", dsDataSource.getId(), dsDataSource.getName());

        // 5. Get DS project code
        long projectCode = SystemInit.getProject().getCode();
        String processName = taskName;

        // 6. Delete existing same-name workflow (if any)
        try {
            ProcessDefinition existing = processClient.getProcessDefinitionInfo(projectCode, processName);
            if (existing != null && existing.getCode() != null) {
                log.info("Deleting existing workflow: name={}, code={}", existing.getName(), existing.getCode());
                processClient.deleteProcessDefinition(projectCode, existing.getCode());
            }
        } catch (Exception e) {
            // workflow not found or not deletable — continue
            log.debug("No existing workflow to delete for name={}: {}", processName, e.getMessage());
        }

        // 7. Build task definition
        long taskCode = System.currentTimeMillis();
        JSONObject taskDefJson = buildHiveTaskDefinition(taskCode, taskName, dsDataSource.getId(), statement);
        JSONArray taskDefArray = new JSONArray();
        taskDefArray.add(taskDefJson);

        // 8. Build task relation
        JSONArray taskRelationArray = new JSONArray();
        JSONObject relation = new JSONObject();
        relation.set("name", "");
        relation.set("preTaskCode", 0);
        relation.set("preTaskVersion", 0);
        relation.set("postTaskCode", taskCode);
        relation.set("postTaskVersion", 1);
        relation.set("conditionType", "NONE");
        relation.set("conditionParams", new JSONObject());
        taskRelationArray.add(relation);

        // 9. Get tenant code
        String tenant = "default";

        // 10. Create process definition in DS
        ProcessDefinition processDefinition;
        try {
            DagNodeLocation nodeLoc = new DagNodeLocation();
            nodeLoc.setTaskCode(taskCode);
            nodeLoc.setX(200);
            nodeLoc.setY(200);

            processDefinition = processClient.createOrUpdateProcessDefinition(
                    projectCode,
                    0L,
                    processName,
                    taskCode,
                    taskRelationArray.toString(),
                    taskDefArray.toString(),
                    Collections.singletonList(nodeLoc),
                    false);
        } catch (Exception e) {
            log.error("Failed to create DS workflow: {}", e.getMessage(), e);
            throw new BusException(Status.DS_HIVE_SUBMIT_FAILED, e.getMessage());
        }

        log.info("DS workflow created: name={}, code={}", processDefinition.getName(), processDefinition.getCode());

        // 11. Release (ONLINE)
        processClient.releaseProcessDefinition(projectCode, processDefinition.getCode());
        log.info("DS workflow released: code={}", processDefinition.getCode());

        // 12. Start instance
        processClient.startProcessInstance(projectCode, processDefinition.getCode());
        log.info("DS workflow started: code={}", processDefinition.getCode());

        // 13. Return result
        Map<String, Object> result = new HashMap<>();
        result.put("processCode", processDefinition.getCode());
        result.put("processName", processName);
        result.put("taskName", taskName);
        return result;
    }

    /**
     * Get JDBC URL from Dinky DataBase's connectConfig.
     */
    private String getDataSourceUrl(Integer databaseId) {
        if (databaseId == null) {
            return null;
        }
        DataBase dataBase = dataBaseService.getById(databaseId);
        if (dataBase == null || dataBase.getConnectConfig() == null) {
            return null;
        }
        Object urlObj = dataBase.getConnectConfig().get("url");
        return urlObj != null ? urlObj.toString() : null;
    }

    /**
     * Map dev URL to prod URL by stripping _dev suffix from database name.
     * e.g. jdbc:hive2://hivespark04:10000/gmall_dw_dev → jdbc:hive2://hivespark04:10000/gmall_dw
     * URLs without _dev suffix are returned unchanged.
     */
    private String mapDevToProdUrl(String url) {
        if (url == null) {
            return null;
        }
        // Find last '/' to identify database segment
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) {
            return url;
        }
        String prefix = url.substring(0, lastSlash + 1);
        String dbName = url.substring(lastSlash + 1);

        // Also handle query params (e.g. ;auth=...)
        String querySuffix = "";
        int queryIdx = dbName.indexOf(';');
        if (queryIdx >= 0) {
            querySuffix = dbName.substring(queryIdx);
            dbName = dbName.substring(0, queryIdx);
        }
        int paramIdx = dbName.indexOf('?');
        if (paramIdx >= 0) {
            querySuffix = dbName.substring(paramIdx) + querySuffix;
            dbName = dbName.substring(0, paramIdx);
        }

        if (dbName.endsWith("_dev")) {
            dbName = dbName.substring(0, dbName.length() - 4);
        }
        return prefix + dbName + querySuffix;
    }

    /**
     * Build task definition JSONObject for a Hive SQL task.
     */
    private JSONObject buildHiveTaskDefinition(long taskCode, String taskName, Long dsDatasourceId, String sql) {
        // Build taskParams JSON
        JSONObject taskParams = new JSONObject();
        taskParams.set("type", "HIVE");
        taskParams.set("datasource", dsDatasourceId);
        taskParams.set("sql", sql);
        taskParams.set("udfList", new JSONArray());
        taskParams.set("sqlType", 1);
        taskParams.set("title", "");
        taskParams.set("receivers", "");
        taskParams.set("receiversCc", "");
        taskParams.set("showType", "TABLE");
        taskParams.set("localParams", new JSONArray());
        taskParams.set("preStatements", new JSONArray());
        taskParams.set("postStatements", new JSONArray());
        taskParams.set("timeout", 0);
        taskParams.set("segmentSeparator", "");
        taskParams.set("displayRows", 10);

        // Build task definition
        JSONObject taskDef = new JSONObject();
        taskDef.set("code", taskCode);
        taskDef.set("name", taskName);
        taskDef.set("taskType", "SQL");
        taskDef.set("taskParams", taskParams.toString());
        taskDef.set("flag", "YES");
        taskDef.set("delayTime", 0);
        taskDef.set("failRetryTimes", 0);
        taskDef.set("failRetryInterval", 1);
        taskDef.set("timeoutFlag", "CLOSE");
        taskDef.set("timeoutNotifyStrategy", "WARN");
        taskDef.set("timeout", 0);
        taskDef.set("resourceList", new JSONArray());
        taskDef.set("description", "");
        taskDef.set("taskPriority", "MEDIUM");
        taskDef.set("workerGroup", "default");
        taskDef.set("environmentCode", -1);
        taskDef.set("cpuQuota", -1);
        taskDef.set("memoryMax", -1);
        taskDef.set("taskGroupId", -1);
        taskDef.set("taskGroupPriority", 0);

        return taskDef;
    }
}
