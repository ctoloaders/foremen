package com.foremen.service;

// Feature: FOR-05-09-team-selection, task 14.3 — Worker_Invitation_Flow (POST /api/users/worker/{id}/invite)
// Requirements 13.17, 13.18

import com.foremen.controller.model.WorkerInvitationResponse;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code Worker_Invitation_Flow}
 * ({@link WorkerInvitationService#invite(Long)}, FOR-05-09 Requirements 13.17&ndash;13.18).
 *
 * <p>Collaborators are mocked ({@link UserDao}, {@link InviteService},
 * {@link ForemenPermissionEvaluator}) so the orchestration logic — the dual-permission guard, the
 * canonical rejection order, and the "send no email / change no state on any rejection" guarantee —
 * is exercised in isolation from persistence and mail. The {@link ForemenPermissionEvaluator} is a
 * genuine mock (not the real ABAC matrix), so a test authenticates by stubbing both the
 * SecurityContext authority (for the role-code extraction) and the evaluator decision.
 */
@DisplayName("WorkerInvitationService — Worker_Invitation_Flow (Req 13.17/13.18)")
class WorkerInvitationServiceTest {

    private static final long WORKER_ID = 7L;

    private UserDao userDao;
    private InviteService inviteService;
    private ForemenPermissionEvaluator permissionEvaluator;
    private WorkerInvitationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        userDao = Mockito.mock(UserDao.class);
        inviteService = Mockito.mock(InviteService.class);
        permissionEvaluator = Mockito.mock(ForemenPermissionEvaluator.class);
        service = new WorkerInvitationService(userDao, inviteService, permissionEvaluator);
        // Default: a MANAGER caller who holds PROJECT_MEMBERS CREATE (the PROJECTS EDIT half is the
        // controller's @RequiresPermission, out of scope for this service-level test).
        authenticate("MANAGER");
        when(permissionEvaluator.isAllowed("MANAGER", "PROJECT_MEMBERS", "CREATE")).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------------------------
    // Success path (Req 13.17)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("success — invite / re-send a not-yet-activated WORKER with a stored email")
    class Success {

        @Test
        @DisplayName("never-invited worker (no password) is invited and reflects 'invited', 200")
        void invitesNeverInvitedWorker() {
            UserEntity worker = worker(WORKER_ID, "crew@example.com", null, UserStatus.INVITED);
            // The record-flow user starts "not invited"; inviteWorker flips it to INVITED and persists.
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            WorkerInvitationResponse response = service.invite(WORKER_ID);

            verify(inviteService, times(1)).inviteWorker(worker);
            assertThat(response.id()).isEqualTo(WORKER_ID);
            assertThat(response.email()).isEqualTo("crew@example.com");
            // The service reads the user's status after inviteWorker; the fake leaves it INVITED.
            assertThat(response.status()).isEqualTo(UserStatus.INVITED.name());
        }

        @Test
        @DisplayName("re-sending to an already-invited (not activated) worker is allowed, 200")
        void reSendsToAlreadyInvitedWorker() {
            UserEntity worker = worker(WORKER_ID, "crew@example.com", null, UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            service.invite(WORKER_ID);

            // A fresh link is issued (the mint step inside inviteWorker invalidates earlier ones).
            verify(inviteService, times(1)).inviteWorker(worker);
        }

        @Test
        @DisplayName("ADMIN caller is allowed through the evaluator bypass")
        void adminCallerSucceeds() {
            SecurityContextHolder.clearContext();
            authenticate("ADMIN");
            when(permissionEvaluator.isAllowed("ADMIN", "PROJECT_MEMBERS", "CREATE")).thenReturn(true);
            UserEntity worker = worker(WORKER_ID, "crew@example.com", null, UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            service.invite(WORKER_ID);

            verify(inviteService, times(1)).inviteWorker(worker);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rejection order (Req 13.18) — each rejection sends no email and changes no state
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("rejections — canonical order, no email sent on any rejection (Req 13.18)")
    class Rejections {

        @Test
        @DisplayName("missing PROJECT_MEMBERS CREATE -> 403 error.access.denied, user never loaded")
        void missingProjectMembersCreateIs403() {
            SecurityContextHolder.clearContext();
            authenticate("FOREMAN"); // READ-only on PROJECT_MEMBERS
            when(permissionEvaluator.isAllowed("FOREMAN", "PROJECT_MEMBERS", "CREATE")).thenReturn(false);

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
            // Permission is step 1: the user is not even looked up, and no email is sent.
            verify(userDao, never()).findById(any());
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("unresolved caller role -> 403 error.access.denied")
        void unresolvedRoleIs403() {
            SecurityContextHolder.clearContext(); // no authentication at all

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getMessageCode()).isEqualTo("error.access.denied");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("non-existent user -> 404 error.entity.not.found")
        void nonExistentUserIs404() {
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.empty());

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("non-WORKER user -> 400 error.worker.invite.not.allowed")
        void nonWorkerUserIs400() {
            UserEntity client = user(WORKER_ID, "CLIENT", "c@example.com", null, UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(client));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.invite.not.allowed");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("already-activated worker (password set) -> 409 error.worker.already.active")
        void activatedByPasswordIs409() {
            UserEntity worker = worker(WORKER_ID, "crew@example.com", "$2a$hash", UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.already.active");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("already-activated worker (ACTIVE status) -> 409 error.worker.already.active")
        void activatedByStatusIs409() {
            UserEntity worker = worker(WORKER_ID, "crew@example.com", null, UserStatus.ACTIVE);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.already.active");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("worker with no stored email -> 400 error.worker.email.required")
        void noStoredEmailIs400() {
            UserEntity worker = worker(WORKER_ID, null, null, UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.email.required");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("blank stored email is treated as no email -> 400 error.worker.email.required")
        void blankStoredEmailIs400() {
            UserEntity worker = worker(WORKER_ID, "   ", null, UserStatus.INVITED);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.email.required");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("activation state is checked before the stored-email check (order)")
        void activationBeatsMissingEmail() {
            // An activated worker that also has no email must surface the 409 (activation) first,
            // proving the canonical order: activation (step 4) before email-required (step 5).
            UserEntity worker = worker(WORKER_ID, null, null, UserStatus.ACTIVE);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(worker));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.already.active");
            verify(inviteService, never()).inviteWorker(any());
        }

        @Test
        @DisplayName("non-WORKER is rejected before the activation check (order)")
        void roleBeatsActivation() {
            // An ACTIVE CLIENT must surface the 400 non-WORKER (step 3) rather than the 409
            // already-active (step 4), proving role precedes activation in the canonical order.
            UserEntity client = user(WORKER_ID, "CLIENT", "c@example.com", "$2a$hash", UserStatus.ACTIVE);
            when(userDao.findById(WORKER_ID)).thenReturn(Optional.of(client));

            ForemenApiException ex = catchThrowableOfType(
                    () -> service.invite(WORKER_ID), ForemenApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.worker.invite.not.allowed");
            verify(inviteService, never()).inviteWorker(any());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static UserEntity worker(Long id, String email, String passwordHash, UserStatus status) {
        return user(id, "WORKER", email, passwordHash, status);
    }

    private static UserEntity user(Long id, String roleCode, String email, String passwordHash, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("Worker " + id);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setStatus(status);
        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "id", 100L);
        role.setCode(roleCode);
        role.setNameRU("role-ru");
        role.setNamePL("role-pl");
        user.setRole(role);
        return user;
    }

    private static void authenticate(String roleCode) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + roleCode));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "n/a", authorities));
    }
}
