#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

# ================================================
# Dinky-LP 构建脚本（固化版）
# ------------------------------------------------
# 关键约束（如需升级 Flink 版本，需同步修改下方 Profile 并核对 software/ 包名）:
#   1. 必须用项目自带 ./mvnw（Maven Wrapper 3.8.4）
#      严禁改用系统 mvn（Red Hat 3.6.2，版本不一致会导致构建异常）
#   2. 构建必须用 JDK 8（代码按 JDK 8 编译）
#   3. Profile 固化: prod,web,aliyun,flink-single-version,flink-1.17,fast
#      - flink-1.17: 生产用 Flink 1.17（与 software/flink-1.17.2 匹配）
#      - flink-single-version: 单版本打包
#      - fast: 跳过 spotless/enforcer/javadoc/surefire
#        （palantir-java-format 需 JDK 11+，JDK 8 下会 UnsupportedClassVersionError）
#      - aliyun: Maven 依赖走阿里云源
#   4. web 构建前置: 清理 .umi-production 缓存
#      （否则 frontend-maven-plugin 的 pnpm build 受 max/umi safe-delete 保护，非交互卡住）
#
# 产物: build/dinky-lp-release-1.17-<BASE_VERSION>-<brand>.tar.gz
#       （品牌后缀由 -Dbrand.suffix 注入，见 dinky-assembly/pom.xml 的 finalName）
# ================================================

cd "$(dirname "$0")"

# 0. 品牌参数（白标多品牌切换）
#    - 品牌元数据: dinky-web/brand/<id>.json
#    - 品牌素材:   dinky-web/public/brand/<id>/（覆盖式注入 dist 根）
#    - 详见 docs/plan/20261106-Dinky-LP Web端商业化白标多品牌切换实施计划.md
BRAND="dinky"
while [ $# -gt 0 ]; do
    case "$1" in
        --brand)
            if [ -z "$2" ]; then
                echo "✗ --brand 需要品牌 id，例如: ./build.sh --brand liangpu"
                exit 1
            fi
            BRAND="$2"; shift 2 ;;
        --brand=*)
            BRAND="${1#*=}"; shift ;;
        -h|--help)
            echo "用法: ./build.sh [--brand <id>]"
            echo "  <id> 取值见 dinky-web/brand/*.json（默认 dinky）"
            exit 0 ;;
        *)
            echo "✗ 未知参数: $1"
            echo "用法: ./build.sh [--brand <id>]"
            exit 1 ;;
    esac
done
export BRAND

