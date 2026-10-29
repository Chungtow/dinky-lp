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

package org.dinky.data.constant;

/**
 * PermissionConstants
 */
public class PermissionConstants {
    /**
     * super admin role
     */
    public static final String ROLE_SUPER_ADMIN = "super_admin";

    /**
     * registration alert group
     */
    public static final String REGISTRATION_ALERT_GROUP_ADD = "registration:alert:group:add";

    public static final String REGISTRATION_ALERT_GROUP_EDIT = "registration:alert:group:edit";

    public static final String REGISTRATION_ALERT_GROUP_DELETE = "registration:alert:group:delete";

    /**
     * registration alert instance
     */
    public static final String REGISTRATION_ALERT_INSTANCE_ADD = "registration:alert:instance:add";

    public static final String REGISTRATION_ALERT_INSTANCE_EDIT = "registration:alert:instance:edit";

    public static final String REGISTRATION_ALERT_INSTANCE_DELETE = "registration:alert:instance:delete";

    /**
     * registration alert template
     */
    public static final String REGISTRATION_ALERT_TEMPLATE_ADD = "registration:alert:template:add";

    public static final String REGISTRATION_ALERT_TEMPLATE_EDIT = "registration:alert:template:edit";

    public static final String REGISTRATION_ALERT_TEMPLATE_DELETE = "registration:alert:template:delete";

    /**
     * alert 策略
     */
    public static final String REGISTRATION_ALERT_RULE_ADD = "registration:alert:rule:add";

    public static final String REGISTRATION_ALERT_RULE_EDIT = "registration:alert:rule:edit";

    public static final String REGISTRATION_ALERT_RULE_DELETE = "registration:alert:rule:delete";

    /**
     * catalog
     * todo: add catalog permission
     */

    /**
     * cluster config
     */
    public static final String REGISTRATION_CLUSTER_CONFIG_ADD = "registration:cluster:config:add";

    public static final String REGISTRATION_CLUSTER_CONFIG_EDIT = "registration:cluster:config:edit";
    public static final String REGISTRATION_CLUSTER_CONFIG_DELETE = "registration:cluster:config:delete";
    // heartbeats
    public static final String REGISTRATION_CLUSTER_CONFIG_HEARTBEATS = "registration:cluster:config:heartbeat";
    // deploy
    public static final String REGISTRATION_CLUSTER_CONFIG_DEPLOY = "registration:cluster:config:deploy";

    /**
     * cluster instance
     */
    public static final String REGISTRATION_CLUSTER_INSTANCE_ADD = "registration:cluster:instance:add";

    public static final String REGISTRATION_CLUSTER_INSTANCE_EDIT = "registration:cluster:instance:edit";

    public static final String REGISTRATION_CLUSTER_INSTANCE_DELETE = "registration:cluster:instance:delete";
    // heartbeats
    public static final String REGISTRATION_CLUSTER_INSTANCE_HEARTBEATS = "registration:cluster:instance:heartbeat";
    // recycle
    public static final String REGISTRATION_CLUSTER_INSTANCE_RECYCLE = "registration:cluster:instance:recycle";
    // killCluster
    public static final String REGISTRATION_CLUSTER_INSTANCE_KILL = "registration:cluster:instance:kill";

    /**
     * data source
     */
    public static final String REGISTRATION_DATA_SOURCE_LIST = "registration:datasource:list";

    public static final String REGISTRATION_DATA_SOURCE_ADD = "registration:datasource:list:add";
    public static final String REGISTRATION_DATA_SOURCE_EDIT = "registration:datasource:list:edit";
    public static final String REGISTRATION_DATA_SOURCE_DELETE = "registration:datasource:list:delete";
    public static final String REGISTRATION_DATA_SOURCE_COPY = "registration:datasource:list:copy";
    public static final String REGISTRATION_DATA_SOURCE_CHECK_HEARTBEAT = "registration:datasource:list:heartbeat";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_TREE = "registration:datasource:detail:tree";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_DESC = "registration:datasource:detail:desc";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_QUERY = "registration:datasource:detail:query";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_GENSQL = "registration:datasource:detail:gensql";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_CONSOLE = "registration:datasource:detail:console";
    public static final String REGISTRATION_DATA_SOURCE_DETAIL_REFRESH = "registration:datasource:detail:refresh";

    /**
     * document
     */
    public static final String REGISTRATION_DOCUMENT_ADD = "registration:document:add";

    public static final String REGISTRATION_DOCUMENT_EDIT = "registration:document:edit";
    public static final String REGISTRATION_DOCUMENT_DELETE = "registration:document:delete";

    /**
     * fragment
     */
    public static final String REGISTRATION_FRAGMENT_ADD = "registration:fragment:add";

    public static final String REGISTRATION_FRAGMENT_EDIT = "registration:fragment:edit";
    public static final String REGISTRATION_FRAGMENT_DELETE = "registration:fragment:delete";

