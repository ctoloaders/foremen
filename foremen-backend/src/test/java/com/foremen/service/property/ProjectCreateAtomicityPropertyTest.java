package com.foremen.service.property;

// Feature: FOR-04-13-project, Property 2: Project creation is all-or-nothing

import com.foremen.controller.model.ClientBlock;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.NewClientInput;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.controller.model.ProjectMemberInput;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectMemberService;
import com.foremen.service.ProjectService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Property test for {@link ProjectService#createProject(CreateProjectRequest)} (FOR-04-13).
 *
 * <p><b>Property 2: Project creation is all-or-nothing.</b> For any {@code CreateProjectRequest}
 * whose processing fails at some step — the project save, one of the member assignments, or the
 * client processing (existing-client assign / new-client registration) — the injected failure
 * propagates out of {@code createProject} rather than being swallowed. Because {@code createProject}
 * is {@code @Transactional}, propagating the exception is exactly what triggers the rollback of the
 * whole unit (no project, no {@code project_members} row, and no client user persists); the DB-level
 * rollback itself is asserted by the Testcontainers integration test (9.1). At the unit level this
 * property asserts (a) the same exception propagates out of {@code createProject}, and (b) no value
 * is returned when any step fails.
 *
 * <p><b>Validates: Requirements 2.6, 2.9</b>
 *
 * <p>Collaborators are mocked ({@link ProjectDao}, {@link ProjectMemberService},
 * {@link ClientRegistrationService}, {@link RoleDao}, {@link GooglePlacesService}) so the test
 * isolates the orchestrator's exception-propagation contract from the persistence layer. A generated
 * "failing step" selects which collaborator throws.
 */
class ProjectCreateAtomicityPropertyTest {

    private static final long CLIENT_ROLE_ID = 42L;

    /** Which step of the orchestration is configured to throw. */
    private enum FailingStep {
        PROJECT_SAVE,   // projectDao.save(...) throws
        MEMBER_ASSIGN,  // one members[i] assign throws (only when there is at least one member)
        CLIENT_STEP     // the client processing throws (only when a client block is present)
    }

    // ---- Property 2: any failing step propagates and yields no result (Req 2.6, 2.9) ----

    @Property(tries = 100)
    void anyFailingStepPropagatesAndReturnsNoResult(
            @ForAll("names") String name,
            @ForAll @IntRange(min = 0, max = 5) int memberCount,
            @ForAll("clientKinds") ClientKind clientKind,
            @ForAll("failingSteps") FailingStep failingStep,
            @ForAll @IntRange(min = 0, max = 4) int failingMemberSeed) {

        Fixture f = new Fixture();

        // Build the request: N members, optionally a client block.
        List<ProjectMemberInput> members = buildMembers(memberCount);
        ClientBlock client = buildClient(clientKind, name);
        CreateProjectRequest request = new CreateProjectRequest(
                name, null, null, null, null, null, null, null, null, null, members, client);

        // A member-assign failure is only meaningful when there is at least one member, and a
        // client-step failure only when a client block is present; otherwise fall back to failing
        // the always-present project save so the request still fails at exactly one step.
        FailingStep effectiveStep = normalizeStep(failingStep, memberCount, clientKind);

        RuntimeException injected = injectFailure(f, effectiveStep, members, failingMemberSeed, clientKind);

        // When any step fails, the exception must propagate out of createProject (so the
        // @Transactional boundary rolls the whole unit back) and no ProjectCreateResponse is
        // produced.
        ProjectCreateResponse[] result = new ProjectCreateResponse[1];
        Throwable thrown = catchThrowable(() -> result[0] = f.service.createProject(request));

        assertThat(thrown)
                .as("failing step %s must propagate out of createProject", effectiveStep)
                .isNotNull()
                .isSameAs(injected);
        assertThat(result[0])
                .as("no ProjectCreateResponse is returned when a step fails")
                .isNull();
    }

    /**
     * Complementary invariant: when <em>no</em> step is injected to fail, {@code createProject}
     * completes normally and returns a non-null response. This anchors the atomicity property — the
     * failure path is meaningful only because the happy path otherwise succeeds.
     */
    @Property(tries = 100)
    void successfulRequestReturnsAResponse(
            @ForAll("names") String name,
            @ForAll @IntRange(min = 0, max = 5) int memberCount,
            @ForAll("clientKinds") ClientKind clientKind) {

        Fixture f = new Fixture();

        List<ProjectMemberInput> members = buildMembers(memberCount);
        ClientBlock client = buildClient(clientKind, name);
        CreateProjectRequest request = new CreateProjectRequest(
                name, null, null, null, null, null, null, null, null, null, members, client);

        ProjectCreateResponse response = f.service.createProject(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
    }

    // ---- Failure injection ----

    /**
     * Configures the selected step's collaborator to throw and returns the exception instance so the
     * test can assert it is the very throwable that propagates.
     */
    private RuntimeException injectFailure(Fixture f, FailingStep step, List<ProjectMemberInput> members,
                                           int failingMemberSeed, ClientKind clientKind) {
        switch (step) {
            case PROJECT_SAVE -> {
                RuntimeException ex = new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.test.save");
                when(f.projectDao.save(any(ProjectEntity.class))).thenThrow(ex);
                return ex;
            }
            case MEMBER_ASSIGN -> {
                int idx = failingMemberSeed % members.size();
                long failingUserId = members.get(idx).userId();
                RuntimeException ex = new ForemenApiException(
                        HttpStatus.CONFLICT, "error.project.member.duplicate", failingUserId, 1L);
                // Make the member assign throw for the chosen member's userId; others succeed.
                when(f.projectMemberService.assign(org.mockito.ArgumentMatchers.eq(failingUserId), anyLong(), anyLong()))
                        .thenThrow(ex);
                return ex;
            }
            case CLIENT_STEP -> {
                if (clientKind == ClientKind.EXISTING) {
                    // Existing-client assign throws (e.g. duplicate membership).
                    RuntimeException ex = new ForemenApiException(
                            HttpStatus.CONFLICT, "error.project.member.duplicate", 999L, 1L);
                    when(f.projectMemberService.assign(
                            org.mockito.ArgumentMatchers.eq(EXISTING_CLIENT_ID), anyLong(), anyLong()))
                            .thenThrow(ex);
                    return ex;
                } else {
                    // New-client registration throws (e.g. duplicate email).
                    RuntimeException ex = new ForemenApiException(
                            HttpStatus.CONFLICT, "error.user.email.already.exists");
                    when(f.clientRegistrationService.register(any())).thenThrow(ex);
                    return ex;
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
    }

    /**
     * Downgrades an impossible failing step to {@link FailingStep#PROJECT_SAVE} (always present) so
     * every generated request still fails at exactly one real step: MEMBER_ASSIGN needs at least one
     * member, CLIENT_STEP needs a client block.
     */
    private static FailingStep normalizeStep(FailingStep step, int memberCount, ClientKind clientKind) {
        if (step == FailingStep.MEMBER_ASSIGN && memberCount == 0) {
            return FailingStep.PROJECT_SAVE;
        }
        if (step == FailingStep.CLIENT_STEP && clientKind == ClientKind.NONE) {
            return FailingStep.PROJECT_SAVE;
        }
        return step;
    }

    // ---- Request building ----

    private static final long EXISTING_CLIENT_ID = 7_000L;
    private static final AtomicLong USER_ID_SEQ = new AtomicLong(1);

    private static List<ProjectMemberInput> buildMembers(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ProjectMemberInput(USER_ID_SEQ.getAndIncrement(), 1L))
                .toList();
    }

    private enum ClientKind { NONE, EXISTING, NEW }

    private static ClientBlock buildClient(ClientKind kind, String name) {
        return switch (kind) {
            case NONE -> null;
            case EXISTING -> new ClientBlock(EXISTING_CLIENT_ID, null);
            case NEW -> new ClientBlock(null, new NewClientInput(name, "c" + USER_ID_SEQ.getAndIncrement() + "@example.com", null, null));
        };
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link ProjectService} with mocked collaborators. By default every step
     * succeeds: {@code projectDao.save} returns an entity with a generated id, member/client assigns
     * succeed, the CLIENT role resolves, and new-client registration succeeds. Individual tests
     * inject a single failing step.
     */
    private static final class Fixture {
        final ProjectDao projectDao = Mockito.mock(ProjectDao.class);
        final ProjectServiceMapper mapper = Mockito.mock(ProjectServiceMapper.class);
        final ProjectAccessCache projectAccessCache = Mockito.mock(ProjectAccessCache.class);
        final AuditLogDao auditLogDao = Mockito.mock(AuditLogDao.class);
        final EntityManager entityManager = Mockito.mock(EntityManager.class);
        final ProjectMemberService projectMemberService = Mockito.mock(ProjectMemberService.class);
        final ClientRegistrationService clientRegistrationService = Mockito.mock(ClientRegistrationService.class);
        final RoleDao roleDao = Mockito.mock(RoleDao.class);
        final GooglePlacesService googlePlacesService = Mockito.mock(GooglePlacesService.class);
        final RoleEntity clientRole = clientRole();
        final ProjectService service;

        Fixture() {
            // Happy-path project save: assign a generated id and echo the entity back.
            AtomicLong idSeq = new AtomicLong(1);
            Answer<ProjectEntity> saveAnswer = inv -> {
                ProjectEntity e = inv.getArgument(0);
                e.setId(idSeq.getAndIncrement());
                return e;
            };
            when(projectDao.save(any(ProjectEntity.class))).thenAnswer(saveAnswer);
            when(projectMemberService.assign(anyLong(), anyLong(), anyLong()))
                    .thenAnswer(inv -> member(inv.getArgument(0)));
            when(roleDao.findByCode("CLIENT")).thenReturn(Optional.of(clientRole));
            when(clientRegistrationService.register(any())).thenReturn(null);

            service = new ProjectService(
                    projectDao, mapper, projectAccessCache, auditLogDao, entityManager,
                    projectMemberService, clientRegistrationService, roleDao, googlePlacesService);
        }
    }

    private static ProjectMemberEntity member(Long userId) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setName("User " + userId);
        m.setUser(user);
        m.setProjectRole(clientRole());
        return m;
    }

    private static RoleEntity clientRole() {
        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "id", CLIENT_ROLE_ID);
        role.setCode("CLIENT");
        role.setNameRU("Клиент");
        role.setNamePL("Klient");
        return role;
    }

    // ---- Generators ----

    /** Non-blank project names within the 1..255 bound. */
    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(60).filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<ClientKind> clientKinds() {
        return Arbitraries.of(ClientKind.NONE, ClientKind.EXISTING, ClientKind.NEW);
    }

    @Provide
    Arbitrary<FailingStep> failingSteps() {
        return Arbitraries.of(FailingStep.PROJECT_SAVE, FailingStep.MEMBER_ASSIGN, FailingStep.CLIENT_STEP);
    }
}
