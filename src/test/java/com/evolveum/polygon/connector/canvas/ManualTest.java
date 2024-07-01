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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.evolveum.polygon.connector.canvas.CanvasConnector.*;

import java.util.*;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.api.operations.SchemaApiOp;
import org.identityconnectors.framework.api.operations.TestApiOp;
import org.identityconnectors.framework.api.operations.ValidateApiOp;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.test.common.TestHelpers;
import org.identityconnectors.test.common.ToListResultsHandler;

/**
 * This is just a manual test, otherwise we would have to simulate Canvas somehow.
 * <ol>
 * <li>Run Canvas somehow and get the API token for admin.</li>
 * <li>Provide the following arguments in this order:
 * <ol>
 *     <li>base URL (without /api/v1),</li>
 *     <li>auth token for admin user,</li>
 *     <li>account ID (typically 1),</li>
 *     <li>student role ID (likely 11),</li>
 *     <li>teacher role ID (likely 12).</li>
 *     <li>test course ID - this must be pre-created course on the Canvas account
 *     where the enrollments will be tested (to avoid problems on other courses)</li>
 *     <li>OPTIONAL: auth provider ID - used for logins, default is 1 (Canvas),
 *     but different ID can be used, e.g. for LDAP</li>
 * </ol>
 * </li>
 * <li>Observe the results, mostly summarized in FINAL LOG (unless failure occurs).</li>
 * </ol>
 */
public class ManualTest {

    public static final Uid WRONG_UID = new Uid("-1");

    public static final StringBuilder finalLog = new StringBuilder("\nFINAL LOG:");

    public static final String TEST_USER_LOGIN = "test47";

    private static Uid testUserUid;

    private static int testCourseId;

    private static final List<String> allCourseIds = new ArrayList<>(); // populated by test01SearchCourses

    public static final int AUTHENTICATION_PROVIDER_CANVAS = 1;

    private static int authProviderId = AUTHENTICATION_PROVIDER_CANVAS;

    public static void main(String[] args) {
        testCourseId = Integer.parseInt(args[5]);
        if (args.length > 6) {
            authProviderId = Integer.parseInt(args[6]);
        }

        ConnectorFacade connector = createConnectorFacade(args);
        connector.test();
        testSupportedOperations(connector);

        test01SearchCourses(connector);
        test02SearchUsers(connector);
        test03GetCourse(connector);

        test10CreateUser(connector);
        test12GetUser(connector);
        test14CreateGetDeleteUserDisabled(connector);

        test20UpdateUser(connector);
        test22AssignUsersToCourses(connector);
        test24AssignCoursesToUsers(connector);

        test30DeleteUser(connector);

        connector.dispose();

        System.out.println(finalLog);
    }

    private static ConnectorFacade createConnectorFacade(String[] args) {
        CanvasConfiguration config = new CanvasConfiguration();
        config.setBaseUrl(args[0]);
        config.setAuthToken(new GuardedString((args[1]).toCharArray()));
        config.setAccountId(Integer.parseInt(args[2]));
        config.setStudentRoleId(Integer.parseInt(args[3]));
        config.setTeacherRoleId(Integer.parseInt(args[4]));
        config.setSendEnrollmentNotification(true);

        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        APIConfiguration impl = TestHelpers.createTestConfiguration(CanvasConnector.class, config);
        impl.getResultsHandlerConfiguration().setEnableAttributesToGetSearchResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableCaseInsensitiveFilter(false);
        impl.getResultsHandlerConfiguration().setEnableFilteredResultsHandler(false);
        impl.getResultsHandlerConfiguration().setEnableNormalizingResultsHandler(false);
        impl.getResultsHandlerConfiguration().setFilteredResultsHandlerInValidationMode(false);
        return factory.newInstance(impl);
    }

    private static void test01SearchCourses(ConnectorFacade connector) {
        System.out.println("\ntest01SearchCourses\n");
        ToListResultsHandler resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_COURSE, null, resultsHandler, null);
        List<ConnectorObject> objects = resultsHandler.getObjects();

