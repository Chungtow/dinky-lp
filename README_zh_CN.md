# Dinky-LP

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![Base](https://img.shields.io/badge/base-Dinky%201.2.5-blue.svg?style=flat-square)](https://github.com/DataLinkDC/dinky)
[![CN doc](https://img.shields.io/badge/文档-中文版-blue.svg?style=flat-square)](README_zh_CN.md)
[![EN doc](https://img.shields.io/badge/document-English-blue.svg?style=flat-square)](README.md)

Dinky-LP 是杭州良璞科技有限公司基于 [Dinky](https://github.com/DataLinkDC/dinky) 1.2.5 二次开发的商业化实时数据平台。Dinky 的开发模型与运行时架构被完整保留，我们在此之上补齐了商业交付所需的能力——最核心的是**多品牌白标切换**，以及控制台内置的 **AI 助手**与一系列数据开发性能优化。

## 简介

本项目自上游 `Release-1.2.5` 分出后独立演进。Dinky 的基座版本**没有升级**，因此上游的开发文档、SQL 语法手册与 Flink 兼容性说明依然完全适用；我们改动的是产品外观与使用体验。

| 项 | 值 |
|---|---|
| 上游 | [DataLinkDC/dinky](https://github.com/DataLinkDC/dinky) @ `Release-1.2.5` |
| 本仓库 | [Chungtow/dinky-lp](https://github.com/Chungtow/dinky-lp) |
| 基座 / 制品版本 | `1.2.5` / `1.2.5-lp.<N>` |
| Flink 运行时 | Apache Flink 1.17（单版本打包） |
| 分支模型 | `main` 发行线 · `lp-dev` 开发线（默认分支） |

> **两个版本号是刻意分开的。** `1.2.5` 是我们分叉时的 Dinky 基座版本，永不变化；`lp.<N>` 是我方制品流水号。`lp-v2.0.0` 这类发行 tag 标记的是一轮商业化改造周期，并不代表上游升级。

## 功能

### 继承自 Dinky

- FlinkSQL 数据开发：提示补全、语句美化、在线调试、逻辑校验、执行计划、Catalog、血缘分析、版本对比
- 支持 FlinkSQL 多版本开发与多种执行模式：Local、Standalone、Yarn/Kubernetes Session、Yarn Per-Job、Yarn/Kubernetes Application
- 支持 Flink 生态：Connector、FlinkCEP、FlinkCDC、Paimon、PyFlink
- FlinkSQL 语法增强：整库同步、执行环境、全局变量、表值聚合、加载依赖、行级权限、执行 Jar 任务
- 支持 FlinkCDC 整库实时入仓入湖与 FlinkCDCPipeline 整库同步
- 支持实时在线调试预览 Table、ChangeLog 和 UDF
- 支持 Catalog、数据源元数据在线查询及管理
- 支持实时任务运维：上线下线、作业信息、作业日志、版本信息、作业快照、监控、SQL 血缘、告警记录
- 支持实时作业报警及报警组：钉钉、企业微信、飞书、邮箱、短信、HTTP
- 支持自动托管的 SavePoint/CheckPoint 恢复与触发机制：最近一次、最早一次、指定一次
- 支持多种资源管理：集群实例、集群配置、数据源、告警、文档、全局变量、Git 项目、UDF、资源、系统配置
- 支持企业级管理：多租户、用户、角色、菜单、令牌

### 本分支新增与增强

| 能力 | 说明 |
|---|---|
| **多品牌白标** | logo、favicon、登录背景、页脚底纹、产品名、版权、外链、主色等所有品牌相关元素，统一收敛为一个品牌素材目录 + 一份 JSON。`bash build.sh --brand <id>` 即产出该品牌的构建，新增品牌无需改任何代码。详见[多品牌白标](#多品牌白标)。 |
| **数据开发 AI 助手** | 控制台内置助手：跨资产的 `@` 候选统一索引与本地模糊匹配、语义句柄下钻、FlinkSQL 作业审查、四层上下文装配，以及消息操作（复制 / 编辑 / 删除 / 沉淀为知识）。 |
| **语义项目（Semantic）** | 以 YAML 定义语义资产，Data Studio 内可直接查看与编辑；版本与 YAML 指纹由后端记录，AI 助手可经 `@semantic` 句柄下钻到字段级语义。详见[语义资产与知识协作](#语义资产与知识协作)。 |
| **技能（Skills）** | 注册中心统一管理技能资产，Data Studio 内置 Markdown 编辑器；AI 可读取、创建、改写与删除技能，写入与删除均需人工确认。 |
| **知识库（Knowledge Base）** | 注册中心提供库列表、未纳管库发现与授权管理；AI 助手经知识检索工具引用知识库内容作答。 |
| **Spark ThriftServer 数据源** | 支持以 JDBC 接入 Spark ThriftServer，Spark SQL 任务可在同一控制台内开发与提交。 |
| **任务变量注入** | 任务 SQL 在提交期可注入变量，并明确了变量解析的优先级。 |
| **数据开发性能** | 编辑器改为非受控、`@` 表名搜索防抖与 LRU 缓存、分栏拖动按帧合并、主题 token 单真源化（同时修复深色模式变量缺失）。 |
| **结果视图体验** | 数据开发「服务」面板布局重构，结果表格更紧凑，交互补齐。 |
| **多租户修复** | 租户标识改由 `TenantContextHolder` 贯穿传递，消除了只依赖请求头导致的元数据为空 / 401 自锁。 |

## 多品牌白标

一个品牌的全部定义只有两处：

```
dinky-web/
├─ brand/<id>.json              # 元数据：名称、版权、外链、主题色、功能开关
└─ public/brand/<id>/           # 品牌素材，目录结构与 public/ 逐层对应
   ├─ brand-logo.(svg|png)      # 必选
   ├─ favicon.ico               # 必选
   ├─ imgs/login_background.jpg # 可选：覆盖公共层
   ├─ icons/footer-bg.svg       # 可选
   └─ login_animation.json      # 可选：登录页 Lottie 动画
```

构建期由 `config/config.ts` 读取所选品牌：校验必选素材（缺失即 fail-fast 并列出缺失项），把品牌包覆盖注入到产物根目录，并将元数据以 `__BRAND__` 常量注入浏览器端；中间的 `brand/` 目录在构建收尾时清理。组件与 locales 统一从该常量取值，因此产品名、版权行、归属声明与外链集合都随品牌与当前语言切换（`name` / `nameEn`、`copyright` / `copyrightEn`、`attribution.zh` / `attribution.en`）。`links` 中的空串语义是"不渲染"，不会产出 `href="#"` 死链。

```bash
# 官方品牌（默认）
bash build.sh

# 指定其他品牌
bash build.sh --brand liangpu
```

现有品牌两个：`dinky`（上游身份，用于等价性验证）与 `liangpu`（商业身份）。**刻意不做运行时切换**——一次构建只产出一个品牌，使注入逻辑完全留在构建期，运行时零开销。

## 语义资产与知识协作

除了传统意义上的 SQL 开发，本分支把「语义层」「技能」「知识库」三类资产做成了**可查看、可编辑、可被 AI 引用**的一等公民。三者既是人维护的资产，也是 AI 助手作答时的上下文来源。

### 语义项目（Semantic）

语义资产以 YAML 定义，在 Data Studio 内以「语义项目」的形式打开：

| 能力 | 说明 |
|---|---|
| 查看与编辑 | 内置编辑器按扩展名切换语言模式（`.yml` / `.yaml` 走 YAML，SQL 走 SQL），语义文件可直接修改保存 |
| 版本与指纹 | 每个文件的版本与 YAML 指纹由后端记录，便于识别改动与增量比对 |
| 文件级反馈 | 可针对当前语义文件提交反馈，反馈随资产一起沉淀 |
| AI 下钻 | 编辑器中经 `@semantic` 句柄可直接下钻到字段级语义；AI 助手在装配上下文时也会引用语义层 |

### 技能（Skills）

技能是可复用的 Markdown 知识资产，由「注册中心 → Skills」统一管理，同时可在 Data Studio 内直接编辑：

| 能力 | 说明 |
|---|---|
| 资产注册 | 注册中心提供技能列表、概览与文件面板，支持新建资产；创建时校验技能名与句柄的命名冲突 |
| 在线编辑 | Data Studio 内置「技能」工具栏与 Markdown 编辑器，保存即写入该技能文件 |
| 解析与渲染 | 后端对技能文档做 Markdown 解析与渲染，装配资产时按句柄解析依赖；路径访问有显式守卫，越界路径直接拒绝 |
| AI 可操作 | AI 助手提供技能相关工具：列举、读取、创建、改写文件、删除整个技能 |
| 写入安全 | AI 的改写**不会自动保存**：先弹出 Diff 对比「原文」与「改写后」，确认后只把内容放入编辑区，由人自行点击保存；删除技能需输入技能名二次确认 |

### 知识库（Knowledge Base）

「注册中心 → 知识库」提供知识资产的纳管与授权：

| 能力 | 说明 |
|---|---|
| 库管理 | 知识库列表，以及**未纳管库的发现**——把散落的知识来源收敛到可控清单 |
| 授权 | 按库粒度做授权，配合多租户与角色体系控制可访问范围 |
| AI 检索 | AI 助手通过知识检索工具查询知识库内容，把检索结果作为作答依据 |

> 语义层、技能与知识库共同构成 AI 助手的上下文底座：**语义层回答"字段是什么意思"，知识库回答"业务上怎么规定的"，技能回答"这类活该怎么干"**。三者都由人维护、可审计、可回退。

## 原理

Dinky 是 Flink 之上的 Web 控制台：浏览器访问 Spring Boot 服务端，服务端通过 Flink 客户端编译、提交与监控作业，元数据存于 MySQL，协调状态由 ZooKeeper 维护。

![dinky_principle](https://raw.githubusercontent.com/DataLinkDC/dinky/dev/images/main/dinky_principle.png)

*架构图取自上游项目——本分支未改动运行时拓扑。*

## 运行效果

控制台已按品牌整体换肤，上游截图不再代表实际界面。主要模块如下：

| 模块 | 内容 |
|---|---|
| 数据开发 | SQL / IDE 工作区、Catalog、语义项目、技能与知识库面板、AI 助手 |
| 数据调试 | 面向活动会话的 Table / ChangeLog / UDF 预览 |
| 作业运维 | 作业生命周期、历史、日志、快照、告警记录 |
| 作业指标 | 单作业的吞吐、延迟与 Checkpoint 统计 |

部署选定品牌的制品后，界面即呈现该品牌。

## 参与贡献

开发在 `lp-dev` 上进行，`main` 只接收发行合并。

```bash
git checkout lp-dev && git pull --ff-only origin lp-dev
git checkout -b feat/<描述>     # 或 fix/<描述>
```

1. 自 `lp-dev` 切出分支，一次只做一件事。
2. 执行 `bash build.sh --brand dinky`，并确保构建后工作区干净——构建会运行 `spotless:apply`，可能重排 Java 源码格式。
3. 提交 PR，**base = `lp-dev`**。分支合并后**保留**，本地与远程都不删除，便于追溯历史。
4. 制品在合并之后由 `lp-dev` 产出，中间构建不打 tag。

提交信息应说明改动的原因，而不只是复述改了什么。

## 如何部署

### 构建

依赖 **JDK 8**（代码按 Java 8 编译）与项目自带的 Maven Wrapper。不要改用系统 Maven——Wrapper 版本属于构建契约的一部分。

```bash
bash build.sh                  # 官方品牌
bash build.sh --brand liangpu  # 指定品牌
```

脚本内部执行 `./mvnw clean package -Dmaven.test.skip=true -Dbrand=<id> -P prod,web,aliyun,flink-single-version,flink-1.17,fast`，构建前清理前端缓存与上一轮 `static/` 产物（避免上一个品牌的素材残留进新包），构建后断言品牌 logo 已落到 `dist/`，最终在 `build/` 下产出 tar 包。

### 部署

产物是自包含的 Dinky 发行包：解压、配置好 MySQL 与 ZooKeeper、启动服务端即可。面向具体环境的编排（镜像构建、发布、回滚）不在本仓库范围内。

## 感谢

站在巨人的肩膀上，Dinky 才得以诞生，本分支亦然。感谢我们所依赖的每一个开源项目及其社区——尤其是 Dinky 的作者与维护者，我们几乎完整继承了他们设计的成果。

[Apache Flink](https://github.com/apache/flink) · [Apache FlinkCDC](https://github.com/apache/flink-cdc) · [Apache Paimon](https://github.com/apache/paimon) · [Apache DolphinScheduler](https://github.com/apache/dolphinscheduler) · [Apache Doris](https://github.com/apache/doris) · [Druid](https://github.com/alibaba/druid) · [Ant Design Pro](https://github.com/ant-design/ant-design-pro) · [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) · [Monaco Editor](https://github.com/microsoft/monaco-editor) · [Sa-Token](https://github.com/dromara/Sa-Token) · [Spring Boot](https://spring.io/projects/spring-boot)

## 获得帮助

这是 Dinky 的一个独立分叉，不是上游官方项目。

- 关于 **Dinky-LP** 的问题——构建、品牌配置、部署、本分支新增功能——请在 [Chungtow/dinky-lp](https://github.com/Chungtow/dinky-lp/issues) 提 issue。
- 关于 **Dinky 本身**的问题——SQL 语法、Flink 兼容性、上游行为——请先查[官方文档](http://www.dinky.org.cn/)。除上文列出的改动项外，上游的解答同样适用。

请勿将本分支的问题提交给上游项目。

## 版权

本项目以 [Apache License 2.0](LICENSE) 发布。代码派生自 Dinky，其中保留的原始版权与许可声明维持原样并继续有效。

界面中展示的产品名称、logo 与版权文案由当前品牌包提供，不改变源代码的许可条款。
