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

import org.dinky.assertion.Asserts;
import org.dinky.context.EngineContextHolder;
import org.dinky.data.constant.CommonConstant;
import org.dinky.data.constant.DirConstant;
import org.dinky.data.enums.Status;
import org.dinky.data.enums.TaskOwnerAlertStrategyEnum;
import org.dinky.data.enums.TaskOwnerLockStrategyEnum;
import org.dinky.data.properties.OssProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * SystemConfiguration
 *
 * @since 2021/11/18
 */
@Getter
public class SystemConfiguration {

    private static final SystemConfiguration systemConfiguration = new SystemConfiguration();

    public static SystemConfiguration getInstances() {
        return systemConfiguration;
    }

    private final Consumer<Configuration<?>> initMethod = null;

    public static Configuration.OptionBuilder key(Status status) {
        return new Configuration.OptionBuilder(status.getKey());
    }

    private static final List<Configuration<?>> CONFIGURATION_LIST = Arrays.stream(
                    ReflectUtil.getFields(SystemConfiguration.class, f -> f.getType() == Configuration.class))
            .map(f -> (Configuration<?>) ReflectUtil.getFieldValue(systemConfiguration, f))
            .collect(Collectors.toList());

    private final Configuration<Boolean> isFirstSystemIn =
            key(Status.SYS_GLOBAL_IS_FIRST).booleanType().defaultValue(true);

