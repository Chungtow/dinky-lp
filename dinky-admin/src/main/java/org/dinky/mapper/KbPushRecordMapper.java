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

package org.dinky.mapper;

import org.dinky.data.model.KbPushRecord;
import org.dinky.mybatis.mapper.SuperMapper;

import org.apache.ibatis.annotations.Mapper;

/**
 * 推送台账 Mapper（{@code dinky_kb_push_record}）。
 *
 * <p>无自定义 SQL：查"某业务键推过哪些 doc_id"用 {@code LambdaQueryWrapper} 表达足够。
 *
 * @since 2026/10/28
 */
@Mapper
public interface KbPushRecordMapper extends SuperMapper<KbPushRecord> {}
