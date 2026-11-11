# Dinky-LP

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![Base](https://img.shields.io/badge/base-Dinky%201.2.5-blue.svg?style=flat-square)](https://github.com/DataLinkDC/dinky)
[![CN doc](https://img.shields.io/badge/文档-中文版-blue.svg?style=flat-square)](README_zh_CN.md)
[![EN doc](https://img.shields.io/badge/document-English-blue.svg?style=flat-square)](README.md)

Dinky-LP is a commercially delivered real-time data platform, forked from [Dinky](https://github.com/DataLinkDC/dinky) 1.2.5 and developed independently by Hangzhou Liangpu Technology Co., Ltd. It keeps Dinky's development model and runtime architecture intact, and adds the capabilities required for commercial delivery — most notably **multi-brand white-labeling**, an in-console **AI assistant**, and a series of data-development performance improvements.

## Introduction

We started from the upstream tag `Release-1.2.5` and maintain our own release train on top of it. The base version of Dinky **has not been upgraded**, so every upstream document, SQL syntax reference and Flink compatibility statement still applies; what we changed is the product surface and the operational ergonomics.

| Item | Value |
|---|---|
| Upstream | [DataLinkDC/dinky](https://github.com/DataLinkDC/dinky) @ `Release-1.2.5` |
| This fork | [Chungtow/dinky-lp](https://github.com/Chungtow/dinky-lp) |
| Base / artifact version | `1.2.5` / `1.2.5-lp.<N>` |
| Flink runtime | Apache Flink 1.17 (single-version package) |
| Release line | `main` (releases) · `lp-dev` (development, default branch) |

> **Two version numbers, on purpose.** `1.2.5` is the Dinky base we forked from and never changes; `lp.<N>` is our own artifact sequence. A release tag such as `lp-v2.0.0` marks a cycle of commercial work, not an upstream upgrade.

## Feature

### Inherited from Dinky

- Immersive Flink SQL development: completion, formatting, online debugging, syntax validation, execution plan, catalog, lineage and version diff.
- Multi-version Flink SQL execution modes: Local, Standalone, Yarn/Kubernetes Session, Yarn Per-Job, Yarn/Kubernetes Application.
- Flink ecosystem support: Connector, FlinkCEP, FlinkCDC, Paimon, PyFlink.
- Flink SQL syntax enhancements: database synchronization, execution environment, global variables, table-valued aggregate functions, dependency loading, row-level permissions, execute-jar.
- Real-time ingestion of a whole database via FlinkCDC (including CDC Pipeline), with online preview of Table / ChangeLog / UDF.
- Catalog and metadata exploration, plus task operations: online/offline, job info, logs, versions, snapshots, monitoring, SQL lineage and alert history.
- Alerting to DingTalk, WeCom, Feishu, e-mail, SMS and HTTP; managed Savepoint/Checkpoint restore and triggering.
- Resource management (cluster instances, data sources, alerts, documents, global variables, Git projects, UDFs, resources, system settings) and enterprise administration (tenants, users, roles, menus, tokens).

### Added in this fork

| Capability | Description |
|---|---|
| **Multi-brand white-label** | All brand-facing elements (logo, favicon, login background, footer texture, product name, copyright, external links, primary color) collapse into one brand package plus one JSON file. `bash build.sh --brand <id>` produces a build for that brand; shipping a new brand requires no code change. See [White-label](#white-label-multi-brand). |
| **Data Studio AI assistant** | An in-console assistant for Flink SQL work: unified `@` candidate index across assets with fuzzy local matching, semantic-handle drill-down, FlinkSQL job review, four layers of context assembly, and message actions (copy / edit / delete / keep as knowledge). |
| **Semantic projects** | Semantic assets are defined in YAML and can be inspected and edited inside Data Studio; versions and YAML fingerprints are tracked server-side, and the assistant can drill down to field-level semantics through the `@semantic` handle. See [Semantic Assets and Knowledge](#semantic-assets-knowledge-and-skills). |
| **Skills** | Reusable Markdown knowledge assets, managed from the registry and editable inside Data Studio. The assistant can list, read, create, rewrite and delete skills — every write or delete requires human confirmation. |
| **Knowledge base** | Registry-side management of knowledge libraries, discovery of unmanaged libraries, and per-library authorization; the assistant grounds its answers by searching them. |
| **Spark ThriftServer as a data source** | JDBC connectivity to Spark ThriftServer, so Spark SQL tasks can be developed and submitted from the same console. |
| **Task variable injection** | Variables can be injected into task SQL at submission time, with a defined resolution order. |
| **Data-development performance** | Editable area switched to uncontrolled inputs, debounced `@` table search with LRU cache, frame-aligned splitter dragging, and theme tokens reduced to a single source of truth (fixes missing dark-mode variables). |
| **Result viewer ergonomics** | Reworked result/service panel layout, denser result grid and completed interaction affordances. |
| **Multi-tenant fixes** | Tenant identifier is now propagated through `TenantContextHolder`, eliminating the empty-metadata / 401 loop caused by relying on request headers only. |

## White-label (multi-brand)

A brand is described by two things and nothing else:

```
dinky-web/
├─ brand/<id>.json              # metadata: names, copyright, links, theme, feature switches
└─ public/brand/<id>/           # assets, mirroring the public/ layout
   ├─ brand-logo.(svg|png)      # required
   ├─ favicon.ico               # required
   ├─ imgs/login_background.jpg # optional: overrides the shared layer
   ├─ icons/footer-bg.svg       # optional
   └─ login_animation.json      # optional: Lottie used on the login page
```

At build time `config/config.ts` reads the selected brand, validates the required assets (fail-fast with the missing list), copies the brand package over the bundle root, and injects the metadata as the browser-side `__BRAND__` constant; the intermediate `brand/` directory is removed when the build finishes. Components and locales read from that constant, so product names, copyright lines, attribution notices and link sets follow the brand and the active language (`name` / `nameEn`, `copyright` / `copyrightEn`, `attribution.zh` / `attribution.en`). An empty string in `links` means "render nothing" — no dead `href="#"` anchors are produced.

```bash
# Official brand (default)
bash build.sh

# Another brand
bash build.sh --brand liangpu
```

Brands shipped today: `dinky` (upstream identity, used for equivalence checks) and `liangpu` (commercial identity). **Runtime switching is intentionally not supported** — one build yields exactly one brand, which keeps the injection path a build-time concern with zero runtime cost.

## Semantic Assets, Knowledge and Skills

Beyond plain SQL development, this fork promotes three kinds of asset — **semantics**, **skills** and **knowledge** — to first-class citizens: each can be browsed, edited, and cited by the AI assistant. They are maintained by humans and consumed as context by the assistant.

### Semantic projects

Semantic assets are written in YAML and opened as *semantic projects* inside Data Studio.

| Capability | Description |
|---|---|
| Inspect and edit | The built-in editor switches language mode by extension (`.yml` / `.yaml` → YAML, `.sql` → SQL); semantic files are edited and saved in place. |
| Versioning | Version and YAML fingerprint are recorded server-side for each file, which makes change detection and incremental comparison possible. |
| Per-file feedback | Feedback can be filed against the current semantic file and is kept alongside the asset. |
| AI drill-down | In the editor the `@semantic` handle drills down to field-level semantics; the assistant also cites the semantic layer when assembling context. |

### Skills

Skills are reusable Markdown knowledge assets: managed centrally under *Registry → Skills*, and editable directly inside Data Studio.

| Capability | Description |
|---|---|
| Registration | The registry lists skills with an overview and a file panel, and supports creating new assets with name/handle conflict validation. |
| Editing | Data Studio ships a skill toolbar and a Markdown editor; saving writes the file back to that skill. |
| Parsing | The server parses and renders skill documents, resolves handle dependencies when assembling assets, and enforces an explicit path guard that rejects out-of-scope paths. |
| AI access | The assistant exposes tools to list, read, create, rewrite files of, and delete a skill. |
| Write safety | An AI rewrite is **never saved automatically**: a diff of *original* versus *rewritten* is shown first, confirmation only puts the content into the editor, and the user saves it explicitly. Deleting a skill requires typing its name to confirm. |

### Knowledge base

*Registry → Knowledge base* manages knowledge assets and their authorization.

| Capability | Description |
|---|---|
| Library management | A list of knowledge libraries plus **discovery of unmanaged libraries**, pulling scattered knowledge sources into a controlled inventory. |
| Authorization | Access is granted per library and composes with the multi-tenant and role model. |
| AI retrieval | The assistant searches knowledge libraries through a dedicated retrieval tool and grounds its answers in the results. |

> Together these three form the assistant's context foundation: **the semantic layer answers "what does this field mean", the knowledge base answers "what does the business mandate", and skills answer "how is this kind of work done"** — all human-maintained, auditable and revertible.

## Principle

Dinky is a web console around Flink: the browser talks to a Spring Boot server, which compiles, submits and monitors Flink jobs through the Flink client, while metadata lives in MySQL and coordination state in ZooKeeper.

![dinky_principle](https://raw.githubusercontent.com/DataLinkDC/dinky/dev/images/main/dinky_principle.png)

*Architecture diagram from the upstream project — this fork does not alter the runtime topology.*

## Run the Screenshot

The console is fully re-skinned per brand, so upstream screenshots no longer represent what you see. The main modules are:

| Module | What it covers |
|---|---|
| Data Studio | SQL/IDE workspace, catalogs, semantic projects, skills and knowledge panels, AI assistant |
| Data Debug | Table / ChangeLog / UDF preview against a live session |
| DevOps | Job lifecycle, history, logs, snapshots, alert records |
| Metrics | Per-job throughput, latency and checkpoint statistics |

Deploy the build and the interface reflects the brand you selected.

## Participate in Contributions

Development happens on `lp-dev`; `main` only receives release merges.

```bash
git checkout lp-dev && git pull --ff-only origin lp-dev
git checkout -b feat/<description>     # or fix/<description>
```

1. Branch from `lp-dev`, keep the change scoped to one concern.
2. Run `bash build.sh --brand dinky` and make sure the working tree is clean afterwards — the build runs `spotless:apply` and will reformat Java sources.
3. Open a pull request with **base = `lp-dev`**. Merged branches are **kept**, both locally and remotely, so history stays traceable.
4. Artifacts are cut from `lp-dev` after merge; intermediate builds never create tags.

Commit messages should explain *why* a change was made, not merely restate what changed.

## How to Deploy

### Build

Requirements: **JDK 8** (the code targets Java 8) and the bundled Maven Wrapper. Do not use a system Maven — the wrapper version is part of the build contract.

```bash
bash build.sh                  # official brand
bash build.sh --brand liangpu  # branded build
```

The script wraps `./mvnw clean package -Dmaven.test.skip=true -Dbrand=<id> -P prod,web,aliyun,flink-single-version,flink-1.17,fast`, cleans the frontend cache and the previous `static/` output (so assets from an earlier brand cannot leak into the new package), asserts that the brand logo landed in `dist/`, and writes the tarball to `build/`.

### Deploy

The tarball is a self-contained Dinky distribution: unpack it, point it at MySQL and ZooKeeper, and start the server. Environment-specific orchestration — image building, rollout, rollback — lives outside this repository.

## Thanks

Standing on the shoulders of giants: Dinky was born from the open-source community, and so was this fork. Thanks to every project and community we build upon — in particular the Dinky authors and maintainers, whose design we inherited almost entirely.

[Apache Flink](https://github.com/apache/flink) · [Apache FlinkCDC](https://github.com/apache/flink-cdc) · [Apache Paimon](https://github.com/apache/paimon) · [Apache DolphinScheduler](https://github.com/apache/dolphinscheduler) · [Apache Doris](https://github.com/apache/doris) · [Druid](https://github.com/alibaba/druid) · [Ant Design Pro](https://github.com/ant-design/ant-design-pro) · [MyBatis-Plus](https://github.com/baomidou/mybatis-plus) · [Monaco Editor](https://github.com/microsoft/monaco-editor) · [Sa-Token](https://github.com/dromara/Sa-Token) · [Spring Boot](https://spring.io/projects/spring-boot)

## Get Help

This is an independent fork, not the upstream project.

- For questions about **Dinky-LP** — builds, branding, deployments, features added here — open an issue in [Chungtow/dinky-lp](https://github.com/Chungtow/dinky-lp/issues).
- For questions about **Dinky itself** — SQL syntax, Flink compatibility, upstream behaviour — consult the [official documentation](http://www.dinky.org.cn/) first. Upstream answers apply unless a feature is listed above as changed.

Please do not report this fork's issues to the upstream project.

## Copyright

Released under the [Apache License 2.0](LICENSE). This project contains code derived from Dinky; the original copyright and license notices carried by that code are retained as-is and remain fully in effect.

Product names, logos and copyright lines shown in the interface are supplied by the active brand package and do not alter the licensing terms of the source code.