    /**
     * git project
     */
    public static final String REGISTRATION_GIT_PROJECT_ADD = "registration:gitproject:add";

    public static final String REGISTRATION_GIT_PROJECT_EDIT = "registration:gitproject:edit";
    public static final String REGISTRATION_GIT_PROJECT_DELETE = "registration:gitproject:delete";
    // build
    public static final String REGISTRATION_GIT_PROJECT_BUILD = "registration:gitproject:build";
    // showlog
    public static final String REGISTRATION_GIT_PROJECT_SHOW_LOG = "registration:gitproject:showLog";

    /**
     * udf
     */
    public static final String REGISTRATION_UDF_TEMPLATE_ADD = "registration:udf:template:add";

    public static final String REGISTRATION_UDF_TEMPLATE_EDIT = "registration:udf:template:edit";
    public static final String REGISTRATION_UDF_TEMPLATE_DELETE = "registration:udf:template:delete";

    /**
     * RESOURCE
     *
     */
    public static final String REGISTRATION_RESOURCE_ADD_FOLDER = "registration:resource:addFolder";

    public static final String REGISTRATION_RESOURCE_RENAME = "registration:resource:rename";
    public static final String REGISTRATION_RESOURCE_DELETE = "registration:resource:delete";
    // upload
    public static final String REGISTRATION_RESOURCE_UPLOAD = "registration:resource:upload";

    /**
     * auth menu
     */
    public static final String AUTH_MENU_ADD_ROOT = "auth:menu:createRoot";

    public static final String AUTH_MENU_ADD_SUB = "auth:menu:addSub";
    public static final String AUTH_MENU_EDIT = "auth:menu:edit";

    public static final String AUTH_MENU_DELETE = "auth:menu:delete";

    /**
     * AUTH ROLE
     */
    public static final String AUTH_ROLE_ADD = "auth:role:add";

    public static final String AUTH_ROLE_EDIT = "auth:role:edit";
    // 将 add 和 edit 合并为一个 集合
    public static final String[] AUTH_ROLE_ADD_EDIT = {AUTH_ROLE_ADD, AUTH_ROLE_EDIT};
    public static final String AUTH_ROLE_DELETE = "auth:role:delete";
    //  分配菜单
    public static final String AUTH_ROLE_ASSIGN_MENU = "auth:role:assignMenu";
    // 查看用户列表
    public static final String AUTH_ROLE_VIEW_USER_LIST = "auth:role:viewUser";

    /**
     * Row Permissions
     */
    public static final String AUTH_ROW_PERMISSIONS_ADD = "auth:rowPermissions:add";

    public static final String AUTH_ROW_PERMISSIONS_EDIT = "auth:rowPermissions:edit";

    public static final String AUTH_ROW_PERMISSIONS_DELETE = "auth:rowPermissions:delete";

    /**
     * token
     */
    public static final String AUTH_TOKEN_ADD = "auth:token:add";

    public static final String AUTH_TOKEN_EDIT = "auth:token:edit";

    public static final String AUTH_TOKEN_DELETE = "auth:token:delete";

    /**
     * user
     */
    public static final String AUTH_USER_ADD = "auth:user:add";

    public static final String AUTH_USER_EDIT = "auth:user:edit";

    public static final String AUTH_USER_DELETE = "auth:user:delete";
    // 修改密码
    public static final String AUTH_USER_CHANGE_PASSWORD = "auth:user:changePassword";
    // 重置密码
    public static final String AUTH_USER_RESET_PASSWORD = "auth:user:reset";
    // 分配角色
    public static final String AUTH_USER_ASSIGN_ROLE = "auth:user:assignRole";
    // 恢复
    public static final String AUTH_USER_RECOVERY = "auth:user:recovery";

    /**
     * tenant
     */
    public static final String AUTH_TENANT_ADD = "auth:tenant:add";

    public static final String AUTH_TENANT_EDIT = "auth:tenant:edit";

    public static final String AUTH_TENANT_DELETE = "auth:tenant:delete";
    // 分配用户
    public static final String AUTH_TENANT_ASSIGN_USER = "auth:tenant:assignUser";
    // 设置为租户管理员
    public static final String AUTH_TENANT_SET_USER_TO_TENANT_ADMIN = "auth:tenant:modifyTenantManager";
    // 查看用户
    public static final String AUTH_TENANT_VIEW_USER = "auth:tenant:viewUser";

    /**
     * system setting
     */
    public static final String SYSTEM_SETTING_INFO_LOG_LIST = "settings:systemlog:loglist";

    public static final String SYSTEM_SETTING_INFO_ROOT_LOG = "settings:systemlog:rootlog";

    /**
     * dashboard
     */
    public static final String DASHBOARD_LIST_ADD = "dashboard:list:add";

