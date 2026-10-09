package com.foremen.service;

// Feature: FOR-05-09-team-selection, task 14.2 — Worker_Record_Flow (POST /api/users/worker)
// Requirements 13.1–13.12, 14.2, 15.2

import com.foremen.controller.model.WorkerRecordRequest;
import com.foremen.controller.model.WorkerRecordResponse;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerKind;
import com.foremen.exception.FieldValidationException;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code Worker_Record_Flow}
 * ({@link WorkerRecordService#register(WorkerRecordRequest)}, FOR-05-09 Requirement 13).
 *
 * <p>Collaborators are mocked ({@link RoleDao}, {@link UserService}, {@link ProjectMemberService},
 * {@link ForemenPermissionEvaluator}) so the orchestration logic — the dual-permission guard, the
 * field validation (all offending fields together), the NIP checksum, the canonical record-flow
 * order, and the "create no user / membership / email on any rejection" guarantee — is exercised in
 * isolation from persistence. A test authenticates by stubbing both the SecurityContext authority
 * (role-code extraction) and the evaluator decision.
 */
@DisplayName("WorkerRecordService — Worker_Record_Flow (Req 13)")
class WorkerRecordServiceTest {

    private static final long PROJECT_ID = 42L;
    private static final long NEW_USER_ID = 500L;
    private static final long MEMBERSHIP_ID = 900L;
    /** A valid Polish NIP (checksum-correct): first nine digits 123456780, check digit 2. */
    private static final String VALID_NIP = "1234567802";

    private RoleDao roleDao;
    private UserService userService;
    private ProjectMemberService projectMemberService;
    private ForemenPermissionEvaluator permissionEvaluator;
    private WorkerRecordService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        roleDao = Mockito.mock(RoleDao.class);
        userService = Mockito.mock(UserService.class);
        projectMemberService = Mockito.mock(ProjectMemberService.class);
        permissionEvaluator = Mockito.mock(ForemenPermissionEvaluator.class);
        service = new WorkerRecordService(roleDao, userService, projectMemberService, permissionEvaluator);

        // Default: a MANAGER caller holding PROJECT_MEMBERS CREATE (the PROJECTS EDIT half is the
        // controller's @RequiresPermission, out of scope for this service-level test).
        authenticate("MANAGER");
        when(permissionEvaluator.isAllowed("MANAGER", "PROJECT_MEMBERS", "CREATE")).thenReturn(true);

        RoleEntity workerRole = role("WORKER");
        when(roleDao.findByCode("WORKER")).thenReturn(Optional.of(workerRole));

        // The user create returns a persisted worker; the assign returns a membership with an id.
        when(userService.createWorkerRecord(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> persistedWorker(
                        inv.getArgument(0), inv.getArgument(1),
                        (RoleEntity) inv.getArgument(3), (WorkerKind) inv.getArgument(4)));
        when(projectMemberService.assign(any(), any(), any(), any(), any()))
                .thenReturn(membership(MEMBERSHIP_ID));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------
    // Success path (Req 13.1, 14.2, 15.2)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("success — one uninvited WORKER user + one WORKER membership, atomically, no email")
    class Success {

        @Test
        @DisplayName("PERSON worker with email + worker type -> 201 with identity, assign under WORKER")
        void personWithTypeAndEmail() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "  Jan Kowalski  ", " jan@example.com ", "+48 500 600 700",
                    "ignored company contact", "ignored nip", 3L, List.of(" welder ", "WELDER"), PROJECT_ID);

            WorkerRecordResponse response = service.register(request);

            assertThat(response.userId()).isEqualTo(NEW_USER_ID);
            assertThat(response.projectId()).isEqualTo(PROJECT_ID);
            assertThat(response.membershipId()).isEqualTo(MEMBERSHIP_ID);
            assertThat(response.email()).isEqualTo("jan@example.com");

            // User created with the trimmed PERSON name, trimmed email, WORKER role, PERSON kind, and
            // NO contact person / NIP (Req 13.3: a PERSON ignores company contact / NIP).
            ArgumentCaptor<WorkerKind> kind = ArgumentCaptor.forClass(WorkerKind.class);
            verify(userService).createWorkerRecord(
                    eq("Jan Kowalski"), eq("jan@example.com"), eq("+48 500 600 700"),
                    any(RoleEntity.class), kind.capture(), isNull(), isNull());
            assertThat(kind.getValue()).isEqualTo(WorkerKind.PERSON);

            // Membership assigned under the user's Company_Role (null projectRoleId), with the worker
            // type and the normalized tags (duplicate "WELDER" dropped case-insensitively, Req 15.2).
            ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
            verify(projectMemberService).assign(
                    eq(NEW_USER_ID), eq(PROJECT_ID), isNull(), eq(3L), tags.capture());
            assertThat(tags.getValue()).containsExactly("welder");
        }

        @Test
        @DisplayName("COMPANY worker with contact person + NIP -> stored, PERSON name ignored")
        void companyWithContactAndNip() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "COMPANY", "  Acme Sp. z o.o.  ", null, null,
                    "  Anna Nowak  ", "123-456-78-02", null, null, PROJECT_ID);

            service.register(request);

            verify(userService).createWorkerRecord(
                    eq("Acme Sp. z o.o."), isNull(), isNull(),
                    any(RoleEntity.class), eq(WorkerKind.COMPANY), eq("Anna Nowak"), eq(VALID_NIP));
            // No worker type -> an Uncategorized_Worker; empty normalized tags for an omitted list.
            verify(projectMemberService).assign(eq(NEW_USER_ID), eq(PROJECT_ID), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("worker without an email -> stored email empty in the response, assign still runs")
        void workerWithoutEmail() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "No Email Worker", null, null, null, null, null, null, PROJECT_ID);

            WorkerRecordResponse response = service.register(request);

            assertThat(response.email()).isEqualTo("");
            verify(userService).createWorkerRecord(
                    eq("No Email Worker"), isNull(), isNull(), any(), eq(WorkerKind.PERSON), isNull(), isNull());
        }

        @Test
        @DisplayName("project checks run BEFORE the user is created (Req 13.9/13.11 ordering)")
        void projectCheckedBeforeUserCreated() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Order Check", "o@example.com", null, null, null, null, null, PROJECT_ID);

            service.register(request);

            var inOrder = Mockito.inOrder(projectMemberService, userService);
            inOrder.verify(projectMemberService).assertProjectAccessibleAndEditable(PROJECT_ID);
            inOrder.verify(userService).createWorkerRecord(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Permission (Req 13.10) + project / lock / duplicate / worker-type (Req 13.6–13.9) ordering
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("guard + delegated rejections — create no user / membership / email")
    class Rejections {

        @Test
        @DisplayName("missing PROJECT_MEMBERS CREATE -> 403, no field validation, no project check")
        void missingProjectMembersCreateIs403() {
            authenticate("FOREMAN");
            when(permissionEvaluator.isAllowed("FOREMAN", "PROJECT_MEMBERS", "CREATE")).thenReturn(false);
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "X", "x@example.com", null, null, null, null, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
            verify(projectMemberService, never()).assertProjectAccessibleAndEditable(any());
            verify(userService, never()).createWorkerRecord(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("unauthenticated caller -> 403 error.access.denied")
        void unauthenticatedIs403() {
            SecurityContextHolder.clearContext();
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "X", "x@example.com", null, null, null, null, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("non-accessible project (404 from the project check) -> no user created")
        void projectNotAccessibleRollsBack() {
            doThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", PROJECT_ID))
                    .when(projectMemberService).assertProjectAccessibleAndEditable(PROJECT_ID);
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "X", "x@example.com", null, null, null, null, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
            verify(userService, never()).createWorkerRecord(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("locked project (409 from the project check) -> no user created")
        void lockedProjectRollsBack() {
            doThrow(new ForemenApiException(HttpStatus.CONFLICT, "error.project.team.locked", PROJECT_ID))
                    .when(projectMemberService).assertProjectAccessibleAndEditable(PROJECT_ID);
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "X", "x@example.com", null, null, null, null, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.project.team.locked");
            verify(userService, never()).createWorkerRecord(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("duplicate email (409 from the user create) -> no membership assigned")
        void duplicateEmailRollsBack() {
            when(userService.createWorkerRecord(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new ForemenApiException(
                            HttpStatus.CONFLICT, "error.user.email.already.exists", "dup@example.com"));
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Dup", "dup@example.com", null, null, null, null, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.user.email.already.exists");
            verify(projectMemberService, never()).assign(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("invalid worker type (400 from the assign) propagates; user create already ran")
        void invalidWorkerTypeFromAssign() {
            when(projectMemberService.assign(any(), any(), any(), any(), any()))
                    .thenThrow(new ForemenApiException(
                            HttpStatus.BAD_REQUEST, "error.project.member.worker.type.invalid", 999L));
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Typed", "typed@example.com", null, null, null, 999L, null, PROJECT_ID);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.register(request), ForemenApiException.class);

            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.project.member.worker.type.invalid");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Field validation (Req 13.2–13.5) — all offending fields reported together, before any work
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("field validation — 400 listing every offending field, no project/user work")
    class FieldValidation {

        @Test
        @DisplayName("missing kind + blank name reported together; nothing created")
        void multipleFieldsReportedTogether() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "ROBOT", "   ", "x@example.com", null, null, null, null, null, PROJECT_ID);

            FieldValidationException ex = catchThrowableOfType(
                    () -> service.register(request), FieldValidationException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getFieldErrors())
                    .containsEntry("workerKind", "error.worker.kind.invalid")
                    .containsEntry("name", "error.worker.name.invalid");
            verify(projectMemberService, never()).assertProjectAccessibleAndEditable(any());
            verify(userService, never()).createWorkerRecord(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("invalid NIP checksum -> error.worker.nip.invalid on the nip field")
        void invalidNipChecksum() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "COMPANY", "Acme", null, null, null, "1234567890", null, null, PROJECT_ID);

            FieldValidationException ex = catchThrowableOfType(
                    () -> service.register(request), FieldValidationException.class);

            assertThat(ex.getFieldErrors()).containsEntry("nip", "error.worker.nip.invalid");
        }

        @Test
        @DisplayName("invalid email + invalid phone reported together")
        void invalidEmailAndPhone() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Jan", "not-an-email", "12", null, null, null, null, PROJECT_ID);

            FieldValidationException ex = catchThrowableOfType(
                    () -> service.register(request), FieldValidationException.class);

            assertThat(ex.getFieldErrors())
                    .containsEntry("email", "error.worker.email.invalid")
                    .containsEntry("phone", "error.worker.phone.invalid");
        }

        @Test
        @DisplayName("non-positive worker type id -> error.project.member.worker.type.invalid")
        void nonPositiveWorkerTypeId() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Jan", "jan@example.com", null, null, null, 0L, null, PROJECT_ID);

            FieldValidationException ex = catchThrowableOfType(
                    () -> service.register(request), FieldValidationException.class);

            assertThat(ex.getFieldErrors())
                    .containsEntry("workerTypeId", "error.project.member.worker.type.invalid");
        }

        @Test
        @DisplayName("invalid tag list -> error.project.member.tag.invalid on the tags field")
        void invalidTagList() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "PERSON", "Jan", "jan@example.com", null, null, null, null,
                    List.of("   "), PROJECT_ID); // blank tag -> rejected

            FieldValidationException ex = catchThrowableOfType(
                    () -> service.register(request), FieldValidationException.class);

            assertThat(ex.getFieldErrors()).containsEntry("tags", "error.project.member.tag.invalid");
        }

        @Test
        @DisplayName("field validation runs before the project check (canonical order)")
        void fieldValidationBeforeProjectCheck() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "ROBOT", "Jan", "jan@example.com", null, null, null, null, null, PROJECT_ID);

            catchThrowableOfType(() -> service.register(request), FieldValidationException.class);

            verify(projectMemberService, never()).assertProjectAccessibleAndEditable(any());
        }

        @Test
        @DisplayName("a valid COMPANY NIP with separators is accepted and normalized")
        void validCompanyNipNormalized() {
            WorkerRecordRequest request = new WorkerRecordRequest(
                    "COMPANY", "Acme", null, null, null, "123-456-78-02", null, null, PROJECT_ID);

            service.register(request);

            verify(userService).createWorkerRecord(
                    any(), any(), any(), any(), eq(WorkerKind.COMPANY), isNull(), eq(VALID_NIP));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static UserEntity persistedWorker(String name, String email, RoleEntity role, WorkerKind kind) {
        UserEntity user = new UserEntity();
        user.setId(NEW_USER_ID);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setWorkerKind(kind);
        user.setActive(true);
        return user;
    }

    private static ProjectMemberEntity membership(Long id) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setId(id);
        member.setProjectId(PROJECT_ID);
        return member;
    }

    private static RoleEntity role(String code) {
        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "id", 100L);
        role.setCode(code);
        role.setNameRU("role-ru");
        role.setNamePL("role-pl");
        return role;
    }

    private static void authenticate(String roleCode) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + roleCode));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "n/a", authorities));
    }
}
