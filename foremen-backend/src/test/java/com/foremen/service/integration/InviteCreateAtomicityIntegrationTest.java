package com.foremen.service.integration;

import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.UserService;
import com.foremen.service.mail.InvitationMailSender;
import com.foremen.service.model.UserServiceExtendedModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Integration test for create-time invite atomicity (FOR-03-02, Requirement 3.6).
 *
 * <p>Verifies that when invite-token persistence fails <em>after</em> the {@code UserEntity} row
 * has already been inserted and flushed inside the transactional {@code create(...)} path
 * ({@code UserService.create} → framework {@code afterCreate} hook → {@code UserService.afterCreate}
 * → {@code InviteService.issueInvite} → {@code InviteTokenDao.save}), the entire user-creation
 * transaction rolls back so that <b>no user row remains</b> and neither the user nor the invite
 * token is retained.
 *
 * <p><b>Failure-injection mechanism:</b> the {@link InviteTokenDao} bean is replaced with a
 * {@code @MockitoBean} whose {@code save(...)} throws a {@link ForemenApiException}, simulating an
 * invite-token persistence failure occurring strictly after the user insert+flush. The
 * {@link InvitationMailSender} is also mocked so no real SMTP dispatch is attempted (and, because
 * the token-persist failure is thrown before mail dispatch in {@code InviteService.generateAndSend},
 * to assert the mail sender is never reached).
 *
 * <p>The test class is intentionally <b>not</b> {@code @Transactional}: it invokes
 * {@code UserService.create(...)} (which manages its own transaction) and, after the exception
 * propagates, reads back from the database on a fresh transaction to prove the rollback discarded
 * the user row.
 *
 * <p><b>Repeatability:</b> each run uses a unique email generated from {@code System.nanoTime()}
 * (see {@link #uniqueEmail()}); a per-test {@code @AfterEach} teardown deletes any seeded role and
 * any residual user, so the suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 3.6
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
class InviteCreateAtomicityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON converter's text value
            // into the jsonb display_preferences column (the handling the deployed app relies on).
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private UserService userService;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /**
     * Replaces the real invite-token DAO so we can force {@code save(...)} to throw <em>after</em>
     * the user has already been persisted and flushed. This is the create-time atomicity trigger.
     */
    @MockitoBean
    private InviteTokenDao inviteTokenDao;

    /**
     * Mocked so no real SMTP is attempted; also lets us assert mail dispatch is never reached
     * because the token-persist failure precedes it.
     */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    private Long seededRoleId;

    @BeforeEach
    void setUp() {
        RoleEntity role = new RoleEntity();
        role.setCode("WORKER-ATOMICITY-" + System.nanoTime());
        role.setNameRU("Работник");
        role.setNamePL("Pracownik");
        role.setSystem(false);
        seededRoleId = roleDao.save(role).getId();
    }

    @AfterEach
    void tearDown() {
        // Remove any residual user (there should be none if rollback worked) and the seeded role,
        // so the scenario is repeatable without manual DB cleanup.
        userDao.findAll().forEach(userDao::delete);
        if (seededRoleId != null) {
            roleDao.findById(seededRoleId).ifPresent(roleDao::delete);
        }
    }

    private String uniqueEmail() {
        return "atomicity+" + System.nanoTime() + "@example.com";
    }

    private UserServiceExtendedModel createModel(String email) {
        return new UserServiceExtendedModel(
                null,
                "Atomicity Test User",
                email,
                null,
                seededRoleId,
                null,
                true,
                "ru",
                Map.of());
    }

    @Test
    @DisplayName("Invite-token persistence failing after the user insert rolls back the whole "
            + "transaction — no user row remains (Requirement 3.6)")
    void tokenPersistFailureRollsBackUserInsert() {
        // Force invite-token persistence to fail after the user row has been inserted+flushed.
        when(inviteTokenDao.save(any(InviteTokenEntity.class)))
                .thenThrow(new ForemenApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "error.invite.token.persist.failed"));

        String email = uniqueEmail();

        // Sanity: no user with this email exists before the create attempt.
        assertThat(userDao.findByEmail(email)).isEmpty();

        // The create transaction must fail because the (mocked) invite-token save throws.
        assertThatThrownBy(() -> userService.create(createModel(email)))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.invite.token.persist.failed"));

        // Atomicity (Requirement 3.6): the user insert performed earlier in the same transaction
        // must have been rolled back — no user row remains.
        Optional<com.foremen.dao.model.UserEntity> persisted = userDao.findByEmail(email);
        assertThat(persisted)
                .as("user insert must be rolled back when invite-token persistence fails")
                .isEmpty();

        // No user was retained overall (the only row we could have created is gone).
        assertThat(userDao.findAll())
                .as("no user should remain after the rolled-back create")
                .noneSatisfy(u -> assertThat(u.getEmail()).isEqualTo(email));

        // The failure occurred before any mail dispatch, so the mail sender is never invoked.
        verifyNoInteractions(invitationMailSender);
    }

    @Test
    @DisplayName("Successful create path is not affected — control: a working token save persists "
            + "an INVITED user")
    void successfulCreatePersistsInvitedUser() {
        // Control scenario: when the invite-token save succeeds, the user is committed as INVITED
        // with a null passwordHash, confirming the rollback in the other test is caused by the
        // injected failure and not by an unrelated create-path problem.
        when(inviteTokenDao.save(any(InviteTokenEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String email = uniqueEmail();

        UserServiceExtendedModel result = userService.create(createModel(email));
        assertThat(result.id()).isNotNull();

        com.foremen.dao.model.UserEntity persisted = userDao.findByEmail(email).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(persisted.getPasswordHash()).isNull();
    }
}
