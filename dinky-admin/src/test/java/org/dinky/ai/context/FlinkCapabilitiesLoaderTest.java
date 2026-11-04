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

package org.dinky.ai.context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/**
 * 能力画像加载：部署层探测文件优先，缺失/损坏回退内置基线。
 *
 * <p>这份文件是 connector 白名单的唯一事实来源（校验器也用它），
 * 所以"读不到就静默为空"是绝不能接受的退化。
 *
 * @since 2026/11/03
 */
public class FlinkCapabilitiesLoaderTest {

    @Test
    public void baselineProvidesRealConnectors() {
        FlinkCapabilities capabilities = new FlinkCapabilitiesLoader().load();
        Assert.assertFalse("基线必须给出 Flink 版本", capabilities.getFlinkVersion().isEmpty());
        Assert.assertTrue(capabilities.allConnectors().contains("kafka"));
        Assert.assertTrue(capabilities.allConnectors().contains("jdbc"));
        Assert.assertTrue(capabilities.allConnectors().contains("doris"));
        Assert.assertFalse("hive 未安装，基线不得包含", capabilities.allConnectors().contains("hive"));
    }

    @Test
    public void debugConnectorsAreExcludedFromUsableList() {
        FlinkCapabilities capabilities = new FlinkCapabilitiesLoader().load();
        Assert.assertTrue(
                "调试 connector 仍在白名单内（可用）", capabilities.allConnectors().contains("print"));
        Assert.assertFalse(
                "调试 connector 不得出现在可用清单", capabilities.usableConnectors().contains("print"));
    }

    @Test
    public void externalFileOverridesBaseline() throws Exception {
        File file = File.createTempFile("flink-capabilities", ".yaml");
        file.deleteOnExit();
        Files.write(
                file.toPath(),
                ("flink_version: \"1.18.0\"\ngenerated_at: \"2026-11-03 12:00:00\"\n"
                                + "connectors:\n  - kafka\n  - jdbc\n")
                        .getBytes(StandardCharsets.UTF_8));
        FlinkCapabilitiesLoader loader = new FlinkCapabilitiesLoader();
        setExternalPath(loader, file.getAbsolutePath());
        FlinkCapabilities capabilities = loader.load();
        Assert.assertEquals("1.18.0", capabilities.getFlinkVersion());
        Assert.assertEquals(2, capabilities.getConnectors().size());
        Assert.assertFalse(capabilities.getConnectors().contains("doris"));
    }

    @Test
    public void brokenExternalFileFallsBackToBaseline() throws Exception {
        File file = File.createTempFile("flink-capabilities-broken", ".yaml");
        file.deleteOnExit();
        Files.write(file.toPath(), "connectors: [ a : b : c".getBytes(StandardCharsets.UTF_8));
        FlinkCapabilitiesLoader loader = new FlinkCapabilitiesLoader();
        setExternalPath(loader, file.getAbsolutePath());
        Assert.assertTrue(loader.load().allConnectors().contains("kafka"));
    }

    private static void setExternalPath(FlinkCapabilitiesLoader loader, String path) throws Exception {
        java.lang.reflect.Field field = FlinkCapabilitiesLoader.class.getDeclaredField("externalPath");
        field.setAccessible(true);
        field.set(loader, path);
    }
}
