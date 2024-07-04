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

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.common.exceptions.ConnectorIOException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Canvas LMS connector.
 * <p>
 * This does not implement PoolableConector as it would call checkAlive often resulting in additional
 * call for every operation. Instead, we try the operation REST call directly and handle problems there.
 */
@ConnectorClass(
        displayNameKey = "canvas.connector.name",
        configurationClass = CanvasConfiguration.class)
public class CanvasConnector implements Connector, TestOp, SchemaOp, SearchOp<CanvasFilter>, CreateOp, DeleteOp, UpdateDeltaOp {

    private static final Log LOG = Log.getLog(CanvasConnector.class);

    // API paths
    private static final String API_USER_DETAILS = "/users/"; // followed by id
    private static final String API_COURSES_DETAILS = "/courses/"; // followed by id
    private static final String API_ACCOUNTS = "/accounts/";

    /*
    Enrollment states as used in REST API, see state[] parameter here:
    https://canvas.instructure.com/doc/api/enrollments.html#method.enrollments_api.index
    */
    private static final String ENROLLMENT_STATE_ACTIVE = "active";
    private static final String ENROLLMENT_STATE_INVITED = "invited";
    private static final String ENROLLMENT_STATE_CREATION_PENDING = "creation_pending";
    private static final String ENROLLMENT_STATE_DELETED = "deleted";
    private static final String ENROLLMENT_STATE_REJECTED = "rejected";
    private static final String ENROLLMENT_STATE_COMPLETED = "completed";
    private static final String ENROLLMENT_STATE_INACTIVE = "inactive";

    /** State for created enrollment (normal default is "invited", but we use "active"). */
    public static final String CREATED_ENROLLMENT_STATE = ENROLLMENT_STATE_ACTIVE;

    /* JSON attributes - user - example object:
    {
      "id": 1,
      "name": "admin@evolveum.com",
      "created_at": "2022-03-15T09:20:18-06:00",
      "sortable_name": "admin@evolveum.com",
      "short_name": "admin@evolveum.com",
      "sis_user_id": null,
      "integration_id": null,
      "sis_import_id": null,
      "login_id": "admin@evolveum.com",
      "last_name": "",
      "first_name": "admin@evolveum.com",
      "email": "admin@evolveum.com",
      "locale": null,
      "effective_locale": "en",
      "permissions": {
        "can_update_name": true,
        "can_update_avatar": false,
        "limit_parent_app_web_access": false
      }
    }    
    */
    public static final String ID = "id"; // both user/course ID, Uid.NAME used in ConnectorObject

    /** Login ID also appears as pseudonym/unique_id when creating user and can be renamed on "login" endpoint. */
    public static final String LOGIN_ID = "login_id"; // mapped to Name.NAME
    public static final String UNIQUE_ID = "unique_id"; // alternative for login_id for some API calls

    public static final String NAME = "name"; // REST property, mapped to FULL_NAME connector attribute
    public static final String CREATED_AT = "created_at";
    public static final String SORTABLE_NAME = "sortable_name";
    public static final String SHORT_NAME = "short_name";
    public static final String EMAIL = "email";

    /** Property on login that decides the enabled/disabled state. */
    public static final String WORKFLOW_STATE = "workflow_state";
    public static final String WORKFLOW_STATE_ACTIVE = "active";
    public static final String WORKFLOW_STATE_SUSPENDED = "suspended";

    /** Property on login that determines the authentication provider - may be empty. */
    public static final String AUTHENTICATION_PROVIDER_ID = "authentication_provider_id";

    public static final String PASSWORD = "password";

    // CONNECTOR attribute names, when different from REST property names or additional ones

    public static final String FULL_NAME = "full_name"; // connector attribute for REST "name"

    public static final String TEACHER_COURSE_IDS = "teacher_course_ids";
    public static final String STUDENT_COURSE_IDS = "student_course_ids";

    /** Names of updatable user attributes. */
    public static final Set<String> USER_UPDATABLE_PROPERTIES =
            Set.of(FULL_NAME, SHORT_NAME, SORTABLE_NAME, EMAIL);
    /** Names of updatable user-login attributes. */
    public static final Set<String> USER_UPDATABLE_LOGIN_PROPERTIES =
            Set.of(Name.NAME, AUTHENTICATION_PROVIDER_ID,
                    OperationalAttributes.ENABLE_NAME, OperationalAttributes.PASSWORD_NAME);
    public static final Set<String> USER_ENROLLMENT_ID_ATTRS =
            Set.of(STUDENT_COURSE_IDS, TEACHER_COURSE_IDS);

    /* JSON attributes - course - example:
    {
      "id": 1,
      "name": "MidPoint Deployment Fundamentals Training",
      "account_id": 3,
      "uuid": "rBOSGxzfBSCHggW6uwbPlG6unxX2pocY9npSXwNL",
      "start_at": "2024-03-09T00:24:47Z",
      ...
      "course_code": "MID-101",
      ...
      "enrollments": [
        {
          "type": "designer",
          "role": "DesignerEnrollment",
          "role_id": 14,
          "user_id": 1,
          "enrollment_state": "active",
          "limit_privileges_to_course_section": false
        }
      ],
      "workflow_state": "available",
      "restrict_enrollments_to_course_dates": true,
      "overridden_course_visibility": ""
    }
    */
    public static final String COURSE_NAME = "name"; // not unique
    public static final String COURSE_CODE = "course_code"; // not unique
    public static final String COURSE_UUID = "uuid"; // unique
    public static final String COURSE_START_AT = "start_at";
    public static final String COURSE_END_AT = "end_at";
    /** Set to true if course is public to both authenticated and unauthenticated users. */
    public static final String COURSE_IS_PUBLIC = "is_public";
    /** Set to true if course is public only to authenticated users. */
    public static final String COURSE_IS_PUBLIC_TO_AUTH_USERS = "is_public_to_auth_users";

    public static final String TEACHER_IDS = "teacher_ids";
    public static final String STUDENT_IDS = "student_ids";

    public static final int LIST_MAX_ITEMS = 10000;
    public static final int DUPLICATE_MAX_PAGES = 100;
    public static final int ENROLLMENTS_MAX_PAGES = 100;

