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

package org.dinky.ai.tools;

import org.junit.Assert;
import org.junit.Test;

/**
 * {@code describe_source} 对「整条 {@code @source/} 路径被塞进 datasource 参数」的容错（2026-11-04）。
 *
 * <p><b>为什么这个容错必须有</b>：用户写 {@code @source/meta-dolphin/dolphinscheduler}，
 * 模型经常把整串当数据源名传进来。旧实现按"单段白名单"直接拒绝（"含非法字符"），
 * 用户侧表现为模型绕一圈后放弃——而 {@code /} 不可能出现在合法数据源名里，
 * 拆开是无歧义的，拒绝纯属信息损失。
 *
 * <p><b>为什么断言"显式参数优先"</b>：{@code normalize} 只为补空，不能覆盖模型明确给出的
 * 库 / 表 / 字段，否则"传了 A 却按 B 查"比报错更难排查。
 *
 * @since 2026/11/04
 */
public class DescribeSourceToolNormalizeTest {

    @Test
    public void singleSegmentDatasourceIsUntouched() {
        String[] out = DescribeSourceTool.normalize("meta-dolphin", null, null, null);
        Assert.assertArrayEquals(new String[] {"meta-dolphin", null, null, null}, out);
    }

    @Test
    public void singleSegmentKeepsExplicitArgs() {
        String[] out = DescribeSourceTool.normalize("meta-dolphin", "dolphinscheduler", "t_ds_project", "code");
        Assert.assertArrayEquals(new String[] {"meta-dolphin", "dolphinscheduler", "t_ds_project", "code"}, out);
    }

    /** 线上实际触发的那一条：{@code @source/meta-dolphin/dolphinscheduler} 被整串当数据源名传入。 */
    @Test
    public void sourcePathIsSplitIntoSegments() {
        String[] out = DescribeSourceTool.normalize("meta-dolphin/dolphinscheduler", null, null, null);
        Assert.assertArrayEquals(new String[] {"meta-dolphin", "dolphinscheduler", null, null}, out);
    }

    @Test
    public void sourcePathWithTableIsSplit() {
        String[] out = DescribeSourceTool.normalize("meta-dolphin/dolphinscheduler/t_ds_project", null, null, null);
        Assert.assertArrayEquals(new String[] {"meta-dolphin", "dolphinscheduler", "t_ds_project", null}, out);
    }

    /** 第三段写成 {@code 表.字段}（{@code @source/} 的既有写法）同样要拆对。 */
    @Test
    public void tableDotColumnIsSplit() {
        String[] out = DescribeSourceTool.normalize("traccar-mysql/traccar/tc_positions.id", null, null, null);
        Assert.assertArrayEquals(new String[] {"traccar-mysql", "traccar", "tc_positions", "id"}, out);
    }

    /** 显式给出的 database 不被路径里的值覆盖。 */
    @Test
    public void explicitArgWins() {
        String[] out = DescribeSourceTool.normalize("meta-dolphin/lpdw", "dolphinscheduler", null, null);
        Assert.assertArrayEquals(new String[] {"meta-dolphin", "dolphinscheduler", null, null}, out);
    }

    /** 超过 {@code @source/} 的 4 段上限：返回 null，由调用方给出"路径不合法"的明确失败。 */
    @Test
    public void tooManySegmentsIsRejected() {
        Assert.assertNull(DescribeSourceTool.normalize("a/b/c/d/e", null, null, null));
    }

    /** 路径里出现白名单外字符（空格）：不猜，直接判为不合法。 */
    @Test
    public void illegalCharInsidePathIsRejected() {
        Assert.assertNull(DescribeSourceTool.normalize("meta dolphin/dolphinscheduler", null, null, null));
    }
}
