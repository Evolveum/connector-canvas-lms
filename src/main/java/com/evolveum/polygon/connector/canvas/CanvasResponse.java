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

import org.apache.http.client.methods.HttpRequestBase;

public class CanvasResponse {

    public final HttpRequestBase request; // for internal use, not shown in toString()

    public String body;
    public String nextPage; // null means no next page
    public String pageSize; // page size returned by API, may be lower than what we started with
    public int statusCode; // HTTP status code

    public CanvasResponse(HttpRequestBase request) {
        this.request = request;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append("CanvasResponse{").append("statusCode=").append(statusCode).append(", body='").append(bodyPreview(500)).append('\'');
        if (nextPage != null) {
            sb.append(", nextPage=").append(nextPage).append(", pageSize=").append(pageSize);
        }
        return sb.append('}').toString();
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isNotSuccess() {
        return !isSuccess();
    }

    public String bodyPreview(int maxChars) {
        if (body != null) {
            return body.length() > maxChars ? body.substring(0, maxChars) + "..." : body;
        }
        return null;
    }
}