    // Using predefined ACCOUNT and GROUP OCs, declaring it here for easy change if desired.
    public static final ObjectClass OBJECT_CLASS_COURSE = ObjectClass.GROUP;
    public static final ObjectClass OBJECT_CLASS_USER = ObjectClass.ACCOUNT;

    private CanvasConfiguration configuration;
    private CanvasClient canvasClient;
    private String apiAccountUsers; // without /api/v1 prefix
    private String apiAccountCourses; // without /api/v1 prefix

    @Override
    public void init(Configuration configuration) {
        this.configuration = (CanvasConfiguration) configuration;
        this.canvasClient = new CanvasClient(this.configuration);

        this.apiAccountUsers = API_ACCOUNTS + this.configuration.getAccountId() + "/users";
        this.apiAccountCourses = API_ACCOUNTS + this.configuration.getAccountId() + "/courses";
    }

    @Override
    public void test() {
        LOG.ok("test - reading admin user");
        canvasClient.get(API_USER_DETAILS + "/self");
    }

    @Override
    public FilterTranslator<CanvasFilter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        return new CanvasFilterTranslator();
    }

    @Override
    public void executeQuery(ObjectClass objectClass, CanvasFilter filter, ResultsHandler resultHandler, OperationOptions options) {
        LOG.info(">>> executeQuery: {0}, {1}, {2}, {3}", objectClass, filter, resultHandler, options);
        checkNotNull(objectClass, "objectClass");
        checkNotNull(resultHandler, "resultHandler");

        try {
            if (filter != null && filter.idEqualTo != null) {
                getById(objectClass, filter.idEqualTo, resultHandler, options);
            } else if (filter != null && filter.loginEqualTo != null) {
                JSONObject userByLogin = findUserByLogin(filter.loginEqualTo);
                if (userByLogin != null) {
                    resultHandler.handle(createAccountConnectorObject(userByLogin));
                }
            } else {
                listAll(objectClass, resultHandler, options);
            }
            LOG.ok(">>> executeQuery finished");
        } catch (Exception e) {
            handleException(e, "executeQuery failed, objectClass = " + objectClass + ", filter = "
                    + filter + ", options = " + options + ", reason: " + e);
        }
    }

    private void checkNotNull(Object paramValue, String paramName) {
        if (paramValue == null) {
            throw new IllegalArgumentException("Parameter '" + paramName + "' must not be null");
        }
    }

    private void handleException(Exception e, String message) {
        // use only if needed, normally ConnId reports exceptions with stacktrace as well
//        LOG.ok(e, "handling exception, message: {0}", message);

        if (e instanceof ConnectorException) {
            throw (ConnectorException) e; // already handled, just rethrow
        }
        if (e instanceof IOException) {
            throw new ConnectorIOException(message, e);
        }
        throw new ConnectorException(message, e);
    }

    private void getById(ObjectClass objectClass, String id, ResultsHandler handler, OperationOptions options) {
        if (objectClass.equals(OBJECT_CLASS_USER)) {
            CanvasResponse response = canvasClient.get(API_USER_DETAILS + id,
                    handleNotFoundAndNotSuccess(id, objectClass));
            JSONObject detailJson = new JSONObject(response.body);
            // Deleted users don't have login_id
            if (detailJson.has(LOGIN_ID)) {
                handler.handle(createAccountConnectorObject(detailJson));
            } else {
                throw new UnknownUidException(new Uid(id), objectClass);
            }
        } else if (objectClass.equals(OBJECT_CLASS_COURSE)) {
            CanvasResponse response = canvasClient.get(API_COURSES_DETAILS + id,
                    handleNotFoundAndNotSuccess(id, objectClass));
            JSONObject detailJson = new JSONObject(response.body);
            handler.handle(createGroupConnectorObject(detailJson));
        } else {
            throw new IllegalArgumentException("Object class '" + objectClass + "' not supported.");
        }
    }

    private void throwIf(boolean condition, Supplier<ConnectorException> throwableSupplier) throws ConnectorException {
        if (condition) {
            throw throwableSupplier.get();
        }
    }

    private void listAll(ObjectClass objectClass, ResultsHandler handler, OperationOptions options) {
        Function<JSONObject, ConnectorObject> connectorObjectFunction;
        String apiPath;
        String additionalParams = "";
        if (objectClass.equals(OBJECT_CLASS_USER)) {
            connectorObjectFunction = json -> createAccountConnectorObject(json);
            apiPath = apiAccountUsers;
            additionalParams = "include[]=email&"; // paging will follow, hence &
        } else if (objectClass.equals(OBJECT_CLASS_COURSE)) {
            connectorObjectFunction = this::createGroupConnectorObject;
            apiPath = apiAccountCourses;
        } else {
            throw new IllegalArgumentException("Object class '" + objectClass + "' not supported.");
        }

        Pagination pagination = Pagination.from(options);
        String page = String.valueOf(pagination.page);
        String pageSize = String.valueOf(pagination.pageSize);
        int limit = pagination.limit;
        int skip = pagination.skip;
        while (limit > 0) {
            CanvasResponse response = canvasClient.get(
                    apiPath + "?" + additionalParams + "page=" + page + "&per_page=" + pageSize);
            JSONArray jsonArrayResults = new JSONArray(response.body);
            for (Object o : jsonArrayResults) {
                if (skip > 0) {
                    // this happens when page is not perfectly aligned (page size and offset)
                    skip--;
                    continue;
                }
                JSONObject json = (JSONObject) o;
                ConnectorObject object = connectorObjectFunction.apply(json);
                if (!handler.handle(object)) {
                    return;
                }
                limit--;
                // if limit == 0 we will handle the last object and finish
                if (limit <= 0) {
                    return;
                }
            }

            page = response.nextPage;
            if (page == null) {
                return;
            }
            pageSize = response.pageSize;
        }
    }

    private static final Set<AttributeInfo.Flags> ATTR_OPTIONS_REQUIRED = Set.of(AttributeInfo.Flags.REQUIRED);

    /**
     * Returning courses is a heavy operation (N+1 problem) but non-default caused more problems than good.
     * So instead, we read it all for each user, no problem for "getObject" but slows down list.
     */
    private static final Set<AttributeInfo.Flags> ATTR_OPTIONS_COURSE_LIST_ON_USER =
            Set.of(AttributeInfo.Flags.MULTIVALUED);
    private static final Set<AttributeInfo.Flags> NOT_UPDATABLE_AND_NOT_CREATABLE =
            Set.of(AttributeInfo.Flags.NOT_UPDATEABLE, AttributeInfo.Flags.NOT_CREATABLE);

    @Override
    public Schema schema() {
        SchemaBuilder schemaBuilder = new SchemaBuilder(CanvasConnector.class);

        ObjectClassInfoBuilder userClassBuilder = new ObjectClassInfoBuilder().setType(OBJECT_CLASS_USER.getObjectClassValue())
                // DO NOT specify "nativeName" for Uid and Name attributes, it causes problems for association configuration.
                .addAttributeInfo(new AttributeInfoBuilder(Uid.NAME, String.class)
                        .setFlags(NOT_UPDATABLE_AND_NOT_CREATABLE)
                        .build())
                .addAttributeInfo(new AttributeInfoBuilder(Name.NAME, String.class)
                        .setFlags(ATTR_OPTIONS_REQUIRED)
                        .build())
                .addAttributeInfo(AttributeInfoBuilder.build(FULL_NAME, String.class, ATTR_OPTIONS_REQUIRED))
                .addAttributeInfo(AttributeInfoBuilder.build(CREATED_AT, ZonedDateTime.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(EMAIL, String.class))
                .addAttributeInfo(AttributeInfoBuilder.build(SORTABLE_NAME, String.class))
                .addAttributeInfo(AttributeInfoBuilder.build(SHORT_NAME, String.class))
                .addAttributeInfo(AttributeInfoBuilder.build(AUTHENTICATION_PROVIDER_ID, Integer.class))
                .addAttributeInfo(AttributeInfoBuilder.build(OperationalAttributes.ENABLE_NAME, Boolean.class))
                .addAttributeInfo(OperationalAttributeInfos.PASSWORD)
                .addAttributeInfo(AttributeInfoBuilder.build(STUDENT_COURSE_IDS, String.class, ATTR_OPTIONS_COURSE_LIST_ON_USER))
                .addAttributeInfo(AttributeInfoBuilder.build(TEACHER_COURSE_IDS, String.class, ATTR_OPTIONS_COURSE_LIST_ON_USER));
        // Ignoring: sis_user_id, integration_id, sis_import_id, locale, effective_locale, permissions (and more...)
        schemaBuilder.defineObjectClass(userClassBuilder.build());

        // Courses are not managed in midPoint, only in Canvas - create/delete is not supported.
        // But students and teachers are managed on this object, use updateDelta to change that (enrollments).
        schemaBuilder.defineObjectClass(new ObjectClassInfoBuilder().setType(OBJECT_CLASS_COURSE.getObjectClassValue())
                .addAttributeInfo(new AttributeInfoBuilder(Uid.NAME, String.class)
                        .setFlags(NOT_UPDATABLE_AND_NOT_CREATABLE)
                        .build())
                .addAttributeInfo(AttributeInfoBuilder.build(Name.NAME, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_CODE, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(WORKFLOW_STATE, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_UUID, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_START_AT, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_END_AT, String.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_IS_PUBLIC, Boolean.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(COURSE_IS_PUBLIC_TO_AUTH_USERS, Boolean.class, NOT_UPDATABLE_AND_NOT_CREATABLE))
                .addAttributeInfo(AttributeInfoBuilder.build(STUDENT_IDS, String.class, Set.of(AttributeInfo.Flags.MULTIVALUED)))
                .addAttributeInfo(AttributeInfoBuilder.build(TEACHER_IDS, String.class, Set.of(AttributeInfo.Flags.MULTIVALUED)))
                .build());

        schemaBuilder.defineOperationOption(OperationOptionInfoBuilder.buildPageSize(), SearchOp.class);
        return schemaBuilder.build();
    }

    /**
     * Creates "account" from API user object.
     * This is not for Canvas account objects which we don't list.
     * This method also fetches the enrollments for the user.
     */
    private ConnectorObject createAccountConnectorObject(JSONObject json) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(OBJECT_CLASS_USER);
        int userId = json.getInt(ID);
        Uid userUid = new Uid(String.valueOf(userId));
        builder.setUid(userUid);
        builder.setName(new Name(json.getString(LOGIN_ID)));
        // Other values are read with optional get that allows for missing key:
        builder.addAttribute(AttributeBuilder.build(FULL_NAME, json.optString(NAME)));
        builder.addAttribute(AttributeBuilder.build(EMAIL, json.optString(EMAIL)));
        String createdAt = json.optString(CREATED_AT);
        if (createdAt != null) {
            builder.addAttribute(AttributeBuilder.build(CREATED_AT, ZonedDateTime.parse(createdAt)));
        }
        builder.addAttribute(AttributeBuilder.build(SORTABLE_NAME, json.optString(SORTABLE_NAME)));
        builder.addAttribute(AttributeBuilder.build(SHORT_NAME, json.optString(SHORT_NAME)));
        // We ignore/don't use: INTEGRATION_ID, SIS_USER_ID, SIS_IMPORT_ID and some other

        CourseEnrollments enrollments = fetchUserEnrollments(userUid.getUidValue());
        builder.addAttribute(AttributeBuilder.build(STUDENT_COURSE_IDS,
                enrollments.getCurrentStudentCourseIds()));
        builder.addAttribute(AttributeBuilder.build(TEACHER_COURSE_IDS,
                enrollments.getCurrentTeacherCourseIds()));

        fetchLoginInfoForUser(userUid, builder);
        return builder.build();
    }

    private void fetchLoginInfoForUser(Uid userUid, ConnectorObjectBuilder builder) {
        JSONObject loginInfo = getUserLoginInfo(userUid);
        if (loginInfo != null) {
            builder.addAttribute(
                    AttributeBuilder.build(OperationalAttributes.ENABLE_NAME,
                            WORKFLOW_STATE_ACTIVE.equals(loginInfo.getString(WORKFLOW_STATE))));
            if (loginInfo.has(AUTHENTICATION_PROVIDER_ID)) {
                builder.addAttribute(
                        AttributeBuilder.build(AUTHENTICATION_PROVIDER_ID,
                                loginInfo.isNull(AUTHENTICATION_PROVIDER_ID)
                                        ? null : loginInfo.optInt(AUTHENTICATION_PROVIDER_ID)));
            }
        }
    }

    private ConnectorObject createGroupConnectorObject(JSONObject json) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(OBJECT_CLASS_COURSE);
        int courseId = json.getInt(ID);
        builder.setUid(new Uid(String.valueOf(courseId)));
        builder.setName(new Name(json.getString(COURSE_NAME)));
        builder.addAttribute(COURSE_CODE, json.getString(COURSE_CODE));
        builder.addAttribute(WORKFLOW_STATE, json.optString(WORKFLOW_STATE));
        builder.addAttribute(COURSE_UUID, json.getString(COURSE_UUID));
        builder.addAttribute(COURSE_START_AT, json.optString(COURSE_START_AT));
        builder.addAttribute(COURSE_END_AT, json.optString(COURSE_END_AT));
        if (json.has(COURSE_IS_PUBLIC)) {
            builder.addAttribute(COURSE_IS_PUBLIC, json.optBoolean(COURSE_IS_PUBLIC));
        }
        if (json.has(COURSE_IS_PUBLIC_TO_AUTH_USERS)) {
            builder.addAttribute(COURSE_IS_PUBLIC_TO_AUTH_USERS, json.optBoolean(COURSE_IS_PUBLIC_TO_AUTH_USERS));
        }

        CourseEnrollments courseEnrollments = fetchCourseEnrollments(String.valueOf(courseId));
        builder.addAttribute(AttributeBuilder.build(STUDENT_IDS, courseEnrollments.getCurrentStudentIds()));
        builder.addAttribute(AttributeBuilder.build(TEACHER_IDS, courseEnrollments.getCurrentTeacherIds()));

        return builder.build();
    }

    private CourseEnrollments fetchUserEnrollments(String userId) {
        return fetchEnrollments(API_USER_DETAILS + userId);
    }

    private CourseEnrollments fetchCourseEnrollments(String courseId) {
        return fetchEnrollments(API_COURSES_DETAILS + courseId);
    }

    private CourseEnrollments fetchEnrollments(String restCallPrefix) {
        CourseEnrollments courseEnrollments = new CourseEnrollments();
        String page = "1";
        String pageSize = "100";
        for (int sanity = ENROLLMENTS_MAX_PAGES; sanity > 0; sanity--) {
            CanvasResponse response = canvasClient.get(
                    restCallPrefix + "/enrollments?state[]=active&state[]=invited"
                            + "&state[]=creation_pending&state[]=rejected"
                            + "&state[]=completed&state[]=inactive&page=" + page + "&per_page=" + pageSize);
            JSONArray jsonArrayResults = new JSONArray(response.body);
            for (Object o : jsonArrayResults) {
                JSONObject json = (JSONObject) o;
                if (json.optInt("root_account_id") == configuration.getAccountId()) {
                    int courseRoleId = json.optInt("role_id");
                    if (courseRoleId == configuration.getStudentRoleId()) {
                        courseEnrollments.addStudentEnrollment(json);
                    } else if (courseRoleId == configuration.getTeacherRoleId()) {
                        courseEnrollments.addTeacherEnrollment(json);
                    }
                }
            }
            if (response.nextPage == null) {
                break;
            }

            page = response.nextPage;
            pageSize = response.pageSize;
        }
        return courseEnrollments;
    }

    /*
    https://canvas.instructure.com/doc/api/users.html#method.users.create
    Create user REST example:
    curl -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/accounts/1/users" \
      -X POST -H "Content-Type: application/json" --data @- <<'EOF'
    {
      "user": {
        "name": "User Test3",
        "time_zone": "Europe/Bratislava", # not provided now
        "locale": "sk",                   # not provided now
        "skip_registration": true,
        "send_confirmation": true,
        "sortable_name": "Test3, User"    # generated by Canvas
      },
      "communication_channel": {
        "type": "mail",
        "address": "test3@example.com"
      },
      "pseudonym": {
        "unique_id": "test3"
      }
    }
    EOF
    */
    @Override
    public Uid create(ObjectClass objectClass, Set<Attribute> createAttributes, OperationOptions options) {
        LOG.info(">>> create: {0}, {1}, {2}", objectClass, createAttributes, options);
        checkNotNull(objectClass, "objectClass");
        checkNotNull(createAttributes, "createAttributes");

        try {
            if (OBJECT_CLASS_USER.equals(objectClass)) {
                JSONObject jsonObject = new JSONObject();
                Map<String, Object> userMap = new HashMap<>();

                String login = AttributeUtil.getStringValue(AttributeUtil.find(Name.NAME, createAttributes));
                JSONObject loginInfo = new JSONObject();
                loginInfo.put(UNIQUE_ID, login);
                Attribute authProviderAttr = AttributeUtil.find(AUTHENTICATION_PROVIDER_ID, createAttributes);
                if (authProviderAttr != null) {
                    loginInfo.put(AUTHENTICATION_PROVIDER_ID, AttributeUtil.getIntegerValue(authProviderAttr));
                }
                Attribute passwordAttr = AttributeUtil.find(OperationalAttributes.PASSWORD_NAME, createAttributes);
                if (passwordAttr != null) {
                    AttributeUtil.getGuardedStringValue(passwordAttr).access(
                            chars -> loginInfo.put(PASSWORD, new String(chars)));
                }
                // enabled/disabled can't be set for creation REST call
                jsonObject.put("pseudonym", loginInfo);

                userMap.put(NAME, AttributeUtil.getStringValue(AttributeUtil.find(FULL_NAME, createAttributes)));
                Attribute shortNameAttr = AttributeUtil.find(SHORT_NAME, createAttributes);
                if (shortNameAttr != null) {
                    userMap.put(SHORT_NAME, AttributeUtil.getStringValue(shortNameAttr));
                }
                Attribute sortableNameAttr = AttributeUtil.find(SORTABLE_NAME, createAttributes);
                if (sortableNameAttr != null) {
                    userMap.put(SORTABLE_NAME, AttributeUtil.getStringValue(sortableNameAttr));
                }
                userMap.put("skip_registration", true);
                userMap.put("send_confirmation", true);
                jsonObject.put("user", userMap); // must be called after userMap is filled

                jsonObject.put("communication_channel", Map.of("type", "mail",
                        "address", AttributeUtil.getStringValue(AttributeUtil.find(EMAIL, createAttributes))));

                CanvasResponse response = canvasClient.postJson(apiAccountUsers, jsonObject.toString(),
                        r -> {
                            if (r.statusCode == 400 && r.body.contains("ID already in use for this account")) {
                                handleDuplicate(login);
                            }
                        },
                        CanvasClient::throwIfNotSuccess);

                int userId = new JSONObject(response.body).getInt(ID);
                String uidString = String.valueOf(userId);
                LOG.ok("Created new object with the UID: {0}", uidString);

                Uid uid = new Uid(uidString);
                // Disable can't be provided in create REST call, so we cover it here:
                Attribute enabledAttr = AttributeUtil.find(OperationalAttributes.ENABLE_NAME, createAttributes);
                if (enabledAttr != null && Boolean.FALSE.equals(AttributeUtil.getBooleanValue(enabledAttr))) {
                    updateUserLoginInfo(uid, Map.of(OperationalAttributes.ENABLE_NAME, false));
                }
                // If some courses are set right away (impatient are we?) let's handle it here:
                Attribute studentCourseIdsAttr = AttributeUtil.find(STUDENT_COURSE_IDS, createAttributes);
                if (studentCourseIdsAttr != null) {
                    for (Object courseId : studentCourseIdsAttr.getValue()) {
                        createEnrollment((String) courseId, uidString, configuration.getStudentRoleId());
                    }
                }
                Attribute teacherCourseIdsAttr = AttributeUtil.find(TEACHER_COURSE_IDS, createAttributes);
                if (teacherCourseIdsAttr != null) {
                    for (Object courseId : teacherCourseIdsAttr.getValue()) {
                        createEnrollment((String) courseId, uidString, configuration.getTeacherRoleId());
                    }
                }

                return uid;
            } else if (OBJECT_CLASS_COURSE.equals(objectClass)) {
                throw new UnsupportedOperationException("Unknown object class " + objectClass);
            } else {
                throw new UnsupportedOperationException("Unknown object class " + objectClass);
            }
        } catch (Exception e) {
            handleException(e, "Couldn't create object " + objectClass + " with attributes "
                    + createAttributes + ", reason: " + e.getMessage());
        }
        return null; // not really reachable
    }

    private void handleDuplicate(String login) {
        JSONObject duplicateUser = findUserByLogin(login);
        if (duplicateUser == null) {
            throw new AlreadyExistsException("Account with login '" + login + "' already exists - but UID is unknown?!");
        }

        String uid = duplicateUser.opt(ID).toString();
        throw new AlreadyExistsException("Account with login '" + login + "' already exists with UID: " + uid)
                .initUid(new Uid(uid));
    }

    private JSONObject findUserByLogin(String login) {
        String page = "1";
        String pageSize = "100";
        for (int sanity = DUPLICATE_MAX_PAGES; sanity > 0; sanity--) {
            // search_term=<login> is not helpful, because login_id is not searched for by Canvas REST
            CanvasResponse response = canvasClient.get(
                    apiAccountUsers + "?page=" + page + "&per_page=" + pageSize);
            JSONArray jsonArrayResults = new JSONArray(response.body);
            for (Object o : jsonArrayResults) {
                JSONObject json = (JSONObject) o;
                if (login.equals(json.optString(LOGIN_ID))) {
                    return json;
                }
            }
            if (response.nextPage == null) {
                break;
            }

            page = response.nextPage;
            pageSize = response.pageSize;
        }
        return null;
    }

    /*
    https://canvas.instructure.com/doc/api/accounts.html#method.accounts.remove_user
    */
    @Override
    public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
        LOG.info(">>> delete: {0}, {1}, {2}", objectClass, uid, options);
        checkNotNull(objectClass, "objectClass");
        checkNotNull(uid, "uid");

        try {
            if (OBJECT_CLASS_USER.equals(objectClass)) {
                canvasClient.delete(apiAccountUsers + "/" + uid.getUidValue(),
                        handleNotFoundAndNotSuccess(uid, objectClass));
            } else if (OBJECT_CLASS_COURSE.equals(objectClass)) {
                throw new UnsupportedOperationException("Delete not supported for object class " + objectClass);
            } else {
                throw new UnsupportedOperationException("Unknown object class " + objectClass);
            }
            LOG.ok("The object with the UID {0} was deleted by the connector instance.", uid);
        } catch (Exception ex) {
            handleException(ex, "Couldn't delete " + objectClass + " with uid " + uid + ", reason: " + ex);
        }
    }

    /*
    Update uses edit user endpoint PUT /api/v1/users/:id, documented here:
    https://canvas.instructure.com/doc/api/users.html#method.users.update
    Login ID must be updated with PUT /api/v1/accounts/:account_id/logins/:id, see:
    https://canvas.instructure.com/doc/api/logins.html#method.pseudonyms.update
    */
    @Override
    public Set<AttributeDelta> updateDelta(ObjectClass objectClass,
            Uid uid, Set<AttributeDelta> modifications, OperationOptions options) {
        LOG.info(">>> updateDelta: {0}, {1}, {2}, {3}", objectClass, uid, modifications, options);
        checkNotNull(objectClass, "objectClass");
        checkNotNull(uid, "uid");

        Set<Attribute> attrsToReplace = new HashSet<>();
        Set<Attribute> attrsToAdd = new HashSet<>();
        Set<Attribute> attrsToRemove = new HashSet<>();

        for (AttributeDelta delta : modifications) {
            List<Object> valuesToReplace = delta.getValuesToReplace();
            if (valuesToReplace != null) {
                attrsToReplace.add(AttributeBuilder.build(delta.getName(), valuesToReplace));
            } else {
                List<Object> valuesToRemove = delta.getValuesToRemove();
                List<Object> valuesToAdd = delta.getValuesToAdd();

                // No else here, both may apply!
                if (valuesToRemove != null) {
                    attrsToRemove.add(AttributeBuilder.build(delta.getName(), valuesToRemove));
                }
                if (valuesToAdd != null) {
                    attrsToAdd.add(AttributeBuilder.build(delta.getName(), valuesToAdd));
                }
            }
        }

        try {
            if (objectClass.equals(OBJECT_CLASS_USER)) {
                updateUser(uid, attrsToReplace, attrsToAdd, attrsToRemove);
            } else if (objectClass.equals(OBJECT_CLASS_COURSE)) {
                updateCourse(uid, attrsToReplace, attrsToAdd, attrsToRemove);
            }
        } catch (Exception e) {
            handleException(e, "Couldn't modify attribute values from object " + objectClass +
                    " with uid " + uid + " , reason: " + e);
        }

        return Set.of(); // no additional changes
    }

    private void updateUser(Uid uid, Set<Attribute> attrsToReplace, Set<Attribute> attrsToAdd, Set<Attribute> attrsToRemove) {
        JSONObject userPatchJson = new JSONObject();
        Map<String, Object> loginChanges = new HashMap<>();

        CourseEnrollments courseEnrollments = null;
        String userId = uid.getUidValue();
        if (containsAnyCourseIdAttribute(attrsToReplace)
                || containsAnyCourseIdAttribute(attrsToRemove)) {
            courseEnrollments = fetchUserEnrollments(userId);
        }

        if (!attrsToReplace.isEmpty()) {
            LOG.ok("updateUser: Processing through REPLACE set of attributes in the update attribute delta op");
            for (Attribute attr : attrsToReplace) {
                if (USER_UPDATABLE_PROPERTIES.contains(attr.getName())) {
                    userPatchJson.put(connAttrToPropertyName(attr.getName()), AttributeUtil.getSingleValue(attr));
                }
                if (USER_UPDATABLE_LOGIN_PROPERTIES.contains(attr.getName())) {
                    loginChanges.put(attr.getName(), AttributeUtil.getSingleValue(attr));
                }
                if (attr.getName().equals(STUDENT_COURSE_IDS)) {
                    assert courseEnrollments != null;
                    replaceUserEnrollments(courseEnrollments.studentEnrollments,
                            attr.getValue(), configuration.getStudentRoleId(), userId);
                }
                if (attr.getName().equals(TEACHER_COURSE_IDS)) {
                    assert courseEnrollments != null;
                    replaceUserEnrollments(courseEnrollments.teacherEnrollments,
                            attr.getValue(), configuration.getTeacherRoleId(), userId);
                }
            }
        }
        if (!attrsToAdd.isEmpty()) {
            LOG.ok("updateUser: Processing through ADD set of attributes in the update attribute delta op");
            for (Attribute attr : attrsToAdd) {
                // all attrs are single-valued, so we simply put them to the patch JSON
                if (USER_UPDATABLE_PROPERTIES.contains(attr.getName())) {
                    userPatchJson.put(connAttrToPropertyName(attr.getName()), AttributeUtil.getSingleValue(attr));
                }
                if (USER_UPDATABLE_LOGIN_PROPERTIES.contains(attr.getName())) {
                    loginChanges.put(attr.getName(), AttributeUtil.getSingleValue(attr));
                }
                if (attr.getName().equals(STUDENT_COURSE_IDS)) {
                    attr.getValue().forEach(courseId ->
                            createEnrollment((String) courseId, userId, configuration.getStudentRoleId()));
                }
                if (attr.getName().equals(TEACHER_COURSE_IDS)) {
                    attr.getValue().forEach(courseId ->
                            createEnrollment((String) courseId, userId, configuration.getTeacherRoleId()));
                }
            }
        }

        if (!attrsToRemove.isEmpty()) {
            LOG.ok("updateUser: Processing through REMOVE set of attributes in the update attribute delta op");
            for (Attribute attr : attrsToRemove) {
                if (USER_UPDATABLE_PROPERTIES.contains(attr.getName())) {
                    userPatchJson.put(connAttrToPropertyName(attr.getName()), JSONObject.NULL);
                }
                if (USER_UPDATABLE_LOGIN_PROPERTIES.contains(attr.getName())) {
                    loginChanges.put(attr.getName(), JSONObject.NULL);
                }
                if (attr.getName().equals(STUDENT_COURSE_IDS)) {
                    assert courseEnrollments != null;
                    deleteEnrollmentsFromUser(courseEnrollments.studentEnrollments, attr.getValue());
                }
                if (attr.getName().equals(TEACHER_COURSE_IDS)) {
                    assert courseEnrollments != null;
                    deleteEnrollmentsFromUser(courseEnrollments.teacherEnrollments, attr.getValue());
                }
            }
        }

        if (userPatchJson.length() > 0) {
            // all changes are embedded under "user" key
            String body = new JSONObject(Map.of("user", userPatchJson)).toString();
            canvasClient.putJson(API_USER_DETAILS + userId, body,
                    handleNotFoundAndNotSuccess(uid, OBJECT_CLASS_USER));
        }

        if (!loginChanges.isEmpty()) {
            updateUserLoginInfo(uid, loginChanges);
        }
    }

    private void replaceUserEnrollments(List<CourseEnrollment> existingEnrollments,
            List<Object> newCourseIds, int roleId, String userId) {
        Set<Object> newCourseIdsSet = new HashSet<>(newCourseIds);
        existingEnrollments.stream()
                .filter(e -> !newCourseIdsSet.contains(e.courseIdString())) // it's a string here!
                .filter(e -> !e.isInactiveState())
                .forEach(e -> deleteEnrollment(e));

        Set<String> activeCourseIds = existingEnrollments.stream()
                .filter(e -> e.state.equals(CREATED_ENROLLMENT_STATE))
                .map(e -> e.courseIdString())
                .collect(Collectors.toSet());

        newCourseIds.stream()
                .filter(id -> !activeCourseIds.contains((String) id))
                .forEach(id -> createEnrollment((String) id, userId, roleId));
    }

    private boolean containsAnyCourseIdAttribute(Set<Attribute> attrs) {
        return attrs.stream().anyMatch(a -> USER_ENROLLMENT_ID_ATTRS.contains(a.getName()));
    }

    private String connAttrToPropertyName(String attrName) {
        if (attrName.equals(FULL_NAME)) {
            return NAME;
        }
        return attrName;
    }

    private void updateUserLoginInfo(Uid uid, Map<String, Object> changes) {
        JSONObject loginInfo = getUserLoginInfo(uid);
        throwIf(loginInfo == null, () -> new ConnectorException("No login info found for user with ID "
                + uid.getUidValue() + " on account " + configuration.getAccountId()));

        JSONObject newLoginInfo = new JSONObject();
        Object login = changes.get(Name.NAME);
        if (login != null) {
            newLoginInfo.put(UNIQUE_ID, login);
        }
        Object authProviderId = changes.get(AUTHENTICATION_PROVIDER_ID);
        if (authProviderId != null) {
            newLoginInfo.put(AUTHENTICATION_PROVIDER_ID, authProviderId);
        }
        Object password = changes.get(OperationalAttributes.PASSWORD_NAME);
        if (password != null) {
            ((GuardedString) password).access(
                    chars -> newLoginInfo.put(PASSWORD, new String(chars)));
        }
        Object enable = changes.get(OperationalAttributes.ENABLE_NAME);
        if (enable != null) {
            newLoginInfo.put(WORKFLOW_STATE,
                    enable.equals(Boolean.TRUE)
                            ? WORKFLOW_STATE_ACTIVE
                            : WORKFLOW_STATE_SUSPENDED);
        }

        int loginId = loginInfo.getInt(ID);
        executeLoginInfoUpdate(loginId, newLoginInfo);
        // Do I need to handle duplicate logins separately?
        // {"errors":{"unique_id":[{"attribute":"unique_id","type":"taken","message":"ID already in use for this account and authentication provider"}]}}
        if (newLoginInfo.has(PASSWORD)) {
            newLoginInfo.put(PASSWORD, "<changed>"); // let's not print the actual password to log ;-)
        }
        LOG.ok("Login info changed for user with ID {0}, changes: {1}", uid.getUidValue(), newLoginInfo);
    }

    private void executeLoginInfoUpdate(int loginId, JSONObject newLoginInfo) {
        canvasClient.putJson(API_ACCOUNTS + configuration.getAccountId() + "/logins/" + loginId,
                new JSONObject(Map.of("login", newLoginInfo)).toString(),
                // The login exists, 404 problem indicates another problem:
                response -> {
                    if (response.statusCode == 404) {
                        if (newLoginInfo.has(AUTHENTICATION_PROVIDER_ID)) {
                            throw new ConnectorException(
                                    "Possibly invalid authentication provider ID? Error from resource: "
                                            + response.bodyPreview(500));
                        } else {
                            throw new ConnectorException("Error from resource: " + response.bodyPreview(500));
                        }
                    }
                },
                CanvasClient::throwIfNotSuccess);
    }

    private JSONObject getUserLoginInfo(Uid uid) {
        String userId = uid.getUidValue();
        CanvasResponse response = canvasClient.get(
                API_USER_DETAILS + userId + "/logins",
                handleNotFoundAndNotSuccess(uid, OBJECT_CLASS_USER));
        JSONArray logins = new JSONArray(response.body);
        for (Object item : logins) {
            JSONObject login = (JSONObject) item;
            if (login.getInt("account_id") == configuration.getAccountId()) {
                return login;
            }
        }
        return null;
    }

    private void updateCourse(Uid uid, Set<Attribute> attrsToReplace, Set<Attribute> attrsToAdd, Set<Attribute> attrsToRemove) {
        Predicate<Attribute> attrToRemovePredicate = a -> !a.getName().equals(STUDENT_IDS) && !a.getName().equals(TEACHER_IDS);
        attrsToReplace.removeIf(attrToRemovePredicate);
        attrsToAdd.removeIf(attrToRemovePredicate);
        attrsToRemove.removeIf(attrToRemovePredicate);

        if (attrsToReplace.isEmpty() && attrsToAdd.isEmpty() && attrsToRemove.isEmpty()) {
            return; // nothing to do
        }

        String courseId = uid.getUidValue();
        CourseEnrollments courseEnrollments = null;
        if (!attrsToReplace.isEmpty()) {
            // For replace we need to know current state as well, so we fetch it:
            courseEnrollments = fetchCourseEnrollments(courseId);
            for (Attribute attr : attrsToReplace) {
                if (attr.getName().equals(STUDENT_IDS)) {
                    replaceCourseEnrollments(courseEnrollments.studentEnrollments,
                            attr.getValue(), configuration.getStudentRoleId(), courseId);
                }
                if (attr.getName().equals(TEACHER_IDS)) {
                    replaceCourseEnrollments(courseEnrollments.teacherEnrollments,
                            attr.getValue(), configuration.getTeacherRoleId(), courseId);
                }
            }
        }

        // For add, we don't bother to query existing enrollments, it's safe to re-add
        for (Attribute attr : attrsToAdd) {
            if (attr.getName().equals(STUDENT_IDS)) {
                attr.getValue().forEach(userId ->
                        createEnrollment(courseId, (String) userId, configuration.getStudentRoleId()));
            }
            if (attr.getName().equals(TEACHER_IDS)) {
                attr.getValue().forEach(userId ->
                        createEnrollment(courseId, (String) userId, configuration.getTeacherRoleId()));
            }
        }

        // For delete we need the enrollmentId - if we have it already from REPLACE, we'll reuse it
        if (!attrsToRemove.isEmpty()) {
            courseEnrollments = courseEnrollments != null ? courseEnrollments : fetchCourseEnrollments(courseId);
            for (Attribute attr : attrsToRemove) {
                if (attr.getName().equals(STUDENT_IDS)) {
                    deleteEnrollmentsFromCourse(courseEnrollments.studentEnrollments, attr.getValue());
                }
                if (attr.getName().equals(TEACHER_IDS)) {
                    deleteEnrollmentsFromCourse(courseEnrollments.teacherEnrollments, attr.getValue());
                }
            }
        }
    }

    private void replaceCourseEnrollments(List<CourseEnrollment> existingEnrollments,
            List<Object> newUserIds, int roleId, String courseId) {
        Set<Object> newUserIdsSet = new HashSet<>(newUserIds);
        existingEnrollments.stream()
                .filter(e -> !newUserIdsSet.contains(e.userIdString())) // it's a string here!
                .filter(e -> !e.isInactiveState())
                .forEach(e -> deleteEnrollment(e));

        Set<String> activeUserIds = existingEnrollments.stream()
                .filter(e -> e.state.equals(CREATED_ENROLLMENT_STATE))
                .map(e -> e.userIdString())
                .collect(Collectors.toSet());

        newUserIds.stream()
                .filter(id -> !activeUserIds.contains((String) id))
                .forEach(id -> createEnrollment(courseId, (String) id, roleId));
    }

    private void deleteEnrollment(CourseEnrollment enrollment) {
        LOG.info("deleting enrollment: id {0}, user_id {1}, course_id {2}",
                enrollment.enrollmentId, enrollment.userId, enrollment.courseId);
        // Without explicit parameter, the state is changed to "completed" (delete task "concluded")
        // See: https://canvas.instructure.com/doc/api/enrollments.html#method.enrollments_api.destroy
        canvasClient.delete(API_COURSES_DETAILS + enrollment.courseId + "/enrollments/"
                + enrollment.enrollmentId + "?task=inactivate");
    }

    /*
    curl -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/courses/1/enrollments" \
      -X POST -F 'enrollment[user_id]=1' -F 'enrollment[role_id]=25'
    Request params are provided in JSON in the code, which is a valid alternative.
    */
    private void createEnrollment(String courseId, String userId, int roleId) {
        LOG.info("adding enrollment: user_id {0}, course_id {1}, role_id {2}", userId, courseId, roleId);
        JSONObject json = new JSONObject();
        json.put("enrollment", Map.of(
                "user_id", Integer.valueOf(userId),
                "role_id", roleId,
                "enrollment_state", CREATED_ENROLLMENT_STATE,
                "notify", configuration.isSendEnrollmentNotification()));
        canvasClient.postJson(API_COURSES_DETAILS + courseId + "/enrollments", json.toString());
    }

    private void deleteEnrollmentsFromCourse(List<CourseEnrollment> existingEnrollments, List<Object> userIdsToDelete) {
        Set<Object> idsToDeleteSet = new HashSet<>(userIdsToDelete);
        existingEnrollments.stream()
                .filter(e -> idsToDeleteSet.contains(e.userIdString())) // it's a string here!
                .filter(e -> !e.isInactiveState())
                .forEach(e -> deleteEnrollment(e));
    }

    private void deleteEnrollmentsFromUser(List<CourseEnrollment> existingEnrollments, List<Object> courseIdsToDelete) {
        Set<Object> idsToDeleteSet = new HashSet<>(courseIdsToDelete);
        existingEnrollments.stream()
                .filter(e -> idsToDeleteSet.contains(e.courseIdString())) // it's a string here!
                .filter(e -> !e.isInactiveState())
                .forEach(e -> deleteEnrollment(e));
    }

    private CanvasClient.ResponseHandler[] handleNotFoundAndNotSuccess(String uid, ObjectClass objectClass) {
        return handleNotFoundAndNotSuccess(new Uid(uid), objectClass);
    }

    private CanvasClient.ResponseHandler[] handleNotFoundAndNotSuccess(Uid uid, ObjectClass objectClass) {
        return new CanvasClient.ResponseHandler[] {
                new CanvasClient.NotFoundResponseHandler(uid, objectClass),
                CanvasClient::throwIfNotSuccess
        };
    }

    @Override
    public CanvasConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void dispose() {
        canvasClient.close();
        canvasClient = null;
    }

    /** Helper structure with course user and teacher ids. */
    private static class CourseEnrollments {
        List<CourseEnrollment> studentEnrollments = new ArrayList<>();
        List<CourseEnrollment> teacherEnrollments = new ArrayList<>();

        public void addStudentEnrollment(JSONObject json) {
            studentEnrollments.add(new CourseEnrollment(json));
        }

        public void addTeacherEnrollment(JSONObject json) {
            teacherEnrollments.add(new CourseEnrollment(json));
        }

        public List<String> getCurrentStudentIds() {
            return userIdsAsStrings(studentEnrollments);
        }

        public List<String> getCurrentTeacherIds() {
            return userIdsAsStrings(teacherEnrollments);
        }

        public List<String> getCurrentStudentCourseIds() {
            return courseIdsAsStrings(studentEnrollments);
        }

        public List<String> getCurrentTeacherCourseIds() {
            return courseIdsAsStrings(teacherEnrollments);
        }

        private List<String> userIdsAsStrings(List<CourseEnrollment> enrollments) {
            return enrollments.stream()
                    .filter(CourseEnrollment::isCurrent)
                    .map(e -> e.userIdString())
                    .toList();
        }

        private List<String> courseIdsAsStrings(List<CourseEnrollment> enrollments) {
            return enrollments.stream()
                    .filter(CourseEnrollment::isCurrent)
                    .map(e -> e.courseIdString())
                    .toList();
        }
    }

    private record CourseEnrollment(int enrollmentId, int userId, int courseId, String state) {
        public CourseEnrollment(JSONObject json) {
            this(json.getInt("id"), json.getInt("user_id"), json.getInt("course_id"), json.getString("enrollment_state"));
        }

        // These two are default for state[]: https://canvas.instructure.com/doc/api/enrollments.html#method.enrollments_api.index
        public boolean isCurrent() {
            return state.equals(CREATED_ENROLLMENT_STATE) || state.equals(ENROLLMENT_STATE_INVITED);
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        public boolean isInactiveState() {
            return state.equals(ENROLLMENT_STATE_INACTIVE)
                    || state.equals(ENROLLMENT_STATE_COMPLETED);
        }

        public String userIdString() {
            return String.valueOf(userId);
        }

        public String courseIdString() {
            return String.valueOf(courseId);
        }
    }

    private record Pagination(int page, int pageSize, int skip, int limit) {
        public static final Pagination DEFAULT = new Pagination(1, 100, 0, LIST_MAX_ITEMS);

        public static Pagination from(OperationOptions options) {
            if (options == null) {
                return DEFAULT;
            }

            Integer optPageSize = options.getPageSize();
            Integer offset = options.getPagedResultsOffset();
            // We only support pagination with offset >= 1, not with cookie:
            if (optPageSize == null || optPageSize == 0 || offset == null || offset == 0) {
                return DEFAULT;
            }

            int fixedOffset = offset - 1;
            int pageSize = optPageSize;
            int zeroBasedPage = fixedOffset / pageSize; // more useful for calculation
            int skip = fixedOffset - zeroBasedPage * pageSize;
            return new Pagination(zeroBasedPage + 1, pageSize, skip, pageSize);
        }
    }
}