# 0.1 品牌素材解析（私有品牌素材不进仓库）
#     本仓库公开，只内置官方 dinky 品牌。其他品牌（如 liangpu）的元数据与素材
#     属于商业资产，存放在私有素材库，构建前按需同步到仓库内的约定位置；
#     该位置已被 .gitignore 忽略，不会污染 git status。解析优先级：
#       1) 仓库内已存在  → 直接使用（官方 dinky，或开发者手工放入）
#       2) $DINKY_BRAND_ASSETS/<id>/（默认 <仓库>/../brand-assets/<id>）
BRAND_META="dinky-web/brand/${BRAND}.json"
BRAND_ASSET_DIR="dinky-web/public/brand/${BRAND}"
if [ ! -f "$BRAND_META" ] || [ ! -d "$BRAND_ASSET_DIR" ]; then
    ASSETS_ROOT="${DINKY_BRAND_ASSETS:-$(cd .. && pwd)/brand-assets}"
    ASSET_SRC="${ASSETS_ROOT}/${BRAND}"
    if [ ! -f "${ASSET_SRC}/brand.json" ]; then
        echo ""
        echo "✗ 品牌 ${BRAND} 的元数据不存在：${BRAND_META}"
        echo "  本仓库只内置官方 dinky 品牌，其他品牌需从私有素材库同步。"
        echo "  期望路径：${ASSET_SRC}/brand.json"
        echo "  可用环境变量指定素材库根目录："
        echo "    DINKY_BRAND_ASSETS=/path/to/brand-assets bash build.sh --brand ${BRAND}"
        exit 1
    fi
    echo "品牌素材来源：私有素材库 ${ASSET_SRC}"
    mkdir -p "$BRAND_ASSET_DIR"
    cp -f "${ASSET_SRC}/brand.json" "$BRAND_META"
    for item in "${ASSET_SRC}"/*; do
        [ -e "$item" ] || continue
        base=$(basename "$item")
        case "$base" in
            brand.json|README.md) continue ;;
        esac
        cp -Rf "$item" "$BRAND_ASSET_DIR/"
    done
    echo "  ✓ 已同步至 ${BRAND_ASSET_DIR}（该路径被 .gitignore 忽略）"
fi

echo "=== Dinky-LP 构建（固化 Profile） ==="
echo "Maven: ./mvnw（Wrapper 3.8.4，禁用系统 mvn）"
echo "JDK: $(java -version 2>&1 | head -1)"
echo "Profile: prod,web,aliyun,flink-single-version,flink-1.17,fast"
echo "品牌: ${BRAND}（元数据 dinky-web/brand/${BRAND}.json）"
echo "=========================================="

# 1. 检查 JDK 8
if ! java -version 2>&1 | grep -q 'version "1\.8'; then
    echo "✗ 构建需 JDK 8（当前非 1.8）。请将 JAVA_HOME 切到 JDK 8 后重试。"
    exit 1
fi
echo "✓ JDK 8 检查通过"

# 1.5 spotless 自愈
#   背景: 主构建用 JDK 8（代码按 JDK 8 编译），而 palantir-java-format 需 JDK 11+，
#         两者互斥 → fast profile 跳过了 spotless → 本地构建从不检查格式，
#         只能等 GitHub CI 的 spotless:check 报错（已发生过一次）。
#   处置: 检测到 JDK 11+ 就先跑一遍 spotless:apply，把格式问题消灭在本地。
#         找不到 JDK 11+ 时仅告警，不阻断构建。
SPOTLESS_JDK=""
for jd in /usr/lib/jvm/java-11-openjdk* /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/java-21-openjdk*; do
    [ -d "$jd" ] && SPOTLESS_JDK="$jd" && break
done
if [ -n "$SPOTLESS_JDK" ]; then
    echo "执行 spotless:apply（JDK: $(basename "$SPOTLESS_JDK")）..."
    if JAVA_HOME="$SPOTLESS_JDK" ./mvnw -q spotless:apply 2>&1 | tail -5; then
        echo "✓ spotless 格式化完成（避免 GitHub CI 因格式失败）"
    else
        echo "⚠ spotless:apply 执行异常，继续构建（GitHub CI 可能会报格式问题）"
    fi
else
    echo "⚠ 未找到 JDK 11+，跳过 spotless；GitHub CI 的 spotless:check 可能因格式问题失败"
fi

# 2. 前置: 清理前端缓存与旧产物（避免 umi safe-delete 非交互卡住）
#    - .umi-production / src/.umi-production: umi 编译缓存
#    - dist: 上次构建产物，若残留 ≥500 文件会触发 SAFE_DELETE_BULK_CONFIRM_REQUIRED
echo "清理 .umi-production 缓存与 dist 旧产物..."
rm -rf dinky-web/.umi-production dinky-web/src/.umi-production dinky-web/dist 2>/dev/null || true

# 2.5 清理上一轮构建遗留的前端静态资源
#     dinky-admin 的 copy-static 是"增量覆盖、不清空目标目录"，切换品牌若不清理，
#     上一个品牌独有的素材（如 community/ 二维码）会残留在本轮发布包里。
echo "清理上一轮前端静态资源（避免跨品牌残留）..."
rm -rf dinky-admin/src/main/resources/static 2>/dev/null || true

# 2.6 构建期品牌自证文件（制品身份自描述）
#     umi 会把 public/ 原样拷进 dist 根 → copy-static 收进 jar 的 static/ →
#     制品内路径为 html/brand-build-info.json。release/deploy 据此校验品牌，
#     无需解析 HTML（见 docs/plan/20261112-Dinky-LP 制品品牌化命名与发布链改造实施计划.md §3.5）
#     注意：该文件**不在构建末尾删除**——构建过程本身已产生大量删除操作，
#     再追加一次 rm 会触发构建期批量删除护栏（SAFE_DELETE_BULK_CONFIRM_REQUIRED）。
#     改为在 .gitignore 中忽略，每次构建前覆盖写入，既不留痕也不阻断构建。
BRAND_INFO_FILE="dinky-web/public/brand-build-info.json"

BASE_VERSION=$(grep -m1 -o '<revision>[^<]*</revision>' pom.xml | sed 's#</\?revision>##g')
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo unknown)
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then GIT_DIRTY="yes"; else GIT_DIRTY="no"; fi
BRAND_TITLE=$(python3 -c "import json;d=json.load(open('dinky-web/brand/${BRAND}.json',encoding='utf-8'));print(d.get('documentTitle') or d.get('name') or '')" 2>/dev/null || echo "")
export BRAND BRAND_TITLE BASE_VERSION GIT_COMMIT GIT_DIRTY
python3 - "$BRAND_INFO_FILE" <<'PY'
import datetime, json, os, socket, sys

data = {
    "id": os.environ["BRAND"],
    "documentTitle": os.environ.get("BRAND_TITLE", ""),
    "version": os.environ.get("BASE_VERSION", ""),
    "gitCommit": os.environ.get("GIT_COMMIT", "unknown"),
    "gitDirty": os.environ.get("GIT_DIRTY", "unknown"),
    "buildTime": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "builder": "%s@%s" % (os.environ.get("USER", "?"), socket.gethostname()),
}
with open(sys.argv[1], "w", encoding="utf-8") as fh:
    fh.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY
echo "✓ 品牌自证文件：${BRAND_INFO_FILE}（id=${BRAND}, version=${BASE_VERSION}, commit=${GIT_COMMIT:0:8}, dirty=${GIT_DIRTY}）"

# 3. 构建（./mvnw 而非系统 mvn）
#    -Dbrand         → config.ts 读取，决定品牌元数据/素材
#    -Dbrand.suffix  → assembly finalName，决定包名与包内顶层目录
echo "执行: ./mvnw clean package -Dmaven.test.skip=true -Dbrand=${BRAND} -Dbrand.suffix=-${BRAND} -P prod,web,aliyun,flink-single-version,flink-1.17,fast"
./mvnw clean package -Dmaven.test.skip=true \
    -Dbrand="${BRAND}" \
    -Dbrand.suffix="-${BRAND}" \
    -P prod,web,aliyun,flink-single-version,flink-1.17,fast
MVN_EXIT=$?
if [ ${MVN_EXIT} -ne 0 ]; then
    echo ""
    echo "✗ 构建失败（mvn 退出码 ${MVN_EXIT}）"
    exit ${MVN_EXIT}
fi

# 4. 品牌产物自检与中间产物清理
#    - 断言：品牌素材已注入 dist 根（证明 config.ts 的 copy 覆盖注入生效）
#      logo 文件名取自 brand/<id>.json 的 logo 字段（支持 .svg / .png），不要写死
#    - 清理：dist/brand 是品牌素材"源"目录（umi 整体拷贝 public/ 所致），
#            dinky-admin 的 copy-static 已 exclude brand/**，不进发布包；这里清掉中间产物
BRAND_LOGO=$(python3 -c "import json;print(json.load(open('dinky-web/brand/${BRAND}.json',encoding='utf-8')).get('logo') or 'brand-logo.svg')" 2>/dev/null || echo "brand-logo.svg")
if [ -f "dinky-web/dist/${BRAND_LOGO}" ]; then
    echo "✓ 品牌素材已注入 dist 根：${BRAND_LOGO}（品牌: ${BRAND}）"
else
    echo "✗ dist 根缺少 ${BRAND_LOGO}，品牌 copy 注入可能未生效（品牌: ${BRAND}）"
    exit 1
fi
for i in 1 2 3; do
    [ -d "dinky-web/dist/brand" ] || break
    rm -rf "dinky-web/dist/brand" 2>/dev/null || true
    sleep 1
done
if [ -d "dinky-web/dist/brand" ]; then
    echo "⚠ 未能清理 dist/brand（不影响发布包：copy-static 已 exclude brand/**）"
else
    echo "✓ 已清理 dist/brand 中间产物"
fi

# 4.1 产物命名断言：必须存在**唯一**一个带本品牌后缀的包
shopt -s nullglob
BRAND_TARBALLS=(build/dinky-lp-release-1.17-*-"${BRAND}".tar.gz)
shopt -u nullglob
if [ ${#BRAND_TARBALLS[@]} -ne 1 ]; then
    echo "✗ 未找到唯一的品牌构建产物：build/dinky-lp-release-1.17-*-${BRAND}.tar.gz（命中 ${#BRAND_TARBALLS[@]} 个）"
    echo "  当前 build/ 下的 tar 包："
    ls -1 build/*.tar.gz 2>/dev/null | sed 's/^/    /' || echo "    (无)"
    echo "  排查：pom 的 brand.suffix 是否生效（构建需带 -Dbrand.suffix=-${BRAND}）"
    exit 1
fi
BRAND_TARBALL="${BRAND_TARBALLS[0]}"
echo "✓ 构建产物（品牌 ${BRAND}）：${BRAND_TARBALL}"

# 4.2 制品自证断言：包内 brand-build-info.json 的 id 必须等于本次品牌
PKG_ID=$(tar -xzOf "$BRAND_TARBALL" --wildcards '*/html/brand-build-info.json' 2>/dev/null \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
if [ "$PKG_ID" != "$BRAND" ]; then
    echo "✗ 制品内品牌自证不一致：包内 id='${PKG_ID}'，期望 '${BRAND}'"
    exit 1
fi
echo "✓ 制品自证通过：html/brand-build-info.json id=${PKG_ID}"

echo ""
echo "=== 构建完成 ==="
echo "产物: ${BRAND_TARBALL}（品牌: ${BRAND}）"