    private final Configuration<Boolean> useRestAPI = key(Status.SYS_FLINK_SETTINGS_USERESTAPI)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_FLINK_SETTINGS_USERESTAPI_NOTE);

    private final Configuration<Integer> jobIdWait = key(Status.SYS_FLINK_SETTINGS_JOBIDWAIT)
            .intType()
            .defaultValue(30)
            .note(Status.SYS_FLINK_SETTINGS_JOBIDWAIT_NOTE);

    private final Configuration<Boolean> useFlinkHistoryServer = key(Status.SYS_FLINK_SETTINGS_USE_FLINK_HISTORY_SERVER)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_FLINK_SETTINGS_USE_FLINK_HISTORY_SERVER_NOTE);
    private final Configuration<Integer> flinkHistoryServerPort =
            key(Status.SYS_FLINK_SETTINGS_FLINK_HISTORY_SERVER_PORT)
                    .intType()
                    .defaultValue(8082)
                    .note(Status.SYS_FLINK_SETTINGS_FLINK_HISTORY_SERVER_PORT_NOTE);
    private final Configuration<Integer> flinkHistoryServerArchiveRefreshInterval =
            key(Status.SYS_FLINK_SETTINGS_FLINK_HISTORY_SERVER_ARCHIVE_REFRESH_INTERVAL)
                    .intType()
                    .defaultValue(5000)
                    .note(Status.SYS_FLINK_SETTINGS_FLINK_HISTORY_SERVER_ARCHIVE_REFRESH_INTERVAL_NOTE);

    private final Configuration<String> mavenSettings = key(Status.SYS_MAVEN_SETTINGS_SETTINGSFILEPATH)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_MAVEN_SETTINGS_SETTINGSFILEPATH_NOTE);

    private final Configuration<String> mavenRepository = key(Status.SYS_MAVEN_SETTINGS_REPOSITORY)
            .stringType()
            .defaultValue("https://maven.aliyun.com/nexus/content/repositories/central")
            .note(Status.SYS_MAVEN_SETTINGS_REPOSITORY_NOTE);

    private final Configuration<String> mavenRepositoryUser = key(Status.SYS_MAVEN_SETTINGS_REPOSITORYUSER)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_MAVEN_SETTINGS_REPOSITORYUSER_NOTE);

    private final Configuration<String> mavenRepositoryPassword = key(Status.SYS_MAVEN_SETTINGS_REPOSITORYPASSWORD)
            .stringType()
            .defaultValue("")
            .desensitizedHandler(DesensitizedUtil::password)
            .note(Status.SYS_MAVEN_SETTINGS_REPOSITORYPASSWORD_NOTE);

    private final Configuration<String> pythonHome = key(Status.SYS_ENV_SETTINGS_PYTHONHOME)
            .stringType()
            .defaultValue("python3")
            .note(Status.SYS_ENV_SETTINGS_PYTHONHOME_NOTE);
    private final Configuration<String> dinkyAddr = key(Status.SYS_ENV_SETTINGS_DINKYADDR)
            .stringType()
            .defaultValue(System.getProperty("dinkyAddr"))
            .note(Status.SYS_ENV_SETTINGS_DINKYADDR_NOTE);
    private final Configuration<String> dinkyToken = key(Status.SYS_ENV_SETTINGS_DINKYTOKEN)
            .stringType()
            .defaultValue("efda1551-7958-4e0f-80a8-dfd107df3e38")
            .note(Status.SYS_ENV_SETTINGS_DINKYTOKEN_NOTE);

    private final Configuration<Integer> jobReSendDiffSecond = key(Status.SYS_ENV_SETTINGS_JOB_RESEND_DIFF_SECOND)
            .intType()
            .defaultValue(60)
            .note(Status.SYS_ENV_SETTINGS_JOB_RESEND_DIFF_SECOND_NOTE);

    private final Configuration<Integer> diffMinuteMaxSendCount =
            key(Status.SYS_ENV_SETTINGS_DIFF_MINUTE_MAX_SEND_COUNT)
                    .intType()
                    .defaultValue(2)
                    .note(Status.SYS_ENV_SETTINGS_DIFF_MINUTE_MAX_SEND_COUNT_NOTE);

    private final Configuration<Integer> jobMaxRetainCount = key(Status.SYS_ENV_SETTINGS_MAX_RETAIN_COUNT)
            .intType()
            .defaultValue(10)
            .note(Status.SYS_ENV_SETTINGS_MAX_RETAIN_COUNT_NOTE);

    private final Configuration<Integer> jobMaxRetainDays = key(Status.SYS_ENV_SETTINGS_MAX_RETAIN_DAYS)
            .intType()
            .defaultValue(30)
            .note(Status.SYS_ENV_SETTINGS_MAX_RETAIN_DAYS_NOTE);

    // the default value is the same as the default value of the expressionVariable
    private final Configuration<String> expressionVariable = key(Status.SYS_ENV_SETTINGS_EXPRESSION_VARIABLE)
            .stringType()
            .defaultValue(CommonConstant.DEFAULT_EXPRESSION_VARIABLES)
            .note(Status.SYS_ENV_SETTINGS_EXPRESSION_VARIABLE_NOTE);

    private final Configuration<TaskOwnerLockStrategyEnum> taskOwnerLockStrategy =
            key(Status.SYS_ENV_SETTINGS_TASK_OWNER_LOCK_STRATEGY)
                    .enumType(TaskOwnerLockStrategyEnum.class)
                    .defaultValue(TaskOwnerLockStrategyEnum.ALL)
                    .note(Status.SYS_ENV_SETTINGS_TASK_OWNER_LOCK_STRATEGY_NOTE);

    private final Configuration<TaskOwnerAlertStrategyEnum> taskOwnerAlertStrategy =
            key(Status.SYS_ENV_SETTINGS_TASK_OWNER_ALERT_STRATEGY)
                    .enumType(TaskOwnerAlertStrategyEnum.class)
                    .defaultValue(TaskOwnerAlertStrategyEnum.NONE)
                    .note(Status.SYS_ENV_SETTINGS_TASK_OWNER_ALERT_STRATEGY_NOTE);

    private final Configuration<Boolean> dolphinschedulerEnable = key(Status.SYS_DOLPHINSCHEDULER_SETTINGS_ENABLE)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_DOLPHINSCHEDULER_SETTINGS_ENABLE_NOTE);

    private final Configuration<String> dolphinschedulerUrl = key(Status.SYS_DOLPHINSCHEDULER_SETTINGS_URL)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_DOLPHINSCHEDULER_SETTINGS_URL_NOTE);
    private final Configuration<String> dolphinschedulerToken = key(Status.SYS_DOLPHINSCHEDULER_SETTINGS_TOKEN)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_DOLPHINSCHEDULER_SETTINGS_TOKEN_NOTE);
    private final Configuration<String> dolphinschedulerProjectName =
            key(Status.SYS_DOLPHINSCHEDULER_SETTINGS_PROJECTNAME)
                    .stringType()
                    .defaultValue("Dinky")
                    .note(Status.SYS_DOLPHINSCHEDULER_SETTINGS_PROJECTNAME_NOTE);
    private final Configuration<String> dolphinschedulerTenantCode =
            key(Status.SYS_DOLPHINSCHEDULER_SETTINGS_TENANT_CODE)
                    .stringType()
                    .defaultValue("default")
                    .note(Status.SYS_DOLPHINSCHEDULER_SETTINGS_TENANT_CODE_NOTE);

    private final Configuration<String> ldapUrl =
            key(Status.SYS_LDAP_SETTINGS_URL).stringType().defaultValue("").note(Status.SYS_LDAP_SETTINGS_URL_NOTE);

    private final Configuration<String> ldapUserDn = key(Status.SYS_LDAP_SETTINGS_USERDN)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_LDAP_SETTINGS_USERDN_NOTE);

    private final Configuration<String> ldapUserPassword = key(Status.SYS_LDAP_SETTINGS_USERPASSWORD)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_LDAP_SETTINGS_USERPASSWORD_NOTE);

    private final Configuration<Integer> ldapTimeLimit = key(Status.SYS_LDAP_SETTINGS_TIMELIMIT)
            .intType()
            .defaultValue(30)
            .note(Status.SYS_LDAP_SETTINGS_TIMELIMIT_NOTE);

    private final Configuration<String> ldapBaseDn = key(Status.SYS_LDAP_SETTINGS_BASEDN)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_LDAP_SETTINGS_BASEDN_NOTE);

    private final Configuration<String> ldapCastUsername = key(Status.SYS_LDAP_SETTINGS_CASTUSERNAME)
            .stringType()
            .defaultValue("cn")
            .note(Status.SYS_LDAP_SETTINGS_CASTUSERNAME_NOTE);

    private final Configuration<String> ldapCastNickname = key(Status.SYS_LDAP_SETTINGS_CASTNICKNAME)
            .stringType()
            .defaultValue("sn")
            .note(Status.SYS_LDAP_SETTINGS_CASTNICKNAME_NOTE);

    private final Configuration<String> ldapFilter = key(Status.SYS_LDAP_SETTINGS_FILTER)
            .stringType()
            .defaultValue("(&(objectClass=inetOrgPerson))")
            .note(Status.SYS_LDAP_SETTINGS_FILTER_NOTE);

    private final Configuration<Boolean> ldapAutoload = key(Status.SYS_LDAP_SETTINGS_AUTOLOAD)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LDAP_SETTINGS_AUTOLOAD_NOTE);

    private final Configuration<String> ldapDefaultTeant = key(Status.SYS_LDAP_SETTINGS_DEFAULTTEANT)
            .stringType()
            .defaultValue("DefaultTenant")
            .note(Status.SYS_LDAP_SETTINGS_DEFAULTTEANT_NOTE);

    private final Configuration<Boolean> ldapEnable = key(Status.SYS_LDAP_SETTINGS_ENABLE)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LDAP_SETTINGS_ENABLE_NOTE);

    // ==================== AI / LLM ====================

    private final Configuration<Boolean> llmEnable = key(Status.SYS_LLM_SETTINGS_ENABLE)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LLM_SETTINGS_ENABLE_NOTE);

    private final Configuration<String> llmBaseUrl = key(Status.SYS_LLM_SETTINGS_BASEURL)
            .stringType()
            .defaultValue("https://api.deepseek.com")
            .note(Status.SYS_LLM_SETTINGS_BASEURL_NOTE);

    private final Configuration<String> llmCompletionsPath = key(Status.SYS_LLM_SETTINGS_COMPLETIONSPATH)
            .stringType()
            .defaultValue("/chat/completions")
            .note(Status.SYS_LLM_SETTINGS_COMPLETIONSPATH_NOTE);

    /** API Key：对外返回时必须脱敏（/api/sysConfig/getAll 是 @SaIgnore 接口） */
    private final Configuration<String> llmApiKey = key(Status.SYS_LLM_SETTINGS_APIKEY)
            .stringType()
            .defaultValue("")
            .desensitizedHandler(DesensitizedUtil::password)
            .note(Status.SYS_LLM_SETTINGS_APIKEY_NOTE);

    private final Configuration<String> llmModel = key(Status.SYS_LLM_SETTINGS_MODEL)
            .stringType()
            .defaultValue("deepseek-v4-flash")
            .note(Status.SYS_LLM_SETTINGS_MODEL_NOTE);

    private final Configuration<Integer> llmTimeout =
            key(Status.SYS_LLM_SETTINGS_TIMEOUT).intType().defaultValue(60).note(Status.SYS_LLM_SETTINGS_TIMEOUT_NOTE);

    private final Configuration<Integer> llmMaxTokens = key(Status.SYS_LLM_SETTINGS_MAXTOKENS)
            .intType()
            .defaultValue(4000)
            .note(Status.SYS_LLM_SETTINGS_MAXTOKENS_NOTE);

    /** 生成 SQL 后是否自动执行校验（阶段 0：正确性闭环） */
    private final Configuration<Boolean> llmSqlVerifyEnable = key(Status.SYS_LLM_SETTINGS_SQLVERIFYENABLE)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LLM_SETTINGS_SQLVERIFYENABLE_NOTE);

    /** 校验失败后的自动修复重试上限（对齐 pgconsole 的 max 2 次） */
    private final Configuration<Integer> llmSqlVerifyMaxRetry = key(Status.SYS_LLM_SETTINGS_SQLVERIFYMAXRETRY)
            .intType()
            .defaultValue(2)
            .note(Status.SYS_LLM_SETTINGS_SQLVERIFYMAXRETRY_NOTE);

    /** 校验执行超时（秒） */
    private final Configuration<Integer> llmSqlExecTimeout = key(Status.SYS_LLM_SETTINGS_SQLEXECTIMEOUT)
            .intType()
            .defaultValue(10)
            .note(Status.SYS_LLM_SETTINGS_SQLEXECTIMEOUT_NOTE);

    /** 是否允许执行 SHOW / DESC / EXPLAIN 等元数据语句 */
    private final Configuration<Boolean> llmSqlExecAllowMetadata = key(Status.SYS_LLM_SETTINGS_SQLEXECALLOWMETADATA)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LLM_SETTINGS_SQLEXECALLOWMETADATA_NOTE);

    /** 是否允许执行 DML（默认禁止） */
    private final Configuration<Boolean> llmSqlExecAllowDml = key(Status.SYS_LLM_SETTINGS_SQLEXECALLOWDML)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LLM_SETTINGS_SQLEXECALLOWDML_NOTE);

    /** 是否允许执行 DDL（默认禁止） */
    private final Configuration<Boolean> llmSqlExecAllowDdl = key(Status.SYS_LLM_SETTINGS_SQLEXECALLOWDDL)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LLM_SETTINGS_SQLEXECALLOWDDL_NOTE);

    /**
     * 表数不超过该阈值时才「全量表 + 全部字段」；超过则改为「关键词召回相关表 + 相关表字段」。
     *
     * <p>默认值刻意取小：中等规模库（几十张表）若只给表名不给字段，模型会因看不到列而无法写 SQL
     * （实测 45 张表的库：字段缺失时 Text-to-SQL 成功率不足 50%）。
     */
    private final Configuration<Integer> llmSchemaTableDetailThreshold =
            key(Status.SYS_LLM_SETTINGS_SCHEMATABLEDETAILTHRESHOLD)
                    .intType()
                    .defaultValue(8)
                    .note(Status.SYS_LLM_SETTINGS_SCHEMATABLEDETAILTHRESHOLD_NOTE);

    /** 大库场景下关键词召回的表数量上限 */
    private final Configuration<Integer> llmSchemaRecallTopN = key(Status.SYS_LLM_SETTINGS_SCHEMARECALLTOPN)
            .intType()
            .defaultValue(20)
            .note(Status.SYS_LLM_SETTINGS_SCHEMARECALLTOPN_NOTE);

    /**
     * 阶段 1a 上下文预算（字符数）：schema 区块总上限。
     *
     * <p>原为硬编码常量。现代模型 context window 普遍 128K~1M，24000 属保守值；
     * 调大需结合「准确率 / p95 延迟 / 成本」实测（见阶段 1 计划 §3.4.5），故改为可配置。
     */
    private final Configuration<Integer> llmSchemaMaxChars = key(Status.SYS_LLM_SETTINGS_SCHEMAMAXCHARS)
            .intType()
            .defaultValue(24000)
            .note(Status.SYS_LLM_SETTINGS_SCHEMAMAXCHARS_NOTE);

    /** 阶段 1a 上下文预算（字符数）：逐表累加字段详情的预算上限 */
    private final Configuration<Integer> llmColumnBudgetChars = key(Status.SYS_LLM_SETTINGS_COLUMNBUDGETCHARS)
            .intType()
            .defaultValue(20000)
            .note(Status.SYS_LLM_SETTINGS_COLUMNBUDGETCHARS_NOTE);

    /** 阶段 1a 上下文预算（字符数）：编辑区代码块上限 */
    private final Configuration<Integer> llmEditorSqlMaxChars = key(Status.SYS_LLM_SETTINGS_EDITORSQLMAXCHARS)
            .intType()
            .defaultValue(6000)
            .note(Status.SYS_LLM_SETTINGS_EDITORSQLMAXCHARS_NOTE);

    /** 单用户每分钟请求上限（0 表示不限） */
    private final Configuration<Integer> llmRateLimitPerMinute = key(Status.SYS_LLM_SETTINGS_RATELIMITPERMINUTE)
            .intType()
            .defaultValue(10)
            .note(Status.SYS_LLM_SETTINGS_RATELIMITPERMINUTE_NOTE);

    /** 单用户每日请求上限（0 表示不限） */
    private final Configuration<Integer> llmMaxRequestsPerDay = key(Status.SYS_LLM_SETTINGS_MAXREQUESTSPERDAY)
            .intType()
            .defaultValue(200)
            .note(Status.SYS_LLM_SETTINGS_MAXREQUESTSPERDAY_NOTE);

    /** 单用户每日 token 上限（0 表示不限） */
    private final Configuration<Integer> llmMaxTokensPerDay = key(Status.SYS_LLM_SETTINGS_MAXTOKENSPERDAY)
            .intType()
            .defaultValue(200000)
            .note(Status.SYS_LLM_SETTINGS_MAXTOKENSPERDAY_NOTE);

    /** 是否记录 AI 对话审计日志 */
    private final Configuration<Boolean> llmAuditEnable = key(Status.SYS_LLM_SETTINGS_AUDITENABLE)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LLM_SETTINGS_AUDITENABLE_NOTE);

    /** 是否流式输出（评测脚本可置 false 走一次性返回） */
    private final Configuration<Boolean> llmStream = key(Status.SYS_LLM_SETTINGS_STREAM)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LLM_SETTINGS_STREAM_NOTE);

    // ==================== AI / LLM：阶段 1b 只读工具（Agent Loop） ====================

    /**
     * 工具调用总开关。
     *
     * <p>关闭时{@code AiToolLoop} 不被启用，行为与阶段 1a 完全一致——这是上线后出现意料之外行为时
     * 的第一条退路。
     */
    private final Configuration<Boolean> llmToolCallEnable = key(Status.SYS_LLM_SETTINGS_TOOLCALLENABLE)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_LLM_SETTINGS_TOOLCALLENABLE_NOTE);

    /**
     * 是否开放触碰业务数据行的 sample_rows 工具。
     *
     * <p>默认关闭：元数据红线要求「业务数据入 LLM 必须显式授权」。关闭时该工具<b>不会出现在
     * 下发给模型的 tools 里</b>，而不是下发后再拒绝。
     */
    private final Configuration<Boolean> llmToolSampleRowsEnable = key(Status.SYS_LLM_SETTINGS_TOOLSAMPLEROWSENABLE)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LLM_SETTINGS_TOOLSAMPLEROWSENABLE_NOTE);

    /** 一次对话最多进行多少轮工具调用（一轮 = 一次完整 LLM 请求） */
    private final Configuration<Integer> llmToolCallMaxRounds = key(Status.SYS_LLM_SETTINGS_TOOLCALLMAXROUNDS)
            .intType()
            .defaultValue(3)
            .note(Status.SYS_LLM_SETTINGS_TOOLCALLMAXROUNDS_NOTE);

    /** 单个工具的执行超时（秒）：元数据查询卡住时不能拖垮整个对话 */
    private final Configuration<Integer> llmToolTimeoutSeconds = key(Status.SYS_LLM_SETTINGS_TOOLTIMEOUTSECONDS)
            .intType()
            .defaultValue(10)
            .note(Status.SYS_LLM_SETTINGS_TOOLTIMEOUTSECONDS_NOTE);

    /**
     * 工具轮是否保留 thinking。
     *
     * <p>默认关闭：2026-09-28 探针实测 thinking 默认开启时吃掉约 75% 生成预算
     * （completion 147 vs 38 tokens，延迟 1.34s vs 1.02s），而工具决策轮的输出只是一个结构化 JSON。
     * 若实测发现工具命中率下降，改回 true 即可，无需改代码。
     */
    private final Configuration<Boolean> llmToolThinkingEnabled = key(Status.SYS_LLM_SETTINGS_TOOLTHINKINGENABLED)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_LLM_SETTINGS_TOOLTHINKINGENABLED_NOTE);

    private final Configuration<Boolean> metricsSysEnable = key(Status.SYS_METRICS_SETTINGS_SYS_ENABLE)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_METRICS_SETTINGS_SYS_ENABLE_NOTE);

    private final Configuration<Integer> metricsSysGatherTiming = key(Status.SYS_METRICS_SETTINGS_SYS_GATHERTIMING)
            .intType()
            .defaultValue(3000)
            .note(Status.SYS_METRICS_SETTINGS_SYS_GATHERTIMING_NOTE);
    private final Configuration<Integer> flinkMetricsGatherTiming = key(Status.SYS_METRICS_SETTINGS_FLINK_GATHERTIMING)
            .intType()
            .defaultValue(3000)
            .note(Status.SYS_METRICS_SETTINGS_FLINK_GATHERTIMING_NOTE);

    private final Configuration<Integer> flinkMetricsGatherTimeout =
            key(Status.SYS_METRICS_SETTINGS_FLINK_GATHERTIMEOUT)
                    .intType()
                    .defaultValue(1000)
                    .note(Status.SYS_METRICS_SETTINGS_FLINK_GATHERTIMEOUT_NOTE);

    private final Configuration<Boolean> resourcesEnable = key(Status.SYS_RESOURCE_SETTINGS_ENABLE)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_RESOURCE_SETTINGS_ENABLE_NOTE);

    private final Configuration<Boolean> physicalDeletion = key(Status.SYS_RESOURCE_SETTINGS_PHYSICAL_DELETION)
            .booleanType()
            .defaultValue(false)
            .note(Status.SYS_RESOURCE_SETTINGS_PHYSICAL_DELETION_NOTE);

    private final Configuration<ResourcesModelEnum> resourcesModel = key(Status.SYS_RESOURCE_SETTINGS_MODEL)
            .enumType(ResourcesModelEnum.class)
            .defaultValue(ResourcesModelEnum.LOCAL)
            .note(Status.SYS_RESOURCE_SETTINGS_MODEL_NOTE);

    private final Configuration<String> resourcesUploadBasePath = key(Status.SYS_RESOURCE_SETTINGS_UPLOAD_BASE_PATH)
            .stringType()
            .defaultValue("/dinky")
            .note(Status.SYS_RESOURCE_SETTINGS_UPLOAD_BASE_PATH_NOTE);

    private final Configuration<String> resourcesOssEndpoint = key(Status.SYS_RESOURCE_SETTINGS_OSS_ENDPOINT)
            .stringType()
            .defaultValue("http://localhost:9000")
            .note(Status.SYS_RESOURCE_SETTINGS_OSS_ENDPOINT_NOTE);

    private final Configuration<String> resourcesOssAccessKey = key(Status.SYS_RESOURCE_SETTINGS_OSS_ACCESSKEY)
            .stringType()
            .defaultValue("minioadmin")
            .note(Status.SYS_RESOURCE_SETTINGS_OSS_ACCESSKEY_NOTE);

    private final Configuration<String> resourcesOssSecretKey = key(Status.SYS_RESOURCE_SETTINGS_OSS_SECRETKEY)
            .stringType()
            .defaultValue("minioadmin")
            .note(Status.SYS_RESOURCE_SETTINGS_OSS_SECRETKEY_NOTE);

    private final Configuration<String> resourcesOssBucketName = key(Status.SYS_RESOURCE_SETTINGS_OSS_BUCKETNAME)
            .stringType()
            .defaultValue("dinky")
            .note(Status.SYS_RESOURCE_SETTINGS_OSS_BUCKETNAME_NOTE);
    private final Configuration<String> resourcesOssRegion = key(Status.SYS_RESOURCE_SETTINGS_OSS_REGION)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_RESOURCE_SETTINGS_OSS_REGION_NOTE);
    private final Configuration<String> resourcesHdfsUser = key(Status.SYS_RESOURCE_SETTINGS_HDFS_ROOT_USER)
            .stringType()
            .defaultValue("hdfs")
            .note(Status.SYS_RESOURCE_SETTINGS_HDFS_ROOT_USER_NOTE);
    private final Configuration<String> resourcesHdfsDefaultFS = key(Status.SYS_RESOURCE_SETTINGS_HDFS_FS_DEFAULTFS)
            .stringType()
            .defaultValue("file:///")
            .note(Status.SYS_RESOURCE_SETTINGS_HDFS_FS_DEFAULTFS_NOTE);
    private final Configuration<String> resourcesHdfsCoreSite = key(Status.SYS_RESOURCE_SETTINGS_HDFS_CORE_SITE)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_RESOURCE_SETTINGS_HDFS_CORE_SITE_NOTE);
    private final Configuration<String> resourcesHdfsHdfsSite = key(Status.SYS_RESOURCE_SETTINGS_HDFS_HDFS_SITE)
            .stringType()
            .defaultValue("")
            .note(Status.SYS_RESOURCE_SETTINGS_HDFS_HDFS_SITE_NOTE);
    private final Configuration<Boolean> resourcesPathStyleAccess = key(Status.SYS_RESOURCE_SETTINGS_PATH_STYLE_ACCESS)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_RESOURCE_SETTINGS_PATH_STYLE_ACCESS_NOTE);

    private final Configuration<Boolean> isOwnerReference = key(Status.SYS_ENV_SETTINGS_IS_OWNER_REFERENCE)
            .booleanType()
            .defaultValue(true)
            .note(Status.SYS_ENV_SETTINGS_IS_OWNER_REFERENCE_NOTE);

    /**
     * Initialize after spring bean startup
     */
    public void initAfterBeanStarted() {
        if (StrUtil.isBlank(dinkyAddr.getDefaultValue())) {
            ReflectUtil.setFieldValue(dinkyAddr, "defaultValue", System.getProperty("dinkyAddr"));
        }
    }

    public void setConfiguration(String key, String value) {
        CONFIGURATION_LIST.stream().filter(x -> x.getKey().equals(key)).forEach(item -> {
            if (value == null) {
                item.setValue(item.getDefaultValue());
                item.runParameterCheck();
                item.runChangeEvent();
                return;
            }
            if (!StrUtil.equals(Convert.toStr(item.getValue()), value)) {
                item.setValue(value);
                item.runParameterCheck();
                item.runChangeEvent();
            }
        });
    }

    public void initSetConfiguration(Map<String, String> configMap) {
        CONFIGURATION_LIST.forEach(item -> {
            if (!configMap.containsKey(item.getKey())) {
                return;
            }
            final String value = configMap.get(item.getKey());
            if (value == null) {
                item.setValue(item.getDefaultValue());
                return;
            }
            item.setValue(value);
        });
        CONFIGURATION_LIST.stream().peek(Configuration::runParameterCheck).forEach(Configuration::runChangeEvent);
    }

    public void initExpressionVariableList(Map<String, String> configMap) {
        CONFIGURATION_LIST.forEach(item -> {
            if (item.getKey().equals(expressionVariable.getKey())) {
                EngineContextHolder.loadExpressionVariableClass(configMap.get(item.getKey()));
            }
        });
    }

    public Map<String, List<Configuration<?>>> getAllConfiguration() {
        Map<String, List<Configuration<?>>> data = new TreeMap<>();
        for (Configuration<?> item : CONFIGURATION_LIST) {
            final String key = item.getKey();
            String k = StrUtil.split(key, ".").get(1);
            Opt.ofBlankAble(k).ifPresent(name -> {
                item.setName(Status.findMessageByKey(item.getKey()));
                item.setNote(Status.findMessageByKey(item.getNoteKey()));
                data.computeIfAbsent(k, x -> new ArrayList<>());
                data.get(k).add(item);
            });
        }
        return data;
    }

    public boolean isUseRestAPI() {
        return Asserts.isNull(useRestAPI.getValue()) ? useRestAPI.getDefaultValue() : useRestAPI.getValue();
    }

    // ==================== AI / LLM ====================

    public boolean isLlmEnable() {
        return Asserts.isNull(llmEnable.getValue()) ? llmEnable.getDefaultValue() : llmEnable.getValue();
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl.getValue();
    }

    public String getLlmCompletionsPath() {
        return llmCompletionsPath.getValue();
    }

    /** 仅服务端使用：返回真实 API Key，禁止通过任何接口对外返回 */
    public String getLlmApiKey() {
        return llmApiKey.getValue();
    }

    public String getLlmModel() {
        return llmModel.getValue();
    }

    public int getLlmTimeout() {
        return Asserts.isNull(llmTimeout.getValue()) ? llmTimeout.getDefaultValue() : llmTimeout.getValue();
    }

    public int getLlmMaxTokens() {
        return Asserts.isNull(llmMaxTokens.getValue()) ? llmMaxTokens.getDefaultValue() : llmMaxTokens.getValue();
    }

    public boolean isLlmSqlVerifyEnable() {
        return Asserts.isNull(llmSqlVerifyEnable.getValue())
                ? llmSqlVerifyEnable.getDefaultValue()
                : llmSqlVerifyEnable.getValue();
    }

    public int getLlmSqlVerifyMaxRetry() {
        return Asserts.isNull(llmSqlVerifyMaxRetry.getValue())
                ? llmSqlVerifyMaxRetry.getDefaultValue()
                : llmSqlVerifyMaxRetry.getValue();
    }

    public int getLlmSqlExecTimeout() {
        return Asserts.isNull(llmSqlExecTimeout.getValue())
                ? llmSqlExecTimeout.getDefaultValue()
                : llmSqlExecTimeout.getValue();
    }

    public boolean isLlmSqlExecAllowMetadata() {
        return Asserts.isNull(llmSqlExecAllowMetadata.getValue())
                ? llmSqlExecAllowMetadata.getDefaultValue()
                : llmSqlExecAllowMetadata.getValue();
    }

    public boolean isLlmSqlExecAllowDml() {
        return Asserts.isNull(llmSqlExecAllowDml.getValue())
                ? llmSqlExecAllowDml.getDefaultValue()
                : llmSqlExecAllowDml.getValue();
    }

    public boolean isLlmSqlExecAllowDdl() {
        return Asserts.isNull(llmSqlExecAllowDdl.getValue())
                ? llmSqlExecAllowDdl.getDefaultValue()
                : llmSqlExecAllowDdl.getValue();
    }

    public int getLlmSchemaTableDetailThreshold() {
        return Asserts.isNull(llmSchemaTableDetailThreshold.getValue())
                ? llmSchemaTableDetailThreshold.getDefaultValue()
                : llmSchemaTableDetailThreshold.getValue();
    }

    public int getLlmSchemaRecallTopN() {
        return Asserts.isNull(llmSchemaRecallTopN.getValue())
                ? llmSchemaRecallTopN.getDefaultValue()
                : llmSchemaRecallTopN.getValue();
    }

    /** @return schema 上下文区块的字符总上限（阶段 1a 起可配置） */
    public int getLlmSchemaMaxChars() {
        return Asserts.isNull(llmSchemaMaxChars.getValue())
                ? llmSchemaMaxChars.getDefaultValue()
                : llmSchemaMaxChars.getValue();
    }

    /** @return 逐表累加字段详情的字符预算上限（阶段 1a 起可配置） */
    public int getLlmColumnBudgetChars() {
        return Asserts.isNull(llmColumnBudgetChars.getValue())
                ? llmColumnBudgetChars.getDefaultValue()
                : llmColumnBudgetChars.getValue();
    }

    /** @return 编辑区代码块的字符上限（阶段 1a 起可配置） */
    public int getLlmEditorSqlMaxChars() {
        return Asserts.isNull(llmEditorSqlMaxChars.getValue())
                ? llmEditorSqlMaxChars.getDefaultValue()
                : llmEditorSqlMaxChars.getValue();
    }

    public int getLlmRateLimitPerMinute() {
        return Asserts.isNull(llmRateLimitPerMinute.getValue())
                ? llmRateLimitPerMinute.getDefaultValue()
                : llmRateLimitPerMinute.getValue();
    }

    public int getLlmMaxRequestsPerDay() {
        return Asserts.isNull(llmMaxRequestsPerDay.getValue())
                ? llmMaxRequestsPerDay.getDefaultValue()
                : llmMaxRequestsPerDay.getValue();
    }

    public int getLlmMaxTokensPerDay() {
        return Asserts.isNull(llmMaxTokensPerDay.getValue())
                ? llmMaxTokensPerDay.getDefaultValue()
                : llmMaxTokensPerDay.getValue();
    }

    public boolean isLlmAuditEnable() {
        return Asserts.isNull(llmAuditEnable.getValue()) ? llmAuditEnable.getDefaultValue() : llmAuditEnable.getValue();
    }

    public boolean isLlmStream() {
        return Asserts.isNull(llmStream.getValue()) ? llmStream.getDefaultValue() : llmStream.getValue();
    }

    /** @return 是否允许模型调用只读工具（关闭后行为与阶段 1a 一致） */
    public boolean isLlmToolCallEnable() {
        return Asserts.isNull(llmToolCallEnable.getValue())
                ? llmToolCallEnable.getDefaultValue()
                : llmToolCallEnable.getValue();
    }

    /** @return 是否开放触碰业务数据行的 sample_rows 工具 */
    public boolean isLlmToolSampleRowsEnable() {
        return Asserts.isNull(llmToolSampleRowsEnable.getValue())
                ? llmToolSampleRowsEnable.getDefaultValue()
                : llmToolSampleRowsEnable.getValue();
    }

    /** @return 一次对话最多进行多少轮工具调用 */
    public int getLlmToolCallMaxRounds() {
        return Asserts.isNull(llmToolCallMaxRounds.getValue())
                ? llmToolCallMaxRounds.getDefaultValue()
                : llmToolCallMaxRounds.getValue();
    }

    /** @return 单个工具的执行超时（秒） */
    public int getLlmToolTimeoutSeconds() {
        return Asserts.isNull(llmToolTimeoutSeconds.getValue())
                ? llmToolTimeoutSeconds.getDefaultValue()
                : llmToolTimeoutSeconds.getValue();
    }

    /** @return 工具轮是否保留 thinking（默认关闭，详见字段注释） */
    public boolean isLlmToolThinkingEnabled() {
        return Asserts.isNull(llmToolThinkingEnabled.getValue())
                ? llmToolThinkingEnabled.getDefaultValue()
                : llmToolThinkingEnabled.getValue();
    }

    public int GetJobIdWaitValue() {
        return jobIdWait.getValue();
    }

    public String getMavenSettings() {

        return mavenSettings.getValue();
    }

    public String getMavenRepository() {
        return mavenRepository.getValue();
    }

    public String getMavenRepositoryUser() {
        return mavenRepositoryUser.getValue();
    }

    public String getMavenRepositoryPassword() {
        return mavenRepositoryPassword.getValue();
    }

    public String getPythonHome() {
        return pythonHome.getValue();
    }

    public OssProperties getOssProperties() {
        return OssProperties.builder()
                .enable(true)
                .endpoint(resourcesOssEndpoint.getValue())
                .accessKey(resourcesOssAccessKey.getValue())
                .secretKey(resourcesOssSecretKey.getValue())
                .bucketName(resourcesOssBucketName.getValue())
                .region(resourcesOssRegion.getValue())
                .pathStyleAccess(resourcesPathStyleAccess.getValue())
                .build();
    }

    public TaskOwnerLockStrategyEnum GetTaskOwnerLockStrategyValue() {
        return taskOwnerLockStrategy.getValue();
    }

    public static final String FLINK_JOB_ARCHIVE = "rs:/tmp/flink-job-archive";

    public Map<String, String> getFlinkHistoryServerConfiguration() {
        Map<String, String> config = new HashMap<>();
        if (useFlinkHistoryServer.getValue()) {
            config.put(
                    "historyserver.web.port", flinkHistoryServerPort.getValue().toString());
            config.put(
                    "historyserver.archive.fs.refresh-interval",
                    flinkHistoryServerArchiveRefreshInterval.getValue().toString());
            config.put(
                    "historyserver.web.tmpdir",
                    FileUtil.file(DirConstant.getTempRootDir(), "flink-job-archive")
                            .getAbsolutePath());
            config.put("historyserver.archive.fs.dir", FLINK_JOB_ARCHIVE);
            config.put("historyserver.archive.clean-expired-jobs", "true");
        }
        return config;
    }

    public Boolean isOwnerReference() {
        return isOwnerReference.getValue();
    }
}
