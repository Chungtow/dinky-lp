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
# 产物: build/dinky-lp-release-1.17-1.2.5.tar.gz
# ================================================

cd "$(dirname "$0")"

echo "=== Dinky-LP 构建（固化 Profile） ==="
echo "Maven: ./mvnw（Wrapper 3.8.4，禁用系统 mvn）"
echo "JDK: $(java -version 2>&1 | head -1)"
echo "Profile: prod,web,aliyun,flink-single-version,flink-1.17,fast"
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

# 3. 构建（./mvnw 而非系统 mvn）
echo "执行: ./mvnw clean package -Dmaven.test.skip=true -P prod,web,aliyun,flink-single-version,flink-1.17,fast"
./mvnw clean package -Dmaven.test.skip=true \
    -P prod,web,aliyun,flink-single-version,flink-1.17,fast

echo ""
echo "=== 构建完成 ==="
echo "产物: build/dinky-lp-release-1.17-1.2.5.tar.gz"