        addFinalLog("testSearchCourses: resultsHandler.getObjects().size() = " + objects.size());
        if (!objects.isEmpty()) {
            StringBuilder sb = new StringBuilder("testSearchCourses found:");
            for (ConnectorObject object : objects) {
                String id = object.getUid().getUidValue();
                allCourseIds.add(id);
                sb.append("\n  ").append(id)
                        .append(" - ").append(object.getName().getNameValue());
            }
            addFinalLog(sb.toString());
        }

        resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_COURSE, null, resultsHandler,
                new OperationOptions(Map.of(
                        OperationOptions.OP_PAGED_RESULTS_OFFSET, 3,
                        OperationOptions.OP_PAGE_SIZE, 3
                )));
        objects = resultsHandler.getObjects();
        List<String> paginatedIds = objects.stream().map(o -> o.getUid().getUidValue()).toList();
        // 2 is the third element, pagination is 1-based
        assertThat(paginatedIds).hasSize(3)
                .containsExactlyElementsOf(allCourseIds.subList(2, 5));

        addFinalLog("testSearchCourses - pagination(3,3) result ids: " + paginatedIds);
    }

    private static void test02SearchUsers(ConnectorFacade connector) {
        System.out.println("\ntest02SearchUsers\n");
        ToListResultsHandler resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_USER, null, resultsHandler, null);
        List<ConnectorObject> objects = resultsHandler.getObjects();

        int totalUsers = objects.size();

        addFinalLog("testSearchUsers: resultsHandler.getObjects().size() = " + totalUsers);
        if (objects.isEmpty()) {
            return;
        }
        addFinalLog("testSearchUsers: first object = " + prettyString(objects.get(0)));

        if (objects.size() < 3) {
            return; // no reason to check pagination with so few users
        }
        // For pagination test, I'll try to get first 2 users and last 2 users
        List<String> allUserIds = objects.stream().map(o -> o.getUid().getUidValue()).toList();
        resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_USER, null, resultsHandler,
                new OperationOptions(Map.of(
                        OperationOptions.OP_PAGED_RESULTS_OFFSET, 1,
                        OperationOptions.OP_PAGE_SIZE, 2
                )));
        objects = resultsHandler.getObjects();
        List<String> paginatedIds = objects.stream().map(o -> o.getUid().getUidValue()).toList();
        assertThat(paginatedIds).hasSize(2)
                .containsExactlyElementsOf(allUserIds.subList(0, 2));

        addFinalLog("testSearchUsers - paginated first two users ids: " + paginatedIds);

        resultsHandler = new ToListResultsHandler();
        connector.search(OBJECT_CLASS_USER, null, resultsHandler,
                new OperationOptions(Map.of(
                        OperationOptions.OP_PAGED_RESULTS_OFFSET, allUserIds.size() - 1,
                        OperationOptions.OP_PAGE_SIZE, 2
                )));
        objects = resultsHandler.getObjects();
        paginatedIds = objects.stream().map(o -> o.getUid().getUidValue()).toList();
        assertThat(paginatedIds).hasSize(2)
                .containsExactlyElementsOf(allUserIds.subList(allUserIds.size() - 2, allUserIds.size()));

        addFinalLog("testSearchUsers - paginated last two users ids: " + paginatedIds);
    }

    private static void test03GetCourse(ConnectorFacade connector) {
        System.out.println("\ntest03GetCourse\n");
        ConnectorObject object = connector.getObject(OBJECT_CLASS_COURSE, new Uid(String.valueOf(testCourseId)), null);

        addFinalLog("testGetObjectGroup: first object = " + prettyString(object));

        assertThatThrownBy(() -> connector.getObject(OBJECT_CLASS_USER, WRONG_UID, null))
                .isInstanceOf(UnknownUidException.class);
    }

    private static void test10CreateUser(ConnectorFacade connector) {
        System.out.println("\ntest10CreateUser\n");
        deleteTestUserIfExists(TEST_USER_LOGIN, connector);
        deleteTestUserIfExists(TEST_USER_LOGIN + "X", connector); // in case previous test failed after rename

        Set<Attribute> set = new HashSet<>();
        set.add(AttributeBuilder.build(Name.NAME, TEST_USER_LOGIN)); // login
        set.add(AttributeBuilder.build(FULL_NAME, "User Test47"));
        set.add(AttributeBuilder.build(SHORT_NAME, "TEST47"));
        set.add(AttributeBuilder.build(EMAIL, "test47@example.com"));
        set.add(AttributeBuilder.build(AUTHENTICATION_PROVIDER_ID, AUTHENTICATION_PROVIDER_CANVAS));
        set.add(AttributeBuilder.build(OperationalAttributes.PASSWORD_NAME,
                new GuardedString("init-password".toCharArray())));
        String testCourseIdString = String.valueOf(testCourseId);
        set.add(AttributeBuilder.build(STUDENT_COURSE_IDS, testCourseIdString));
        set.add(AttributeBuilder.build(TEACHER_COURSE_IDS, testCourseIdString));

        testUserUid = connector.create(OBJECT_CLASS_USER, set, null);
        assertThat(testUserUid).isNotNull();

        ConnectorObject object = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertThat(object).isNotNull();
        assertThat(object.getAttributeByName(STUDENT_COURSE_IDS).getValue()).containsExactly(testCourseIdString);
        assertThat(object.getAttributeByName(TEACHER_COURSE_IDS).getValue()).containsExactly(testCourseIdString);

        addFinalLog("testCreateObject - created account " + TEST_USER_LOGIN + " with UID " + testUserUid);
    }

    private static void test12GetUser(ConnectorFacade connector) {
        System.out.println("\ntest12GetUser\n");
        ConnectorObject object = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertThat(object).isNotNull();
        assertThat(object.getAttributeByName(Uid.NAME)).isNotNull();
        assertThat(object.getAttributeByName(Name.NAME)).isNotNull()
                .extracting(a -> AttributeUtil.getStringValue(a)).isEqualTo("test47");
        assertThat(object.getAttributeByName(EMAIL)).isNotNull()
                .extracting(a -> AttributeUtil.getStringValue(a)).isEqualTo("test47@example.com");
        assertThat(object.getAttributeByName(FULL_NAME)).isNotNull()
                .extracting(a -> AttributeUtil.getStringValue(a)).isEqualTo("User Test47");
        // If short name is not provided, it would be filled with the full name value by Canvas.
        assertThat(object.getAttributeByName(SHORT_NAME)).isNotNull()
                .extracting(a -> AttributeUtil.getStringValue(a)).isEqualTo("TEST47");
        assertThat(object.getAttributeByName(SORTABLE_NAME)).isNotNull(); // not updatable, calculated by Canvas
        assertThat(object.getAttributeByName(OperationalAttributes.ENABLE_NAME)).isNotNull()
                .extracting(a -> AttributeUtil.getBooleanValue(a)).isEqualTo(true);
        assertThat(object.getAttributeByName(AUTHENTICATION_PROVIDER_ID)).isNotNull()
                .extracting(a -> AttributeUtil.getIntegerValue(a)).isEqualTo(AUTHENTICATION_PROVIDER_CANVAS);

        addFinalLog("testGetObjectAccount: object = " + prettyString(object));

        assertThatThrownBy(() -> connector.getObject(OBJECT_CLASS_USER, WRONG_UID, null))
                .isInstanceOf(UnknownUidException.class);
    }

    private static void test14CreateGetDeleteUserDisabled(ConnectorFacade connector) {
        System.out.println("\ntest14CreateGetDeleteUserDisabled\n");
        deleteTestUserIfExists("disabled-user", connector); // in case previous test goes wrong

        Set<Attribute> set = new HashSet<>();
        set.add(AttributeBuilder.build(Name.NAME, "disabled-user")); // login
        set.add(AttributeBuilder.build(FULL_NAME, "User Disabled"));
        set.add(AttributeBuilder.build(EMAIL, "disabled@example.com"));
        set.add(AttributeBuilder.build(AUTHENTICATION_PROVIDER_ID, authProviderId));
        set.add(AttributeBuilder.build(OperationalAttributes.ENABLE_NAME, false));

        Uid userUid = connector.create(OBJECT_CLASS_USER, set, null);
        assertThat(userUid).isNotNull();

        addFinalLog("testCreateGetDeleteUserDisabled - created account: " + userUid.getUidValue());

        ConnectorObject object = connector.getObject(OBJECT_CLASS_USER, userUid, null);
        assertThat(object.getName().getNameValue()).isEqualTo("disabled-user");
        assertThat(AttributeUtil.getIntegerValue(object.getAttributeByName(AUTHENTICATION_PROVIDER_ID)))
                .isEqualTo(authProviderId);
        assertThat(AttributeUtil.getBooleanValue(object.getAttributeByName(OperationalAttributes.ENABLE_NAME)))
                .isFalse();

        addFinalLog("testCreateGetDeleteUserDisabled - get: " + prettyString(object));

        connector.delete(OBJECT_CLASS_USER, userUid, null);
        addFinalLog("testCreateGetDeleteUserDisabled - deleted");
    }

    private static void test20UpdateUser(ConnectorFacade connector) {
        System.out.println("\ntest20UpdateUser\n");
        Set<AttributeDelta> updateResult = connector.updateDelta(OBJECT_CLASS_USER, testUserUid,
                Set.of(AttributeDeltaBuilder.build(Name.NAME, TEST_USER_LOGIN + "X")), null);
        assertThat(updateResult).isEmpty();

        ConnectorObject object = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertThat(object.getName().getNameValue()).isEqualTo(TEST_USER_LOGIN + "X");

        // For NAME, we can't even create delta without REPLACE, so no worry about sending empty value for login:
        assertThatThrownBy(() -> AttributeDeltaBuilder.build(Name.NAME,
                Set.of(TEST_USER_LOGIN + "X"), Set.of("whatever")))
                .isInstanceOf(IllegalArgumentException.class);

        // Let's change all modifiable attributes except password, combination of base user+login info.
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid,
                Set.of(AttributeDeltaBuilder.build(Name.NAME, TEST_USER_LOGIN),
                        AttributeDeltaBuilder.build(FULL_NAME, "User 47 Test"),
                        AttributeDeltaBuilder.build(SHORT_NAME, "UT47"),
                        AttributeDeltaBuilder.build(AUTHENTICATION_PROVIDER_ID, authProviderId),
                        AttributeDeltaBuilder.build(OperationalAttributes.ENABLE_NAME, false),
                        AttributeDeltaBuilder.build(EMAIL, "user47@example.com")),
                null);
        object = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertThat(object.getName().getNameValue()).isEqualTo(TEST_USER_LOGIN);
        assertThat(AttributeUtil.getStringValue(object.getAttributeByName(FULL_NAME)))
                .isEqualTo("User 47 Test");
        assertThat(AttributeUtil.getStringValue(object.getAttributeByName(SHORT_NAME)))
                .isEqualTo("UT47");
        assertThat(AttributeUtil.getStringValue(object.getAttributeByName(EMAIL)))
                .isEqualTo("user47@example.com");
        assertThat(AttributeUtil.getBooleanValue(object.getAttributeByName(OperationalAttributes.ENABLE_NAME)))
                .isFalse();

        // This is just reported, because Canvas may be set to disallow password change by admin
        try {
            connector.updateDelta(OBJECT_CLASS_USER, testUserUid,
                    Set.of(
                            // In any case, we need to use "canvas" provider to allow password change.
                            // This seems to work in the same call as password change just fine.
                            // If the login is already using the provider, it's not necessary here, of course.
                            AttributeDeltaBuilder.build(AUTHENTICATION_PROVIDER_ID, 1),
                            AttributeDeltaBuilder.build(OperationalAttributes.PASSWORD_NAME,
                                    new GuardedString("new-password".toCharArray()))), null);
            addFinalLog("Password change OK");
        } catch (Exception e) {
            addFinalLog("Password change problem: " + e);
        }

        assertThatThrownBy(() -> connector.updateDelta(OBJECT_CLASS_USER, WRONG_UID,
                Set.of(AttributeDeltaBuilder.build(Name.NAME, "xxx")), null))
                .isInstanceOf(UnknownUidException.class);

        addFinalLog("testUpdateUser - updated account with UID " + testUserUid);
    }

    private static void test22AssignUsersToCourses(ConnectorFacade connector) {
        System.out.println("\ntest22AssignUsersToCourses\n");
        String testUser2Id = getOrCreateAnotherUser("testUser2", connector).getUidValue();
        String testUserId = testUserUid.getUidValue();

        // First we will delete all teachers/students on the test course
        Uid courseUid = new Uid(String.valueOf(testCourseId));
        Set<AttributeDelta> additionalDeltas = connector.updateDelta(OBJECT_CLASS_COURSE, courseUid,
                // AttributeDeltaBuilder.build(STUDENT_IDS) is not good and in updateDelta causes:
                // IllegalArgumentException: Lists of added, removed and replaced values can not be 'null'.
                Set.of(AttributeDeltaBuilder.build(STUDENT_IDS, Set.of()),
                        AttributeDeltaBuilder.build(TEACHER_IDS, Set.of())),
                null);
        assertThat(additionalDeltas).isEmpty();
        // Validate
        ConnectorObject modifiedCourse = connector.getObject(OBJECT_CLASS_COURSE, courseUid, null);
        assertStudentIds(modifiedCourse);
        assertTeacherIds(modifiedCourse);
        addFinalLog("testAssignUsersToCourses - replace with empty values: " + prettyString(modifiedCourse));

        // Now we set students
        Set<AttributeDelta> delta = Set.of(AttributeDeltaBuilder.build(STUDENT_IDS, testUserId, testUser2Id));
        connector.updateDelta(OBJECT_CLASS_COURSE, courseUid, delta, null);
        modifiedCourse = connector.getObject(OBJECT_CLASS_COURSE, courseUid, null);
        assertStudentIds(modifiedCourse, testUserId, testUser2Id);
        assertTeacherIds(modifiedCourse);
        addFinalLog("testAssignUsersToCourses - replace with students only: " + prettyString(modifiedCourse));

        // repeated update, nothing should change
        connector.updateDelta(OBJECT_CLASS_COURSE, courseUid, delta, null);
        modifiedCourse = connector.getObject(OBJECT_CLASS_COURSE, courseUid, null);
        assertStudentIds(modifiedCourse, testUserId, testUser2Id);
        assertTeacherIds(modifiedCourse);
        addFinalLog("testAssignUsersToCourses - repeated replace correct");

        String teacherId = getOrCreateAnotherUser("testTeacher", connector).getUidValue();

        connector.updateDelta(OBJECT_CLASS_COURSE, courseUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_IDS, testUser2Id),
                        AttributeDeltaBuilder.build(TEACHER_IDS, teacherId)),
                null);
        modifiedCourse = connector.getObject(OBJECT_CLASS_COURSE, courseUid, null);
        assertStudentIds(modifiedCourse, testUser2Id);
        assertTeacherIds(modifiedCourse, teacherId);
        addFinalLog("testAssignUsersToCourses - replace with teacher: " + prettyString(modifiedCourse));

        connector.updateDelta(OBJECT_CLASS_COURSE, courseUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_IDS,
                                // add/remove sets, they are also applied in this order
                                Set.of(testUserId, testUser2Id), Set.of(testUser2Id, teacherId)),
                        AttributeDeltaBuilder.build(TEACHER_IDS,
                                Set.of(testUserId, testUser2Id), Set.of(teacherId))),
                null);
        modifiedCourse = connector.getObject(OBJECT_CLASS_COURSE, courseUid, null);
        assertStudentIds(modifiedCourse, testUserId); // testUser2Id removed, teacherId ignored
        assertTeacherIds(modifiedCourse, testUserId, testUser2Id);
        addFinalLog("testAssignUsersToCourses - add/remove delta: " + prettyString(modifiedCourse));

        // Let's test course IDs on users:
        ConnectorObject object = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertThat(object).isNotNull();
        Attribute attr = object.getAttributeByName(STUDENT_COURSE_IDS);
        assertThat(attr).isNotNull();
        assertThat(attr.getValue()).containsExactlyInAnyOrder(courseUid.getUidValue());

        attr = object.getAttributeByName(TEACHER_COURSE_IDS);
        assertThat(attr).isNotNull();
        assertThat(attr.getValue()).containsExactlyInAnyOrder(courseUid.getUidValue());
        addFinalLog("testAssignUsersToCourses - get user returns student/teacher course IDs");
    }

    private static void test24AssignCoursesToUsers(ConnectorFacade connector) {
        System.out.println("\ntest24AssignCoursesToUsers\n");

        // First we will delete all courses from test user
        String testCourseIdString = String.valueOf(testCourseId);
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid,
                Set.of(AttributeDeltaBuilder.build(STUDENT_COURSE_IDS, Set.of()),
                        AttributeDeltaBuilder.build(TEACHER_COURSE_IDS, Set.of())),
                null);
        // Validate
        ConnectorObject modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, List.of());
        assertTeacherCourseIds(modifiedUser, List.of());
        addFinalLog("testAssignCoursesToUsers - replace with empty values: " + prettyString(modifiedUser));

        // Now we set the user as a student for all courses
        Set<AttributeDelta> delta = Set.of(AttributeDeltaBuilder.build(STUDENT_COURSE_IDS, allCourseIds));
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, delta, null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, allCourseIds);
        assertTeacherCourseIds(modifiedUser, List.of());
        addFinalLog("testAssignCoursesToUsers - replace with students only: " + prettyString(modifiedUser));

        // repeated update, nothing should change
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, delta, null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, allCourseIds);
        assertTeacherCourseIds(modifiedUser, List.of());
        addFinalLog("testAssignCourses - repeated replace correct");

        List<String> halfCourseIds = allCourseIds.stream().limit(allCourseIds.size() / 2).toList();
        List<String> otherHalfCourseIds = allCourseIds.stream().skip(allCourseIds.size() / 2).toList();
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_COURSE_IDS, testCourseIdString),
                        AttributeDeltaBuilder.build(TEACHER_COURSE_IDS, halfCourseIds)),
                null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, List.of(testCourseIdString));
        assertTeacherCourseIds(modifiedUser, halfCourseIds);
        addFinalLog("testAssignCourses - replace with teacher: " + prettyString(modifiedUser));

        // testing add/remove
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_COURSE_IDS,
                                otherHalfCourseIds, halfCourseIds),
                        AttributeDeltaBuilder.build(TEACHER_COURSE_IDS,
                                halfCourseIds, null)),
                null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, otherHalfCourseIds);
        assertTeacherCourseIds(modifiedUser, halfCourseIds);
        addFinalLog("testAssignCourses - add/remove delta: " + prettyString(modifiedUser));

        // testing add/remove 2
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_COURSE_IDS,
                                halfCourseIds, null),
                        AttributeDeltaBuilder.build(TEACHER_COURSE_IDS,
                                null, otherHalfCourseIds)), // no harm done, nothing changes
                null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, allCourseIds);
        assertTeacherCourseIds(modifiedUser, halfCourseIds); // nothing changes here
        addFinalLog("testAssignCourses - add/remove delta 2: " + prettyString(modifiedUser));

        // testing add/remove 3 (removing all current courses)
        connector.updateDelta(OBJECT_CLASS_USER, testUserUid, Set.of(
                        AttributeDeltaBuilder.build(STUDENT_COURSE_IDS,
                                null, allCourseIds),
                        AttributeDeltaBuilder.build(TEACHER_COURSE_IDS,
                                null, halfCourseIds)),
                null);
        modifiedUser = connector.getObject(OBJECT_CLASS_USER, testUserUid, null);
        assertStudentCourseIds(modifiedUser, List.of());
        assertTeacherCourseIds(modifiedUser, List.of());
        addFinalLog("testAssignCourses - add/remove delta 3: " + prettyString(modifiedUser));
    }

    private static void test30DeleteUser(ConnectorFacade connector) {
        System.out.println("\ntest30DeleteUser\n");
        connector.delete(OBJECT_CLASS_USER, testUserUid, null);

        // after the delete, user should not be available by getObject
        assertThatThrownBy(() -> connector.getObject(OBJECT_CLASS_USER, testUserUid, null))
                .isInstanceOf(UnknownUidException.class);

        assertThatThrownBy(() -> connector.delete(OBJECT_CLASS_USER, testUserUid, null))
                .isInstanceOf(UnknownUidException.class);

        addFinalLog("testDeleteUser - deleted account with UID " + testUserUid);
    }

    private static void deleteTestUserIfExists(String login, ConnectorFacade connector) {
        ConnectorObject user = getUserByLogin(login, connector);
        if (user != null) {
            Uid uid = user.getUid();
            connector.delete(OBJECT_CLASS_USER, uid, null);
            addFinalLog("Deleted pre-existing user '" + login + "' with uid: " + uid);
        }
    }

    private static ConnectorObject getUserByLogin(String login, ConnectorFacade connector) {
        ToListResultsHandler resultsHandler = new ToListResultsHandler();
        EqualsFilter filter = new EqualsFilter(AttributeBuilder.build(Name.NAME, login));
        connector.search(OBJECT_CLASS_USER, filter, resultsHandler, null);

        List<ConnectorObject> objects = resultsHandler.getObjects();
        if (objects.isEmpty()) {
            return null;
        }
        assertThat(objects).hasSize(1);
        return objects.get(0);
    }

    private static void assertStudentIds(ConnectorObject modifiedCourse, String... userIds) {
        assertEnrolledUserIds(modifiedCourse, STUDENT_IDS, Arrays.asList(userIds));
    }

    private static void assertTeacherIds(ConnectorObject modifiedCourse, String... userIds) {
        assertEnrolledUserIds(modifiedCourse, TEACHER_IDS, Arrays.asList(userIds));
    }

    private static void assertStudentCourseIds(ConnectorObject modifiedUser, List<String> courseIds) {
        assertEnrolledUserIds(modifiedUser, STUDENT_COURSE_IDS, courseIds);
    }

    private static void assertTeacherCourseIds(ConnectorObject modifiedUser, List<String> courseIds) {
        assertEnrolledUserIds(modifiedUser, TEACHER_COURSE_IDS, courseIds);
    }

    private static void assertEnrolledUserIds(ConnectorObject modifiedCourse, String attrName, List<String> ids) {
        Attribute idsAttr = modifiedCourse.getAttributeByName(attrName);
        if (ids == null || ids.isEmpty()) {
            assertThat(idsAttr == null
                    || idsAttr.getValue() == null
                    || idsAttr.getValue().isEmpty())
                    .withFailMessage("List of IDs for " + attrName + " should be empty/null, but contains: " + idsAttr.getValue())
                    .isTrue();
            return;
        }

        assertThat(idsAttr).isNotNull();
        assertThat(idsAttr.getValue()).containsExactlyInAnyOrderElementsOf(ids);
    }

    /** Returns UID for another test user - this one is left on the system and reused, or created the first time. */
    private static Uid getOrCreateAnotherUser(String login, ConnectorFacade connector) {
        ConnectorObject user = getUserByLogin(login, connector);
        if (user != null) {
            return user.getUid();
        }

        Set<Attribute> set = new HashSet<>();
        set.add(AttributeBuilder.build(Name.NAME, login));
        set.add(AttributeBuilder.build(FULL_NAME, login));
        set.add(AttributeBuilder.build(EMAIL, login + "@example.com"));

        return connector.create(OBJECT_CLASS_USER, set, null);
    }

    private static void testSupportedOperations(ConnectorFacade connector) {
        assertThat(connector.getSupportedOperations())
                .contains(SchemaApiOp.class,
                        TestApiOp.class,
                        ValidateApiOp.class);
    }

    /** Prints object in short no-nonsense way (AttributeUtil.toMap.toString is still too verbose). */
    private static String prettyString(ConnectorObject object) {
        SortedMap<String, Object> attrMap = new TreeMap<>();
        for (Attribute attr : object.getAttributes()) {
            attrMap.put(attr.getName(), attr.getValue());
        }
        return attrMap.toString();
    }

    private static void addFinalLog(String msg) {
        System.out.println(msg);
        finalLog.append("\n- ").append(msg);
    }
}
