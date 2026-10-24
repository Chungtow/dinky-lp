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

package org.dinky.context;

import java.util.Optional;

/** TenantContextHolder */
public class TenantContextHolder {

    private static final ThreadLocal<Object> TENANT_CONTEXT = new InheritableThreadLocal<>();
    private static final ThreadLocal<Boolean> IGNORE_TENANT = new InheritableThreadLocal<>();

    public static void ignoreTenant() {
        IGNORE_TENANT.set(true);
    }

    /**
     * 清除「忽略租户过滤」标记。
     *
     * <p>{@code IGNORE_TENANT} 是 {@link InheritableThreadLocal}，若不显式清除会污染同线程后续查询
     * （例如目录遍历后紧接着的 task / catalogue 查询会意外绕过租户隔离）。
     */
    public static void clearIgnoreTenant() {
        IGNORE_TENANT.remove();
    }

    public static boolean isIgnoreTenant() {
        return Optional.ofNullable(IGNORE_TENANT.get()).orElse(false);
    }

    public static void set(Object value) {
        TENANT_CONTEXT.set(value);
    }

    public static Object get() {
        return TENANT_CONTEXT.get();
    }

    public static void clear() {
        TENANT_CONTEXT.remove();
    }
}