    public static final String DASHBOARD_LIST_EDIT = "dashboard:list:edit";
    public static final String DASHBOARD_LIST_DELETE = "dashboard:list:delete";
    public static final String DASHBOARD_LIST_VIEW = "dashboard:list:view";
    public static final String DASHBOARD_CHART_DELETE = "dashboard:chart:delete";
    public static final String DASHBOARD_CHART_EDIT = "dashboard:chart:edit";
    public static final String DASHBOARD_CHART_ADD = "dashboard:chart:add";

    /**
     * registration skills
     *
     * <p>2026-10-14 菜单与权限登记缺口修复：Skills 能力此前无独立权限点，写接口复用
     * REGISTRATION_RESOURCE_UPLOAD/DELETE。过渡期两个发布周期内以 SaMode.OR 同时接受新旧权限点，
     * 避免"升级即失权"。
     */
    public static final String REGISTRATION_SKILL = "registration:skill";

    public static final String REGISTRATION_SKILL_VIEW = "registration:skill:view";
    public static final String REGISTRATION_SKILL_ADD = "registration:skill:add";
    public static final String REGISTRATION_SKILL_SAVE = "registration:skill:save";
    public static final String REGISTRATION_SKILL_DELETE = "registration:skill:delete";
    public static final String REGISTRATION_SKILL_FILE = "registration:skill:file";

    /**
     * Skills 可见性：把资产设置为「全体公开」所需的权限点（2026-10-14 三档可见性）。
     *
     * <p>「私有 / 同租户共享」沿用 {@link #REGISTRATION_SKILL_SAVE}；只有「面向全体公开」这一组织级
     * 动作单独管控。权限串**必须 ≥ 3 段**，否则连超管 {@code *:*:*} 都匹配不上
     * （见 V20261014.1.4.5 的实测教训）。
     */
    public static final String REGISTRATION_SKILL_SHARE = "registration:skill:share";

    /**
     * 知识库（{@code @kb}）权限点（知识库 @kb 接入 v1.0）。
     *
     * <p>前缀与 Skills 同构（都是「注册中心」下的资产），段数同样**必须 ≥ 3**
     * —— 理由见 {@link #REGISTRATION_SKILL_SHARE}。
     *
     * <ul>
     *   <li>{@code view}：浏览库列表与库内文档（只读打开）；</li>
     *   <li>{@code save}：新建 / 改名 / 改描述 / 启停 / 同租户共享；</li>
     *   <li>{@code share}：设为「全体公开」（组织级动作，单独管控）；</li>
     *   <li>{@code write}：把文本推送入库。<b>独立授权</b>：A 侧采用「合并归属」后，
     *       多归属会扩大文档的可见范围，放任任何人都能往公开库或别人的库里塞内容就是越权扩散。</li>
     * </ul>
     */
    public static final String REGISTRATION_KB_VIEW = "registration:kb:view";

    public static final String REGISTRATION_KB_SAVE = "registration:kb:save";

    public static final String REGISTRATION_KB_SHARE = "registration:kb:share";

    public static final String REGISTRATION_KB_WRITE = "registration:kb:write";

    /**
     * AI Chat（分级：使用 / 写操作）
     *
     * <p>aiChat:panel:use → 面板入口、对话、只读工具；aiChat:panel:write → 写审计 / 确认 / 取消（落盘闸门）。
     *
     * <p><b>必须保持 3 段式</b>：超管的通配权限为 {@code *:*:*}，Sa-Token 的通配按「冒号分段」匹配，
     * 2 段权限串（如 {@code aiChat:use}）**连超管都无法匹配**，会导致接口对所有人 403。
     */
    public static final String AI_CHAT_USE = "aiChat:panel:use";

    public static final String AI_CHAT_WRITE = "aiChat:panel:write";

    /**
     * classloader jars
     */
    public static final String SETTINGS_CLASSLOADERJARS_VIEW = "settings:classloaderjars:view";

    /**
     * dbt 语义项目在线编辑（P2）。
     *
     * <p><b>必须保持 3 段式</b>：超管通配 {@code *:*:*} 按冒号分段匹配，2 段串连超管都匹配不上。
     * 这两条权限挂在「数据开发」菜单（dinky_sys_menu id=5）下，只登记功能权限、不新增菜单行
     * （「dbt」是数据开发页内的前端 tab，不是左侧菜单）。
     */
    public static final String DATASTUDIO_SEMANTIC_VIEW = "datastudio:semantic:view";

    public static final String DATASTUDIO_SEMANTIC_EDIT = "datastudio:semantic:edit";

    /**
     * P4 一期：处理语义资产反馈（RESOLVED / IGNORED）。
     *
     * <p><b>只给「处理」用</b>——提交反馈沿用 {@link #DATASTUDIO_SEMANTIC_VIEW}，
     * 让所有能看语义项目的人都能提意见（否则收不到反馈，闭环无从谈起）。
     * 迁移 {@code V20261023.1.4.9} 已登记该权限点（menu id = 190）。
     */
    public static final String DATASTUDIO_SEMANTIC_FEEDBACK = "datastudio:semantic:feedback";
}
