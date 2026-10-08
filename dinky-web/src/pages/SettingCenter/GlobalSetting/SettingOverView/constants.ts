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

export enum SettingConfigKeyEnum {
  DINKY = 'Dinky',
  FLINK = 'Flink',
  MAVEN = 'Maven',
  DOLPHIN_SCHEDULER = 'DolphinScheduler',
  LDAP = 'LDAP',
  LLM = 'LLM',
  KAFKA = 'Kafka',
  METRIC = 'Metric',
  RESOURCE = 'Resource',
  ENV = 'Env'
}

export enum ButtonFrontendType {
  BOOLEAN = 'boolean',
  OPTION = 'option'
}

/**
 * `sys.llm.settings.profiles` 的示例模板（配置中心 hover 帮助里展示）。
 *
 * <p>刻意选「多实例、各自不同 Key」的形态做示例——这是最容易配错的场景；同网关多模型可省略
 * `apiKey`（解析时回落到默认实例的密钥）。字段说明附在 JSON 之后，避免管理员逐个猜。
 */
export const LLM_PROFILES_JSON_TEMPLATE = `[
  {
    "id": "deepseek",
    "name": "DeepSeek",
    "baseUrl": "https://api.deepseek.com",
    "model": "deepseek-chat",
    "apiKey": "sk-deepseek-xxxxxxxx",
    "supportsTools": true
  },
  {
    "id": "qwen",
    "name": "通义千问",
    "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "model": "qwen-plus",
    "apiKey": "sk-qwen-yyyyyyyy",
    "supportsTools": true
  },
  {
    "id": "ollama",
    "name": "本地 Ollama",
    "baseUrl": "http://127.0.0.1:11434/v1",
    "model": "qwen2.5:7b",
    "apiKey": "ollama",
    "supportsTools": false
  }
]

id             唯一标识（必填，对话请求按它选实例）
name           下拉框显示名
baseUrl        网关地址（OpenAI 兼容）
model          模型名
apiKey         密钥；留空则复用默认实例的密钥
supportsTools  是否支持工具调用；false 时该实例退化为纯问答`;

/**
 * 需要额外 hover 帮助（问号）的配置项：key → 帮助内容（等宽字体、保留换行）。
 * 新增项直接往这里加，`GeneralConfig` 无需再改。
 */
export const CONFIG_KEY_HELP: Record<string, string> = {
  'sys.llm.settings.profiles': LLM_PROFILES_JSON_TEMPLATE
};
