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

package org.dinky.resource.semantic;

/**
 * 语义项目内的一个条目（文件或目录）。
 *
 * <p>路径为「相对项目根」的相对路径，统一用 {@code /} 分隔（HDFS 上永远是 '/'，不随 OS 变化）。
 */
public class SemanticFileEntry {

    private final String path;
    private final boolean directory;
    private final long size;
    private final long modifiedAt;
    private final boolean readOnly;

    public SemanticFileEntry(String path, boolean directory, long size, long modifiedAt, boolean readOnly) {
        this.path = path;
        this.directory = directory;
        this.size = size;
        this.modifiedAt = modifiedAt;
        this.readOnly = readOnly;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
