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

package org.dinky.ai.rule.check;

import org.dinky.ai.rule.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code check} 参数读取器（YAML → 各 check 器）。
 *
 * <p>参数全部来自配置文件，属于"半可信"输入：正则写错不能把整条链打断，
 * 因此 {@link #pattern(String)} 解析失败时返回 {@code null} 由调用方跳过并给出 detail。
 *
 * @since 2026/11/02
 */
@Slf4j
public final class CheckParams {

    private CheckParams() {}

    public static Map<String, Object> map(Rule rule) {
        return rule == null || rule.getCheck() == null ? Collections.emptyMap() : rule.getCheck();
    }

    public static String str(Rule rule, String key) {
        Object value = map(rule).get(key);
        return value == null ? null : Convert.toStr(value);
    }

    public static String str(Rule rule, String key, String defaultValue) {
        String value = str(rule, key);
        return StrUtil.isBlank(value) ? defaultValue : value;
    }

    public static boolean bool(Rule rule, String key, boolean defaultValue) {
        Object value = map(rule).get(key);
        return value == null ? defaultValue : Convert.toBool(value, defaultValue);
    }

    public static int integer(Rule rule, String key, int defaultValue) {
        Object value = map(rule).get(key);
        return value == null ? defaultValue : Convert.toInt(value, defaultValue);
    }

    /** 字符串列表；单值自动包成单元素列表 */
    public static List<String> strings(Rule rule, String key) {
        Object value = map(rule).get(key);
        List<String> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    list.add(Convert.toStr(item));
                }
            }
        } else {
            list.add(Convert.toStr(value));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Rule rule, String key) {
        Object value = map(rule).get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> children(Rule rule, String key) {
        Object value = map(rule).get(key);
        List<Map<String, Object>> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    list.add((Map<String, Object>) item);
                }
            }
        }
        return list;
    }

    /** 编译正则；非法返回 null（调用方跳过该条模式） */
    public static Pattern pattern(String regex) {
        if (StrUtil.isBlank(regex)) {
            return null;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            log.warn("AI rule: illegal regex in rule file ignored: {}", regex);
            return null;
        }
    }

    /** 文本是否命中任一正则（非法正则被忽略） */
    public static String firstMatch(List<String> regexes, String text) {
        if (text == null) {
            return null;
        }
        for (String regex : regexes) {
            Pattern pattern = pattern(regex);
            if (pattern != null && pattern.matcher(text).find()) {
                return regex;
            }
        }
        return null;
    }
}
