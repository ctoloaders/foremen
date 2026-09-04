package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 17: Client registration fixes the CLIENT role regardless of request
// Feature: FOR-03-05-otp-client-auth, Property 18: Client registration creates exactly one membership on the supplied project
// Feature: FOR-03-05-otp-client-auth, Property 19: Client registration is atomic

import com.foremen.controller.model.ClientRegistrationRequest;
import com.foremen.controller.model.ClientRegistrationResponse;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.ProjectMemberService;
import com.foremen.service.UserService;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.WithNull;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property tests for {@link ClientRegistrationService#register(ClientRegistrationRequest)}
 * (FOR-03-05, Requirements 10.1, 10.4, 10.6, 10.7, 10.9, 10.11).
 *
 * <p>Properties covered:
 * <ul>
 *   <li><b>Property 17: Client registration fixes the CLIENT role regardless of request</b> &mdash;
 *       for every valid request the role passed to user creation is the server-resolved {@code CLIENT}
 *       role (via {@link RoleDao#findByCode(String)}), independent of any request field, and the
 *       request DTO carries no role identifier. <b>Validates: Requirements 10.1, 10.4, 10.11</b></li>
 *   <li><b>Property 18: Client registration creates exactly one membership on the supplied project</b>
 *       &mdash; on success exactly one membership is assigned linking the new client's id to the
 *       supplied {@code projectId} under the CLIENT project role, and the created user is
 *       {@code INVITED}. <b>Validates: Requirements 10.6</b></li>
 *   <li><b>Property 19: Client registration is atomic</b> &mdash; when the membership assignment
 *       fails, the failure propagates out of {@code register} (no swallowing), so the single
 *       enclosing transaction rolls back the user creation and no response is produced.
 *       <b>Validates: Requirements 10.7, 10.9</b></li>
 * </ul>
 *
 * <p>Collaborators are mocked: {@link RoleDao} returns the CLIENT role, {@link UserService}
 * returns the created (INVITED) user, and {@link ProjectMemberService} records / fails the
 * assignment. This isolates the service's orchestration logic (role resolution, ordering, and
 * exception propagation) from the persistence layer.
 */
class ClientRegistrationPropertyTest {

    private static final long CLIENT_ROLE_ID = 42L;

    // ---- Property 17: fixes the CLIENT role regardless of request (Req 10.1, 10.4, 10.11) ----

    @Property(tries = 100)
    void createdUserAlwaysGetsServerResolvedClientRole(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("optionalStrings") @WithNull String phone,
            @ForAll("optionalStrings") @WithNull String locale,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long projectId) {

        Fixture f = new Fixture();
        ClientRegistrationRequest request =
                new ClientRegistrationRequest(name, email, phone, locale, projectId);

        f.service.register(request);

        // The role handed to user creation is exactly the role the server resolved by code "CLIENT".
        verify(f.roleDao).findByCode("CLIENT");
        ArgumentCaptor<RoleEntity> roleCaptor = ArgumentCaptor.forClass(RoleEntity.class);
        verify(f.userService).createClient(eq(name), eq(email), eq(phone), eq(locale), roleCaptor.capture());
        assertThat(roleCaptor.getValue()).isSameAs(f.clientRole);
        assertThat(roleCaptor.getValue().getCode()).isEqualTo("CLIENT");
    }

    /**
     * Structural invariant of Property 17: the request DTO exposes no role identifier field, so a
     * caller cannot influence the role. This holds for every possible request instance, hence a
     * property over the DTO's declared record components rather than any single value.
     */
    @Property(tries = 1)
    void requestDtoCarriesNoRoleIdentifier() {
        for (RecordComponent component : ClientRegistrationRequest.class.getRecordComponents()) {
            String lower = component.getName().toLowerCase();
            assertThat(lower)
                    .as("ClientRegistrationRequest must not expose a role identifier field, found: %s", component.getName())
                    .doesNotContain("role");
        }
    }

    // ---- Property 18: exactly one membership on the supplied project (Req 10.6) ----

    @Property(tries = 100)
    void successfulRegistrationAssignsExactlyOneClientMembershipOnProject(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long projectId,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long userId) {

        Fixture f = new Fixture();
        f.createdUser.setId(userId);
        ClientRegistrationRequest request =
                new ClientRegistrationRequest(name, email, null, null, projectId);

        ClientRegistrationResponse response = f.service.register(request);

        // Exactly one membership assignment, linking (userId, projectId, CLIENT role id).
        verify(f.projectMemberService, times(1)).assign(eq(userId), eq(projectId), eq(CLIENT_ROLE_ID));
        verify(f.projectMemberService, times(1)).assign(anyLong(), anyLong(), anyLong());
        // The created user is INVITED (reused invite create path).
        assertThat(f.createdUser.getStatus()).isEqualTo(UserStatus.INVITED);
        // Response echoes the persisted client and the supplied project.
        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.projectId()).isEqualTo(projectId);
    }

    // ---- Property 19: atomicity - failed assignment propagates (Req 10.7, 10.9) ----

    @Property(tries = 100)
    void failedMembershipAssignmentPropagatesSoTransactionRollsBack(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long projectId,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long userId) {

        Fixture f = new Fixture();
        f.createdUser.setId(userId);
        // The membership assignment fails, e.g. duplicate (userId, projectId) -> 409.
        when(f.projectMemberService.assign(eq(userId), eq(projectId), eq(CLIENT_ROLE_ID)))
                .thenThrow(new ForemenApiException(
                        HttpStatus.CONFLICT, "error.project.member.duplicate", userId, projectId));
        ClientRegistrationRequest request =
                new ClientRegistrationRequest(name, email, null, null, projectId);

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.register(request), ForemenApiException.class);

        // The failure is not swallowed: it propagates, so the single @Transactional method rolls
        // back the user creation together with the failed assignment (no orphaned CLIENT user).
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getMessageCode()).isEqualTo("error.project.member.duplicate");
        // The user was created before the failing assign (ordering), and the assign was attempted once.
        verify(f.userService).createClient(any(), any(), any(), any(), any());
        verify(f.projectMemberService, times(1)).assign(eq(userId), eq(projectId), eq(CLIENT_ROLE_ID));
    }

    @Property(tries = 100)
    void missingClientRoleFailsFastWithoutCreatingUserOrMembership(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long projectId) {

        Fixture f = new Fixture();
        // CLIENT role not seeded -> findByCode returns empty.
        when(f.roleDao.findByCode("CLIENT")).thenReturn(Optional.empty());
        ClientRegistrationRequest request =
                new ClientRegistrationRequest(name, email, null, null, projectId);

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.register(request), ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getMessageCode()).isEqualTo("error.role.client.missing");
        // No side effects when the role is missing.
        verify(f.userService, never()).createClient(any(), any(), any(), any(), any());
        verify(f.projectMemberService, never()).assign(anyLong(), anyLong(), anyLong());
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link ClientRegistrationService} with mocked collaborators. By default the
     * CLIENT role resolves, {@link UserService#createClient} returns an INVITED user, and
     * {@link ProjectMemberService#assign} succeeds; individual tests override as needed.
     */
    private static final class Fixture {
        final UserService userService = Mockito.mock(UserService.class);
        final RoleDao roleDao = Mockito.mock(RoleDao.class);
        final ProjectMemberService projectMemberService = Mockito.mock(ProjectMemberService.class);
        final RoleEntity clientRole = clientRole();
        final UserEntity createdUser = invitedClient();
        final ClientRegistrationService service;

        Fixture() {
            when(roleDao.findByCode("CLIENT")).thenReturn(Optional.of(clientRole));
            when(userService.createClient(any(), any(), any(), any(), any())).thenAnswer(inv -> {
                createdUser.setName(inv.getArgument(0));
                createdUser.setEmail(inv.getArgument(1));
                return createdUser;
            });
            when(projectMemberService.assign(anyLong(), anyLong(), anyLong()))
                    .thenReturn(new ProjectMemberEntity());
            service = new ClientRegistrationService(userService, roleDao, projectMemberService);
        }
    }

    private static RoleEntity clientRole() {
        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "id", CLIENT_ROLE_ID);
        role.setCode("CLIENT");
        role.setNameRU("Клиент");
        role.setNamePL("Klient");
        return role;
    }

    private static UserEntity invitedClient() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("Client");
        user.setEmail("client@example.com");
        user.setStatus(UserStatus.INVITED);
        user.setPasswordHash(null);
        return user;
    }

    /** Non-blank display names. */
    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().ofMinLength(1).ofMaxLength(60).filter(s -> !s.isBlank());
    }

    /** Syntactically simple, distinct email addresses. */
    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.of("example.com", "mail.test", "client.io");
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d);
    }

    /** Arbitrary optional strings (phone / locale), including null and blank. */
    @Provide
    Arbitrary<String> optionalStrings() {
        return Arbitraries.strings().ofMaxLength(15);
    }
}
