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
 * 平台画像加载：基线可用 + 覆盖整段替换 + 覆盖坏掉回退基线。
 *
 * <p>这份画像决定模型"敢不敢写 hive"，所以宁可测死也不能静默为空。
 *
 * @since 2026/11/03
 */
public class PlatformProfileLoaderTest {

    @Test
    public void baselineProvidesConnectorWhitelist() {
        PlatformProfile profile = new PlatformProfileLoader().load();
        Assert.assertTrue(profile.getConnectors().contains("kafka"));
        Assert.assertTrue(profile.getConnectors().contains("jdbc"));
        Assert.assertFalse("基线不得包含本集群未安装的 connector", profile.getConnectors().contains("hive"));
        Assert.assertFalse(profile.getConventions().isEmpty());
    }

    @Test
    public void overlayReplacesListSections() throws Exception {
        File file = File.createTempFile("platform-profile-overlay", ".yaml");
        file.deleteOnExit();
        Files.write(
                file.toPath(), ("connectors: [kafka]\nconventions:\n  - '覆盖后的口径'\n").getBytes(StandardCharsets.UTF_8));
        PlatformProfileLoader loader = new PlatformProfileLoader();
        setExternalPath(loader, file.getAbsolutePath());
        PlatformProfile profile = loader.load();
        Assert.assertEquals(1, profile.getConnectors().size());
        Assert.assertEquals("kafka", profile.getConnectors().get(0));
        Assert.assertEquals(1, profile.getConventions().size());
        Assert.assertEquals("覆盖后的口径", profile.getConventions().get(0));
        // 未在覆盖文件里出现的段落沿用基线
        Assert.assertFalse(profile.getPipelines().isEmpty());
    }

    @Test
    public void brokenOverlayFallsBackToBaseline() throws Exception {
        File file = File.createTempFile("platform-profile-broken", ".yaml");
        file.deleteOnExit();
        Files.write(file.toPath(), "connectors: [ a : b : c".getBytes(StandardCharsets.UTF_8));
        PlatformProfileLoader loader = new PlatformProfileLoader();
        setExternalPath(loader, file.getAbsolutePath());
        Assert.assertTrue(loader.load().getConnectors().contains("jdbc"));
    }

    private static void setExternalPath(PlatformProfileLoader loader, String path) throws Exception {
        java.lang.reflect.Field field = PlatformProfileLoader.class.getDeclaredField("externalPath");
        field.setAccessible(true);
        field.set(loader, path);
    }
}
