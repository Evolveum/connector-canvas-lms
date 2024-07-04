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

import static com.evolveum.polygon.connector.canvas.CanvasConnector.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.test.common.TestHelpers;
import org.identityconnectors.test.common.ToListResultsHandler;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * CSV export tool - prints courses and users with their course-ids.
 * May take quite a long time on more than hundreds of users.
 * <ol>
 * <li>Run Canvas somehow and get the API token for admin.</li>
 * <li>Provide the following arguments in this order:
 * <ol>
 *     <li>base URL (without /api/v1),</li>
 *     <li>auth token for admin user,</li>
 *     <li>account ID (typically 1),</li>
 *     <li>student role ID (likely 11),</li>
 *     <li>teacher role ID (likely 12).</li>
 * </ol>
 * </li>
 * <li>Observe the results after the tool finishes.</li>
 * </ol>
 */
public class CsvExport {

    private static List<ConnectorObject> users;
    private static List<ConnectorObject> courses;

    public static void main(String[] args) {
        CanvasConfiguration config = createCanvasConfiguration(args);
        ConnectorFacade connector = createConnectorFacade(config);
        listCourses(connector);
        listUsers(connector);
        connector.dispose();

        Map<String, JSONArray> idToLogins = userWithMultipleLogins(config);

        System.out.println("\nFound courses: " + courses.size());
        System.out.println("\n================");
        printCourseCsv();
        System.out.println("================");

        System.out.println("\nFound users: " + users.size());
        System.out.println("\n================");
        printUserCsv();
        System.out.println("================");

        if (!idToLogins.isEmpty()) {
            System.out.println("\nUsers with multiple logins:");
        }
        for (Map.Entry<String, JSONArray> entry : idToLogins.entrySet()) {
            List<String> logins = StreamSupport.stream(entry.getValue().spliterator(), false)
                    .map(o -> (JSONObject) o)
                    .map(o -> o.optString(UNIQUE_ID) + " (" + o.optInt("account_id") + ")")
                    .toList();
            System.out.println(entry.getKey() + " => " + logins);
        }
    }

    private static Map<String, JSONArray> userWithMultipleLogins(CanvasConfiguration config) {
        // low-level stuff using canvas client class directly
        Map<String, JSONArray> idToLogins = new HashMap<>(); // only for logins with non-1 count
        CanvasClient canvasClient = new CanvasClient(config);
        for (ConnectorObject object : users) {
            String id = object.getUid().getUidValue();
            CanvasResponse response = canvasClient.get("/users/" + id + "/logins");
            JSONArray logins = new JSONArray(response.body);
            if (logins.length() != 1) {
                idToLogins.put(id, logins);
            }
        }
        canvasClient.close();
        return idToLogins;
    }

    private static void printCourseCsv() {
        for (ConnectorObject object : courses) {
            System.out.println(
                    object.getUid().getUidValue() + ";" + // comment this to skip ID output
                            object.getName().getNameValue() + ";" +
                            getStringValue(object, COURSE_CODE) + ";" +
                            getStringValue(object, WORKFLOW_STATE) + ";" +
                            getStringValue(object, COURSE_UUID) + ";" +
                            getStringValue(object, COURSE_START_AT) + ";" +
                            getStringValue(object, COURSE_END_AT) + ";" +
                            getStringValue(object, COURSE_IS_PUBLIC) + ";" +
                            getStringValue(object, COURSE_IS_PUBLIC_TO_AUTH_USERS));
        }
    }

    private static void printUserCsv() {
        for (ConnectorObject object : users) {
            Attribute enabledAttr = object.getAttributeByName(OperationalAttributes.ENABLE_NAME);
            String status = enabledAttr != null && Boolean.TRUE.equals(AttributeUtil.getBooleanValue(enabledAttr))
                    ? "ENABLED" : "DISABLED";
            System.out.println(
                    object.getUid().getUidValue() + ";" + // comment this to skip ID output
                            object.getName().getNameValue() + ";" +
                            getStringValue(object, FULL_NAME) + ";" +
                            getStringValue(object, EMAIL) + ";" +
                            status + ";" +
                            getValues(object, STUDENT_COURSE_IDS) + ";" +
                            getValues(object, TEACHER_COURSE_IDS));
        }
    }

    private static ConnectorFacade createConnectorFacade(CanvasConfiguration config) {
        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        APIConfiguration impl = TestHelpers.createTestConfiguration(CanvasConnector.class, config);
        impl.getResultsHandlerConfiguration().setEnableAttributesToGetSearchResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableCaseInsensitiveFilter(false);
        impl.getResultsHandlerConfiguration().setEnableFilteredResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableNormalizingResultsHandler(false);
        impl.getResultsHandlerConfiguration().setFilteredResultsHandlerInValidationMode(false);
        return factory.newInstance(impl);
    }

    private static CanvasConfiguration createCanvasConfiguration(String[] args) {
        CanvasConfiguration config = new CanvasConfiguration();
        config.setBaseUrl(args[0]);
        config.setAuthToken(new GuardedString((args[1]).toCharArray()));
        config.setAccountId(Integer.parseInt(args[2]));
        config.setStudentRoleId(Integer.parseInt(args[3]));
        config.setTeacherRoleId(Integer.parseInt(args[4]));
        return config;
    }

    private static void listUsers(ConnectorFacade connector) {
        ToListResultsHandler resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_USER, null, resultsHandler, null);
        users = resultsHandler.getObjects();
    }

    private static void listCourses(ConnectorFacade connector) {
        ToListResultsHandler resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_COURSE, null, resultsHandler, null);
        courses = resultsHandler.getObjects();
    }

    private static List<Object> getValues(ConnectorObject object, String attrName) {
        return object.getAttributeByName(attrName).getValue();
    }

    private static Object getStringValue(ConnectorObject object, String attrName) {
        return AttributeUtil.getAsStringValue(object.getAttributeByName(attrName));
    }
}
