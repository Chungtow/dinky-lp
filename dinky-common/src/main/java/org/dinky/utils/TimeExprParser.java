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

package org.dinky.utils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight parser for DolphinScheduler time-placeholder expressions.
 * <p>
 * Supports a minimal subset of DS built-in expressions commonly used in task parameters:
 * <ul>
 *   <li>{@code $[yyyyMMdd-N]} — custom date format with offset (e.g. T-1 day)</li>
 *   <li>{@code $[yyyyMMdd+N]} — custom date format with positive offset</li>
 *   <li>{@code ${system.biz.curdate}} — DS business date (treated as current date here)</li>
 * </ul>
 * <p>
 * Note: this parser is used for <b>local testing within Dinky only</b>.
 * When a task is scheduled by DolphinScheduler, DS resolves the expressions
 * and passes resolved values via callback variables.
 */
public class TimeExprParser {

    // $[yyyyMMdd-1], $[yyyyMMdd+2], etc.
    private static final Pattern DATE_OFFSET_PATTERN =
            Pattern.compile("^\\$\\[(yyyyMMdd)([+-])(\\d+)\\]$");

    private static final String DS_BIZ_CURDATE = "${system.biz.curdate}";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TimeExprParser() {
    }

    /**
     * Parse a DS time-placeholder expression and return the resolved value.
     * If the expression is not recognized, returns the input unchanged.
     *
     * @param expr the expression string (e.g. "$[yyyyMMdd-1]")
     * @return resolved date/time value, or the original expression if unrecognized
     */
    public static String parse(String expr) {
        if (expr == null || expr.isEmpty()) {
            return expr;
        }

        // $[yyyyMMdd±N]
        Matcher matcher = DATE_OFFSET_PATTERN.matcher(expr);
        if (matcher.matches()) {
            String format = matcher.group(1);
            String sign = matcher.group(2);
            int offset = Integer.parseInt(matcher.group(3));
            if ("-".equals(sign)) {
                offset = -offset;
            }
            LocalDate target = LocalDate.now().plusDays(offset);
            if ("yyyyMMdd".equals(format)) {
                return target.format(YYYYMMDD);
            }
            return expr;
        }

        // ${system.biz.curdate}
        if (DS_BIZ_CURDATE.equals(expr)) {
            return LocalDate.now().format(YYYYMMDD);
        }

        // Not a recognized DS expression, return as-is
        return expr;
    }
}
