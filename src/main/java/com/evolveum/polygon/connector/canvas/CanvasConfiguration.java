/*
 * Copyright (C) 2024 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.polygon.connector.canvas;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

public class CanvasConfiguration extends AbstractConfiguration {

    private String baseUrl;
    private GuardedString authToken;
    private int accountId;
    private int studentRoleId;
    private int teacherRoleId;

    @ConfigurationProperty(
            required = true,
            displayMessageKey = "canvas.config.baseUrl",
            helpMessageKey = "canvas.config.baseUrl.help",
            order = 10)
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @ConfigurationProperty(
            required = true,
            displayMessageKey = "canvas.config.authToken",
            helpMessageKey = "canvas.config.authToken.help",
            confidential = true,
            order = 20)
    public GuardedString getAuthToken() {
        return authToken;
    }

    public void setAuthToken(GuardedString authToken) {
        this.authToken = authToken;
    }

    @ConfigurationProperty(
            required = true,
            displayMessageKey = "canvas.config.accountId",
            helpMessageKey = "canvas.config.accountId.help",
            order = 30)
    public int getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    @ConfigurationProperty(
            required = true,
            displayMessageKey = "canvas.config.studentRoleId",
            helpMessageKey = "canvas.config.studentRoleId.help",
            order = 40)
    public int getStudentRoleId() {
        return studentRoleId;
    }

    public void setStudentRoleId(int studentRoleId) {
        this.studentRoleId = studentRoleId;
    }

    @ConfigurationProperty(
            required = true,
            displayMessageKey = "canvas.config.teacherRoleId",
            helpMessageKey = "canvas.config.teacherRoleId.help",
            order = 50)
    public int getTeacherRoleId() {
        return teacherRoleId;
    }

    public void setTeacherRoleId(int teacherRoleId) {
        this.teacherRoleId = teacherRoleId;
    }

    @Override
    public void validate() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Canvas LMS URL (baseUrl) cannot be null or empty");
        }
        if (authToken == null) {
            throw new IllegalArgumentException("Authentication Token (authToken) cannot be null");
        }
        if (accountId == 0) {
            throw new IllegalArgumentException("Canvas account ID (accountId) must not be null/zero");
        }
        if (teacherRoleId == 0) {
            throw new IllegalArgumentException("Canvas course-level role ID for teacher (teacherRoleId) must not be null/zero");
        }
        if (studentRoleId == 0) {
            throw new IllegalArgumentException("Canvas course-level role ID for student (studentRoleId) must not be null/zero");
        }
    }
}
