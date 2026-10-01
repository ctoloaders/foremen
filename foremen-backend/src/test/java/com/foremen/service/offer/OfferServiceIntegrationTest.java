package com.foremen.service.offer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.OfferVisibilityStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Spring integration tests for {@link OfferService} — the offer-preparation lifecycle (FOR-05-07,
 * task 5.5). They exercise the real Spring-wired service against a real PostgreSQL (Testcontainers),
 * driving {@code prepareOffer}/{@code send}/{@code approve}/{@code withdraw} end-to-end through the
 * actual DAOs, the {@link OfferStatusMachine}, and the project-status advance, asserting the
 * lifecycle contract the task enumerates:
 *
 * <ul>
 *   <li>{@code prepareOffer} requires a {@code PRICED} estimate (R1.2) and rejects a
 *       non-{@code PRICED} one without creating an offer;</li>
 *   <li>{@code prepareOffer} seeds {@code selectedPackage} from the estimate's
 *       {@code appliedPackageCode} (R1.6) and is null-safe when the code is unset or unresolved
 *       (R1.7);</li>
 *   <li>the one-non-terminal-offer-per-project invariant (R1.4) rejects a second active offer;</li>
 *   <li>{@code send} drives the offer {@code DRAFT → SENT} and the project
 *       {@code READY_TO_OFFER → OFFERED} with derived visibility {@code ON_APPROVAL} (R3.2, R17.6);</li>
 *   <li>{@code approve} drives the offer to {@code APPROVED}, the project {@code OFFERED → APPROVED},
 *       and records the agreed revision (R3.5, R7.1).</li>
 * </ul>
 *
 * <p><b>Boot harness</b> mirrors the repo's established estimate service integration tests
 * ({@code EstimateAssignmentServiceIntegrationTest}): {@code @SpringBootTest(MOCK)} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema, so every fixture row (including the
 * {@code roles}/{@code users} rows the CLIENT actor needs) is created programmatically. The service
 * methods run in their own transactions; assertions read entities back inside a
 * {@link TransactionTemplate} so lazy state is initialised.
 *
 * <p><b>Actor / SecurityContext.</b> {@code OfferService} resolves the acting role from the security
 * context (ADMIN via authority, otherwise the role code of the user identified by the numeric
 * principal id). {@code send}/{@code withdraw} require an executor (MANAGER/ADMIN) and are driven as
 * ADMIN; {@code approve} is a CLIENT-only transition, so it is driven as a persisted {@code CLIENT}
 * user authenticated by its numeric id (mirroring {@code ProjectScopingIT#authenticate}).
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test, so the
 * suite re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 1.2, 1.4, 1.6, 1.7, 3.2, 3.5, 7.1, 17.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferServiceIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
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
    private OfferService offerService;

    @Autowired
    private OfferVisibilityResolver offerVisibilityResolver;

    @Autowired
    private OfferDao offerDao;
    @Autowired
    private EstimateDao estimateDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserDao userDao;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture codes/names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(status -> {
            // Offers (and their cascade children) first, then estimates, then projects — deepest FK
            // first — all keyed by the run-scoped project name so nothing else is touched.
            entityManager.createQuery(
                            "delete from OfferEntity o where o.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from EstimateEntity e where e.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // Run-scoped users (the CLIENT actor fixture). The shared "CLIENT" role is get-or-created
            // and intentionally NOT deleted (its code must literally equal "CLIENT", so it cannot be
            // run-scoped; reused across tests like the PLN currency).
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // Run-scoped offer packages.
            entityManager.createQuery("delete from OfferPackageEntity op where op.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "PLN" currency fixture is intentionally NOT deleted (get-or-create, reused).
        });
    }

    // =====================================================================================
    // prepareOffer: PRICED precondition (R1.2)
    // =====================================================================================

    @Test
    @DisplayName("prepareOffer creates a DRAFT rev-1 offer when the estimate is PRICED (R1.1/R1.2)")
    void prepareRequiresPricedEstimate_success() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);

        OfferEntity offer = offerService.prepareOffer(f.projectId);

        assertThat(offer.getId()).as("prepareOffer must persist an offer for a PRICED estimate").isNotNull();
        assertThat(offer.getStatus()).as("a fresh offer is DRAFT (R1.1)").isEqualTo(OfferStatus.DRAFT);
        assertThat(offer.getRevision()).as("a fresh offer is revision 1 (R1.5)").isEqualTo(1);
        assertThat(offer.getProject().getId()).isEqualTo(f.projectId);
        assertThat(offer.getEstimate().getId()).isEqualTo(f.estimateId);
    }

    @Test
    @DisplayName("prepareOffer rejects a non-PRICED (DRAFT) estimate and creates no offer (R1.2)")
    void prepareRejectsNonPricedEstimate() {
        Fixture f = createFixture(EstimateStatus.DRAFT, null);

        assertThatThrownBy(() -> offerService.prepareOffer(f.projectId))
                .as("prepareOffer must reject a non-PRICED estimate (R1.2)")
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.estimate.not.priced");

        tx().executeWithoutResult(status ->
                assertThat(offerDao.findByProjectIdOrderByIdAsc(f.projectId))
                        .as("no offer may be created when the estimate is not PRICED (R1.2)")
                        .isEmpty());
    }

    // =====================================================================================
    // prepareOffer: package seeding from appliedPackageCode (R1.6) + null-safe (R1.7)
    // =====================================================================================

    @Test
    @DisplayName("prepareOffer seeds selectedPackage from the estimate's appliedPackageCode (R1.6)")
    void prepareSeedsPackageFromAppliedPackageCode() {
        String packageCode = "COMFORT_" + runId;
        OfferPackageEntity pkg = createPackage(packageCode);
        Fixture f = createFixture(EstimateStatus.PRICED, packageCode);

        OfferEntity offer = offerService.prepareOffer(f.projectId);

        assertThat(offer.getSelectedPackage())
                .as("prepareOffer must seed selectedPackage from appliedPackageCode (R1.6)")
                .isNotNull();
        assertThat(offer.getSelectedPackage().getId())
                .as("the seeded package must be the one appliedPackageCode resolves to (R1.6)")
                .isEqualTo(pkg.getId());
    }

    @Test
    @DisplayName("prepareOffer leaves selectedPackage null when appliedPackageCode is unset (R1.7)")
    void prepareNullSafeWhenAppliedPackageCodeUnset() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);

        OfferEntity offer = offerService.prepareOffer(f.projectId);

        assertThat(offer.getSelectedPackage())
                .as("a null appliedPackageCode must yield selectedPackage = null without failing (R1.7)")
                .isNull();
    }

    @Test
    @DisplayName("prepareOffer leaves selectedPackage null when appliedPackageCode is unknown (R1.7)")
    void prepareNullSafeWhenAppliedPackageCodeUnresolved() {
        // An appliedPackageCode that does NOT resolve to any OfferPackage row.
        Fixture f = createFixture(EstimateStatus.PRICED, "NOPE_" + runId);

        OfferEntity offer = offerService.prepareOffer(f.projectId);

        assertThat(offer.getSelectedPackage())
                .as("an unknown appliedPackageCode must yield selectedPackage = null, not fail (R1.7)")
                .isNull();
    }

    // =====================================================================================
    // one-non-terminal-offer-per-project uniqueness (R1.4)
    // =====================================================================================

    @Test
    @DisplayName("prepareOffer rejects a second non-terminal offer for the same project (R1.4)")
    void prepareRejectsSecondActiveOffer() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);

        OfferEntity first = offerService.prepareOffer(f.projectId);
        assertThat(first.getStatus()).isEqualTo(OfferStatus.DRAFT);

        assertThatThrownBy(() -> offerService.prepareOffer(f.projectId))
                .as("a project may hold at most one non-terminal offer (R1.4)")
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.active.exists");

        tx().executeWithoutResult(status ->
                assertThat(offerDao.findByProjectIdOrderByIdAsc(f.projectId))
                        .as("only the first offer may exist (R1.4)")
                        .hasSize(1));
    }

    @Test
    @DisplayName("a new offer may be prepared once the prior one is terminal (WITHDRAWN) (R1.4)")
    void prepareAllowedAfterPriorOfferTerminal() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);

        OfferEntity first = offerService.prepareOffer(f.projectId);
        authenticateAdmin();
        offerService.withdraw(first.getId());

        // With the prior offer terminal (WITHDRAWN), a new active offer is allowed (R1.4).
        OfferEntity second = offerService.prepareOffer(f.projectId);
        assertThat(second.getId())
                .as("a fresh offer may be prepared once the prior one is terminal (R1.4)")
                .isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(OfferStatus.DRAFT);
    }

    // =====================================================================================
    // send: DRAFT -> SENT, project READY_TO_OFFER -> OFFERED, visibility ON_APPROVAL (R3.2/R17.6)
    // =====================================================================================

    @Test
    @DisplayName("send drives the offer DRAFT->SENT, the project READY_TO_OFFER->OFFERED, and "
            + "visibility to ON_APPROVAL (R3.2, R17.6)")
    void sendDrivesOfferAndProjectAndVisibility() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);
        OfferEntity offer = offerService.prepareOffer(f.projectId);
        setProjectStatus(f.projectId, ProjectStatus.READY_TO_OFFER);

        authenticateAdmin();
        OfferEntity sent = offerService.send(offer.getId());

        assertThat(sent.getStatus())
                .as("send must transition the offer DRAFT->SENT (R3.2)").isEqualTo(OfferStatus.SENT);
        assertThat(offerVisibilityResolver.visibilityOf(sent.getStatus()))
                .as("a SENT offer's derived visibility is ON_APPROVAL (R17.6)")
                .isEqualTo(OfferVisibilityStatus.ON_APPROVAL);

        tx().executeWithoutResult(status -> {
            ProjectEntity project = projectDao.findById(f.projectId).orElseThrow();
            assertThat(project.getStatus())
                    .as("send must advance the project READY_TO_OFFER->OFFERED (R3.2)")
                    .isEqualTo(ProjectStatus.OFFERED);
        });
    }

    // =====================================================================================
    // approve: OFFERED -> APPROVED, records the agreed revision (R3.5/R7.1)
    // =====================================================================================

    @Test
    @DisplayName("approve drives the offer SENT->APPROVED, the project OFFERED->APPROVED, and "
            + "records the agreed revision (R3.5, R7.1)")
    void approveDrivesOfferAndProjectAndRecordsAgreedRevision() {
        Fixture f = createFixture(EstimateStatus.PRICED, null);
        OfferEntity offer = offerService.prepareOffer(f.projectId);
        setProjectStatus(f.projectId, ProjectStatus.READY_TO_OFFER);

        // Executor sends the offer (DRAFT->SENT, project READY_TO_OFFER->OFFERED).
        authenticateAdmin();
        offerService.send(offer.getId());

        // CLIENT approves the SENT offer (APPROVE is a CLIENT-only transition, R3.5).
        Long clientUserId = createClientUser();
        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferEntity approved = offerService.approve(offer.getId());

        assertThat(approved.getStatus())
                .as("approve must transition the offer to APPROVED (R3.5)").isEqualTo(OfferStatus.APPROVED);
        assertThat(approved.getApprovedRevision())
                .as("approve must record the current revision as the Agreed_Offer_Version (R7.1)")
                .isEqualTo(approved.getRevision());

        tx().executeWithoutResult(status -> {
            ProjectEntity project = projectDao.findById(f.projectId).orElseThrow();
            assertThat(project.getStatus())
                    .as("approve must advance the project OFFERED->APPROVED (R3.5)")
                    .isEqualTo(ProjectStatus.APPROVED);
        });
    }

    // =====================================================================================
    // SecurityContext helpers (mirror ProjectScopingIT#authenticate)
    // =====================================================================================

    /** Authenticates as an executor via the ADMIN authority (resolved by OfferService without a DB user). */
    private void authenticateAdmin() {
        authenticateAs("admin-" + runId, "ROLE_ADMIN");
    }

    private void authenticateAs(Object principal, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(principal), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long estimateId;
    }

    /**
     * Builds a self-contained lifecycle fixture in one transaction: a DRAFT project + an estimate at
     * the given {@code estimateStatus} (with a get-or-created reference PLN currency for the
     * estimate's NOT NULL {@code currency_id} FK) and the given {@code appliedPackageCode} (nullable).
     * No rooms/lines are seeded — the lifecycle methods under test (prepare/send/approve/withdraw and
     * uniqueness/package-seeding) never traverse lines, and {@code recomputeTotals} treats a
     * line-less estimate as a zero-net offer.
     */
    private Fixture createFixture(EstimateStatus estimateStatus, String appliedPackageCode) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            CurrencyEntity currency = persistCurrency();

            ProjectEntity project = new ProjectEntity();
            project.setName("Offer IT Project " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.DRAFT);
            projectDao.save(project);

            EstimateEntity estimate = new EstimateEntity();
            estimate.setProject(project);
            estimate.setCurrency(currency);
            estimate.setStatus(estimateStatus);
            estimate.setAppliedPackageCode(appliedPackageCode);
            estimateDao.save(estimate);

            f.projectId = project.getId();
            f.estimateId = estimate.getId();
        });
        return f;
    }

    /** Sets the project's status directly (to stage it at READY_TO_OFFER before a send). */
    private void setProjectStatus(Long projectId, ProjectStatus projectStatus) {
        tx().executeWithoutResult(status -> {
            ProjectEntity project = projectDao.findById(projectId).orElseThrow();
            project.setStatus(projectStatus);
            projectDao.save(project);
        });
    }

    /** Persists an active {@link OfferPackageEntity} with the given (run-scoped) code. */
    private OfferPackageEntity createPackage(String code) {
        return tx().execute(status -> {
            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode(code);
            pkg.setOrderNo(1);
            pkg.setNameRU("Пакет " + runId);
            pkg.setNamePL("Pakiet " + runId);
            pkg.setActive(true);
            return offerPackageDao.save(pkg);
        });
    }

    /**
     * Persists a {@code CLIENT} role + an active user of that role and returns the user id — the
     * CLIENT actor for the {@code approve} transition ({@code OfferService} resolves the acting role
     * from the numeric principal id → the user's role code).
     */
    private Long createClientUser() {
        return tx().execute(status -> {
            // The role code must be exactly "CLIENT" for OfferStatusMachine to treat the actor as the
            // client (the service reads user.getRole().getCode()). Because the code cannot be
            // run-scoped, get-or-create the single shared "CLIENT" role (like the PLN currency) so it
            // never violates the unique code constraint across the class's tests.
            RoleEntity role = roleDao.findByCode("CLIENT").orElseGet(() -> {
                RoleEntity r = new RoleEntity();
                r.setCode("CLIENT");
                r.setNameRU("Клиент");
                r.setNamePL("Klient");
                return roleDao.save(r);
            });

            UserEntity user = new UserEntity();
            user.setName("Client " + runId);
            user.setEmail("client+" + runId + "@example.com");
            user.setRole(role);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale("ru");
            user = userDao.save(user);
            return user.getId();
        });
    }

    private CurrencyEntity persistCurrency() {
        return currencyDao.findByCode("PLN").orElseGet(() -> {
            CurrencyEntity currency = new CurrencyEntity();
            currency.setCode("PLN");
            currency.setSymbol("zł");
            currency.setNameRU("Злотый");
            currency.setNamePL("Złoty");
            currency.setActive(true);
            return currencyDao.save(currency);
        });
    }
}
