package com.foremen.service.offer;

import java.math.BigDecimal;
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
import com.foremen.dao.OfferNegotiationRoundDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.DiscountKind;
import com.foremen.dao.model.DiscountScope;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.NegotiationRoundKind;
import com.foremen.dao.model.NegotiationRoundStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferNegotiationRoundEntity;
import com.foremen.dao.model.OfferStatus;
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
 * Spring integration tests for {@link NegotiationService} — the two-sided offer negotiation thread
 * (FOR-05-07, task 7.5). They exercise the real Spring-wired service against a real PostgreSQL
 * (Testcontainers), driving {@code openDiscountRequest}/{@code managerPropose}/{@code managerReject}/
 * {@code clientAccept} end-to-end through the actual DAOs, the {@link OfferStatusMachine}, and the
 * {@link com.foremen.service.EscalationPolicy}, asserting the negotiation contract the task
 * enumerates:
 *
 * <ul>
 *   <li>a client discount request (no figure) drives the offer {@code SENT → CHANGES_REQUESTED} and
 *       records a {@code DISCOUNT_REQUEST} round with no {@code value}/{@code valueKind} (R3.3, R4.1);</li>
 *   <li>a manager proposal drives {@code CHANGES_REQUESTED → COUNTERED} (R3.4);</li>
 *   <li>a manager rejection with a blank explanation is rejected server-side and records no rejection
 *       (R4.8);</li>
 *   <li>a client acceptance materializes the proposal into an applied {@code OfferDiscount} and bumps
 *       the offer revision by one (R4.5);</li>
 *   <li>a broader-scope ({@code GLOBAL}) proposition supersedes the narrower still-{@code OPEN}
 *       propositions it subsumes (R4.9);</li>
 *   <li>a {@code MANAGER_PROPOSAL} over the escalation threshold is rejected for a plain MANAGER
 *       ({@code 409 error.offer.escalation.required}) but allowed for an ADMIN (R6.3/R6.5).</li>
 * </ul>
 *
 * <p><b>Boot harness</b> mirrors {@link OfferServiceIntegrationTest}: {@code @SpringBootTest(MOCK)} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} (Liquibase disabled, Hibernate
 * {@code create-drop} builds the schema, every fixture row created programmatically). The GLOBAL
 * escalation percent cap is pinned low via {@link DynamicPropertySource} so an over-cap proposal
 * exercises the escalation gate. Service methods run in their own transactions; assertions read
 * entities back inside a {@link TransactionTemplate}.
 *
 * <p><b>Actor / SecurityContext.</b> {@code NegotiationService} resolves the acting role the same way
 * {@code OfferService} does (ADMIN via authority, otherwise the role code of the user identified by
 * the numeric principal id). {@code openDiscountRequest}/{@code clientAccept} are CLIENT-only (driven
 * as a persisted {@code CLIENT} user by numeric id); {@code managerPropose}/{@code managerReject} are
 * MANAGER/ADMIN-only (driven as a persisted {@code MANAGER} user by numeric id, or ADMIN via
 * authority for the escalation-allowed case); {@code send} is executor-only (ADMIN authority).
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run id (UUID substring) and
 * {@link #cleanUp()} removes every row this test created (deepest-FK-first) after each test.
 *
 * <p>Validates: Requirements 3.3, 3.4, 4.1, 4.5, 4.8, 4.9, 6.3, 6.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class NegotiationServiceIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    /** A low GLOBAL percent cap so a proposal above it exercises the escalation gate (R6.3/R6.5). */
    private static final String ESCALATION_PERCENT_CAP = "10";

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
        // Pin the GLOBAL escalation percent cap low so an over-cap MANAGER_PROPOSAL escalates.
        registry.add("foremen.offer.escalation.percent-cap", () -> ESCALATION_PERCENT_CAP);
    }

    @Autowired
    private NegotiationService negotiationService;

    @Autowired
    private OfferService offerService;

    @Autowired
    private OfferDao offerDao;
    @Autowired
    private OfferNegotiationRoundDao roundDao;
    @Autowired
    private EstimateDao estimateDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private CurrencyDao currencyDao;
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
            // Deepest FK first — all keyed by the run-scoped project name. A bulk JPQL delete does
            // NOT trigger the JPA cascade, so the offer's discount and negotiation-round children
            // must be removed explicitly before the offers. Discounts are removed BEFORE rounds
            // because a materialized discount's {@code source_round} FK references a round.
            entityManager.createQuery(
                            "delete from OfferDiscountEntity d where d.offer.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from OfferNegotiationRoundEntity r where r.offer.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
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
            // Run-scoped users (the CLIENT / MANAGER actor fixtures).
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            // The shared "CLIENT"/"MANAGER" roles and the "PLN" currency are get-or-created and reused
            // (their codes cannot be run-scoped), so they are intentionally NOT deleted.
        });
    }

    // =====================================================================================
    // open request (no figure) -> CHANGES_REQUESTED (R3.3, R4.1)
    // =====================================================================================

    @Test
    @DisplayName("openDiscountRequest drives a SENT offer to CHANGES_REQUESTED and records a "
            + "DISCOUNT_REQUEST round carrying no figure (R3.3, R4.1)")
    void openRequestDrivesChangesRequestedWithNoFigure() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity round = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        assertThat(round.getKind())
                .as("a client request is a DISCOUNT_REQUEST (R4.1)")
                .isEqualTo(NegotiationRoundKind.DISCOUNT_REQUEST);
        assertThat(round.getValue())
                .as("a DISCOUNT_REQUEST carries NO figure — the value is the manager's (R4.1)").isNull();
        assertThat(round.getValueKind())
                .as("a DISCOUNT_REQUEST carries NO value kind (R4.1)").isNull();
        assertThat(round.getStatus()).isEqualTo(NegotiationRoundStatus.OPEN);

        tx().executeWithoutResult(status ->
                assertThat(offerDao.findById(offerId).orElseThrow().getStatus())
                        .as("openDiscountRequest drives SENT -> CHANGES_REQUESTED (R3.3)")
                        .isEqualTo(OfferStatus.CHANGES_REQUESTED));
    }

    // =====================================================================================
    // manager propose -> COUNTERED (R3.4)
    // =====================================================================================

    @Test
    @DisplayName("managerPropose drives a CHANGES_REQUESTED offer to COUNTERED and records the "
            + "figure on the MANAGER_PROPOSAL (R3.4)")
    void managerProposeDrivesCountered() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");
        Long managerUserId = createUser("MANAGER");

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity request = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        authenticateAs(managerUserId, "ROLE_MANAGER");
        OfferNegotiationRoundEntity proposal = negotiationService.managerPropose(
                request.getId(), DiscountKind.PERCENT, new BigDecimal("5"));

        assertThat(proposal.getKind())
                .as("a manager response is a MANAGER_PROPOSAL (R3.4)")
                .isEqualTo(NegotiationRoundKind.MANAGER_PROPOSAL);
        assertThat(proposal.getValue())
                .as("the figure lives on the MANAGER_PROPOSAL (R4.3)")
                .isEqualByComparingTo(new BigDecimal("5"));
        assertThat(proposal.getValueKind()).isEqualTo(DiscountKind.PERCENT);

        tx().executeWithoutResult(status ->
                assertThat(offerDao.findById(offerId).orElseThrow().getStatus())
                        .as("managerPropose drives CHANGES_REQUESTED -> COUNTERED (R3.4)")
                        .isEqualTo(OfferStatus.COUNTERED));
    }

    // =====================================================================================
    // manager reject requires a non-blank explanation server-side (R4.8)
    // =====================================================================================

    @Test
    @DisplayName("managerReject rejects a blank explanation server-side and records no rejection (R4.8)")
    void managerRejectRequiresExplanation() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");
        Long managerUserId = createUser("MANAGER");

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity request = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        authenticateAs(managerUserId, "ROLE_MANAGER");
        assertThatThrownBy(() -> negotiationService.managerReject(request.getId(), "   "))
                .as("a blank (all-whitespace) explanation must be rejected server-side (R4.8)")
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.reject.explanation.required");

        // No rejection round was recorded and the request stays OPEN.
        tx().executeWithoutResult(status -> {
            OfferEntity offer = offerDao.findById(offerId).orElseThrow();
            assertThat(offer.getNegotiationRounds())
                    .as("no MANAGER_REJECT round is recorded when the explanation is blank (R4.8)")
                    .noneMatch(r -> r.getKind() == NegotiationRoundKind.MANAGER_REJECT);
            assertThat(roundDao.findById(request.getId()).orElseThrow().getStatus())
                    .as("the request stays OPEN when the rejection is refused (R4.8)")
                    .isEqualTo(NegotiationRoundStatus.OPEN);
        });

        // A non-blank explanation is accepted and records a MANAGER_REJECT.
        OfferNegotiationRoundEntity rejection =
                negotiationService.managerReject(request.getId(), "budget does not allow it");
        assertThat(rejection.getKind()).isEqualTo(NegotiationRoundKind.MANAGER_REJECT);
        assertThat(rejection.getExplanation()).isEqualTo("budget does not allow it");
    }

    // =====================================================================================
    // client accept materializes discounts + bumps revision (R4.5)
    // =====================================================================================

    @Test
    @DisplayName("clientAccept materializes the proposal into an applied OfferDiscount and bumps the "
            + "offer revision by one (R4.5)")
    void clientAcceptMaterializesDiscountAndBumpsRevision() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");
        Long managerUserId = createUser("MANAGER");

        int revisionBefore = tx().execute(status ->
                offerDao.findById(offerId).orElseThrow().getRevision());

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity request = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        authenticateAs(managerUserId, "ROLE_MANAGER");
        OfferNegotiationRoundEntity proposal = negotiationService.managerPropose(
                request.getId(), DiscountKind.PERCENT, new BigDecimal("5"));

        authenticateAs(clientUserId, "ROLE_CLIENT");
        negotiationService.clientAccept(proposal.getId());

        tx().executeWithoutResult(status -> {
            OfferEntity offer = offerDao.findById(offerId).orElseThrow();
            assertThat(offer.getDiscounts())
                    .as("accepting a proposal materializes it into an applied OfferDiscount (R4.5)")
                    .hasSize(1);
            assertThat(offer.getDiscounts().get(0).getScope()).isEqualTo(DiscountScope.GLOBAL);
            assertThat(offer.getDiscounts().get(0).getKind()).isEqualTo(DiscountKind.PERCENT);
            assertThat(offer.getDiscounts().get(0).getValue()).isEqualByComparingTo(new BigDecimal("5"));
            assertThat(offer.getDiscounts().get(0).getSourceRound())
                    .as("the materialized discount links back to the accepted proposal (R4.5)")
                    .isNotNull();
            assertThat(offer.getRevision())
                    .as("accepting a proposal bumps the revision by exactly one (R4.5)")
                    .isEqualTo(revisionBefore + 1);
            assertThat(roundDao.findById(proposal.getId()).orElseThrow().getStatus())
                    .as("the accepted proposal round is marked ACCEPTED (R4.5)")
                    .isEqualTo(NegotiationRoundStatus.ACCEPTED);
        });
    }

    // =====================================================================================
    // broader-scope proposition supersedes narrower ones (R4.9)
    // =====================================================================================

    @Test
    @DisplayName("a broader-scope GLOBAL request supersedes the narrower OPEN LINE proposition it "
            + "subsumes (R4.9)")
    void broaderScopeSupersedesNarrower() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");
        Long managerUserId = createUser("MANAGER");

        // A narrow LINE-scoped request is opened first (SENT -> CHANGES_REQUESTED)...
        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity lineRequest = negotiationService.openDiscountRequest(
                offerId, DiscountScope.LINE, 999L, "reduce this line", "line comment");

        // ...and the manager proposes on it, leaving a still-OPEN LINE MANAGER_PROPOSAL and driving
        // the offer to COUNTERED (from which a fresh client request is legal, R3.3).
        authenticateAs(managerUserId, "ROLE_MANAGER");
        OfferNegotiationRoundEntity lineProposal = negotiationService.managerPropose(
                lineRequest.getId(), DiscountKind.PERCENT, new BigDecimal("3"));
        assertThat(lineProposal.getStatus()).isEqualTo(NegotiationRoundStatus.OPEN);
        assertThat(lineProposal.getScope()).isEqualTo(DiscountScope.LINE);

        // The client then opens a broader GLOBAL request (COUNTERED -> CHANGES_REQUESTED); it
        // subsumes the still-OPEN narrower LINE proposition.
        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity globalRequest = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "reduce the whole offer", null);

        tx().executeWithoutResult(status -> {
            assertThat(roundDao.findById(lineProposal.getId()).orElseThrow().getStatus())
                    .as("a GLOBAL proposition supersedes the narrower OPEN LINE proposition (R4.9)")
                    .isEqualTo(NegotiationRoundStatus.SUPERSEDED);
            assertThat(roundDao.findById(globalRequest.getId()).orElseThrow().getStatus())
                    .as("the broader GLOBAL proposition itself stays OPEN (R4.9)")
                    .isEqualTo(NegotiationRoundStatus.OPEN);
        });
    }

    // =====================================================================================
    // escalation over threshold requires ADMIN approval (R6.3/R6.5)
    // =====================================================================================

    @Test
    @DisplayName("a MANAGER_PROPOSAL over the escalation threshold is rejected for a plain MANAGER "
            + "(409 error.offer.escalation.required) (R6.3/R6.5)")
    void overThresholdProposalRejectedForManager() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");
        Long managerUserId = createUser("MANAGER");

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity request = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        // 50% > the pinned 10% cap → a plain MANAGER cannot issue it without ADMIN approval (R6.5).
        authenticateAs(managerUserId, "ROLE_MANAGER");
        assertThatThrownBy(() -> negotiationService.managerPropose(
                request.getId(), DiscountKind.PERCENT, new BigDecimal("50")))
                .as("an over-threshold proposal by a plain MANAGER is rejected (R6.5)")
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.escalation.required");

        // The offer stays CHANGES_REQUESTED and no proposal was recorded.
        tx().executeWithoutResult(status -> {
            OfferEntity offer = offerDao.findById(offerId).orElseThrow();
            assertThat(offer.getStatus())
                    .as("a rejected over-threshold proposal leaves the offer CHANGES_REQUESTED (R6.5)")
                    .isEqualTo(OfferStatus.CHANGES_REQUESTED);
            assertThat(offer.getNegotiationRounds())
                    .as("no MANAGER_PROPOSAL is recorded when escalation is refused (R6.5)")
                    .noneMatch(r -> r.getKind() == NegotiationRoundKind.MANAGER_PROPOSAL);
        });
    }

    @Test
    @DisplayName("a MANAGER_PROPOSAL over the escalation threshold is allowed for an ADMIN and "
            + "recorded adminApproved (R6.3/R6.5)")
    void overThresholdProposalAllowedForAdmin() {
        Long offerId = sentOffer();
        Long clientUserId = createUser("CLIENT");

        authenticateAs(clientUserId, "ROLE_CLIENT");
        OfferNegotiationRoundEntity request = negotiationService.openDiscountRequest(
                offerId, DiscountScope.GLOBAL, null, "please reduce the total", null);

        // An ADMIN satisfies the escalation gate directly (R6.3): the over-cap proposal is recorded.
        authenticateAdmin();
        OfferNegotiationRoundEntity proposal = negotiationService.managerPropose(
                request.getId(), DiscountKind.PERCENT, new BigDecimal("50"));

        assertThat(proposal.getKind()).isEqualTo(NegotiationRoundKind.MANAGER_PROPOSAL);
        assertThat(proposal.isAdminApproved())
                .as("an ADMIN issuing an over-threshold proposal records it adminApproved (R6.3)")
                .isTrue();

        tx().executeWithoutResult(status ->
                assertThat(offerDao.findById(offerId).orElseThrow().getStatus())
                        .as("an ADMIN over-threshold proposal drives CHANGES_REQUESTED -> COUNTERED (R3.4)")
                        .isEqualTo(OfferStatus.COUNTERED));
    }

    // =====================================================================================
    // SecurityContext helpers (mirror OfferServiceIntegrationTest)
    // =====================================================================================

    /** Authenticates as an executor via the ADMIN authority (resolved without a DB user). */
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

    /**
     * Prepares a PRICED-estimate offer and drives it to {@code SENT} (the negotiable state the round
     * mutators require): prepare (DRAFT) → stage the project at {@code READY_TO_OFFER} → send (as an
     * ADMIN executor). Returns the sent offer id. Clears the security context afterwards so each test
     * sets its own actor.
     */
    private Long sentOffer() {
        Long projectId = createProjectWithPricedEstimate();
        OfferEntity offer = offerService.prepareOffer(projectId);
        setProjectStatus(projectId, ProjectStatus.READY_TO_OFFER);

        authenticateAdmin();
        offerService.send(offer.getId());
        SecurityContextHolder.clearContext();
        return offer.getId();
    }

    /**
     * Builds a self-contained fixture in one transaction: a DRAFT project + a PRICED estimate (with a
     * get-or-created reference PLN currency for the estimate's NOT NULL {@code currency_id} FK). No
     * rooms/lines are seeded — the negotiation flows under test drive offer status, round recording,
     * supersession (by round scope/target), and the escalation gate (which compares the proposed
     * value directly against the cap), none of which traverse estimate lines; a line-less estimate
     * recomputes to a zero-net offer.
     */
    private Long createProjectWithPricedEstimate() {
        return tx().execute(status -> {
            CurrencyEntity currency = persistCurrency();

            ProjectEntity project = new ProjectEntity();
            project.setName("Negotiation IT Project " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.DRAFT);
            projectDao.save(project);

            EstimateEntity estimate = new EstimateEntity();
            estimate.setProject(project);
            estimate.setCurrency(currency);
            estimate.setStatus(EstimateStatus.PRICED);
            estimateDao.save(estimate);

            return project.getId();
        });
    }

    /** Sets the project's status directly (to stage it at READY_TO_OFFER before a send). */
    private void setProjectStatus(Long projectId, ProjectStatus projectStatus) {
        tx().executeWithoutResult(status -> {
            ProjectEntity project = projectDao.findById(projectId).orElseThrow();
            project.setStatus(projectStatus);
            projectDao.save(project);
        });
    }

    /**
     * Persists an active user whose role code equals {@code roleCode} and returns its id — the actor
     * for a negotiation mutator ({@code NegotiationService} resolves the acting role from the numeric
     * principal id → the user's role code). The shared role row (its code must equal {@code roleCode}
     * exactly, so it cannot be run-scoped) is get-or-created like the PLN currency.
     */
    private Long createUser(String roleCode) {
        return tx().execute(status -> {
            RoleEntity role = roleDao.findByCode(roleCode).orElseGet(() -> {
                RoleEntity r = new RoleEntity();
                r.setCode(roleCode);
                r.setNameRU(roleCode);
                r.setNamePL(roleCode);
                return roleDao.save(r);
            });

            UserEntity user = new UserEntity();
            user.setName(roleCode + " " + runId);
            user.setEmail(roleCode.toLowerCase() + "+" + runId + "-" + COUNTER.incrementAndGet()
                    + "@example.com");
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
