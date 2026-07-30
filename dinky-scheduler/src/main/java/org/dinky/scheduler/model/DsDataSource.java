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

package org.dinky.scheduler.model;

import lombok.Data;

/**
 * DS DataSource model - maps to DS API /datasources/list response
 */
@Data
public class DsDataSource {

    private Long id;
    private String name;
    private String type;
    private String note;
    /**
     * connectionParams is JSON string containing jdbcUrl/user/password etc.
     * e.g. {"user":"hive","password":"","address":"jdbc:hive2://hivespark04:10000/gmall_dw","jdbcUrl":"jdbc:hive2://hivespark04:10000/gmall_dw","other":{}}
     */
    private String connectionParams;
}
