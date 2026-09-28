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

package org.dinky.ai;

import java.util.List;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具的对外声明（对应 OpenAI function calling 的 tools 数组一项）。
 *
 * <p><b>description 必须写足边界</b>：模型只能通过这段文本判断该不该用、怎么用。含糊的
 * description 会让模型把「取样例数据」当成导出工具使用（调研报告 §7.1）。
 *
 * @since 2026/09/28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiToolSpec {

    private String name;

    private String description;

    /** JSON Schema（object） */
    private JSONObject parameters;

    /** 转成 OpenAI tools 数组的一项 */
    public JSONObject toToolsItem() {
        JSONObject function = new JSONObject();
        function.set("name", StrUtil.nullToEmpty(name));
        function.set("description", StrUtil.nullToEmpty(description));
        function.set("parameters", parameters == null ? new JSONObject() : parameters);
        JSONObject item = new JSONObject();
        item.set("type", "function");
        item.set("function", function);
        return item;
    }

    /** 拼一个 object 型 JSON Schema */
    public static JSONObject objectSchema(String description, JSONObject properties, List<String> required) {
        JSONObject schema = new JSONObject();
        schema.set("type", "object");
        if (StrUtil.isNotBlank(description)) {
            schema.set("description", description);
        }
        schema.set("properties", properties == null ? new JSONObject() : properties);
        if (required != null && !required.isEmpty()) {
            schema.set("required", new JSONArray(required));
        }
        schema.set("additionalProperties", false);
        return schema;
    }

    /** 拼一个 string 型属性 */
    public static JSONObject stringProperty(String description) {
        JSONObject property = new JSONObject();
        property.set("type", "string");
        property.set("description", description);
        return property;
    }

    /** 拼一个 integer 型属性 */
    public static JSONObject integerProperty(String description) {
        JSONObject property = new JSONObject();
        property.set("type", "integer");
        property.set("description", description);
        return property;
    }
}
