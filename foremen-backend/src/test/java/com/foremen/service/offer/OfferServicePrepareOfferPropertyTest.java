package com.foremen.service.offer;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.ApplicationEventPublisher;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferService#prepareOffer(Long)} — the offer-preparation rule (an
 * offer can be prepared from an estimate in <b>any</b> status; the former {@code PRICED} precondition
 * is removed per R1.1/R1.2) and the initial-package seeding rule (the new offer's
 * {@code selectedPackage} is resolved from the estimate's {@code appliedPackageCode}, null-safe)
 * (FOR-05-07, design §Property 16 and §Property 17).
 *
 * <p><b>Chosen test level.</b> {@code prepareOffer} is not a pure function — it reads the project's
 * estimate, checks for an existing non-terminal offer, resolves the package, computes totals, and
 * persists — so it cannot be exercised as a bare pure predicate. Following the repo's established
 * service-level property-test pattern (e.g. {@code InviteConsumePropertyTest},
 * {@code PasswordResetPropertyTest}), the service is constructed directly with <b>mocked DAOs</b>
 * ({@link EstimateDao}, {@link OfferDao}, {@link OfferPackageDao}) and the <b>real pure
 * collaborators</b> ({@link DiscountResolver}, {@link OfferTotalsCalculator},
 * {@link OfferStatusMachine}). This runs entirely in memory over 100+ iterations with no Spring
 * context and no Testcontainers.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 16: Preparing an offer succeeds from an estimate
 * in any status</b> — <b>Validates: Requirements 1.1, 1.2</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 17: The initial package is seeded from the
 * estimate's applied package</b> — <b>Validates: Requirements 1.6, 1.7</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 16: Preparing an offer succeeds from an estimate in any status")
@Tag("Feature: FOR-05-07-offer-approval, Property 17: The initial package is seeded from the estimate's applied package")
class OfferServicePrepareOfferPropertyTest {

    private static final long PROJECT_ID = 42L;

    // ------------------------------------------------------------------------------------------
    // Property 16: Preparing an offer succeeds from an estimate in ANY status.
    // For all estimate statuses (including DRAFT and other non-PRICED statuses), prepareOffer
    // SUCCEEDS and creates a DRAFT, revision-1 offer. The former PRICED precondition and its
    // error.offer.estimate.not.priced message are removed (R1.1/R1.2); the only prepare guards are
    // the single-active-offer rule and entity existence.
    // Validates: Requirements 1.1, 1.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 16: Preparing an offer succeeds from an estimate in any status")
    void anyStatusEstimateIsAcceptedAndCreatesDraftOffer(@ForAll("anyStatuses") EstimateStatus status) {
        Fixture f = new Fixture();
        EstimateEntity estimate = estimate(status, null);
        when(f.estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));
        when(f.offerDao.findByProjectIdAndStatusNotIn(anyLong(), any())).thenReturn(List.of());

        OfferEntity created = f.service.prepareOffer(PROJECT_ID);

        // R1.1/R1.2: an estimate in any status prepares a fresh DRAFT, revision-1 offer; no status
        // gate rejects a non-PRICED estimate.
        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(OfferStatus.DRAFT);
        assertThat(created.getRevision()).isEqualTo(1);
        assertThat(created.getEstimate()).isSameAs(estimate);
        assertThat(created.getProject()).isSameAs(estimate.getProject());
        verify(f.offerDao).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // Property 17: The initial package is seeded from the estimate's applied package.
    // For all estimates, the prepared offer's selectedPackage equals the OfferPackage resolved from
    // estimate.appliedPackageCode, or is null when the code is null / blank / unresolvable -- and
    // offer creation NEVER fails on an unresolvable code.
    // Validates: Requirements 1.6, 1.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 17: The initial package is seeded from the estimate's applied package")
    void selectedPackageEqualsResolvedAppliedPackageElseNull(
            @ForAll("appliedPackageCodes") String appliedCode,
            @ForAll boolean codeResolves) {

        Fixture f = new Fixture();
        EstimateEntity estimate = estimate(EstimateStatus.PRICED, appliedCode);
        when(f.estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));
        when(f.offerDao.findByProjectIdAndStatusNotIn(anyLong(), any())).thenReturn(List.of());

        // A non-null, non-blank code MAY resolve to a package or MAY be unknown (codeResolves).
        boolean codeIsResolvable = appliedCode != null && !appliedCode.isBlank();
        OfferPackageEntity resolved = null;
        if (codeIsResolvable && codeResolves) {
            resolved = offerPackage(777L, appliedCode);
            when(f.offerPackageDao.findByCode(appliedCode)).thenReturn(Optional.of(resolved));
        } else if (codeIsResolvable) {
            // Known-shape code that does not resolve to any package.
            when(f.offerPackageDao.findByCode(appliedCode)).thenReturn(Optional.empty());
        }

        OfferEntity created = f.service.prepareOffer(PROJECT_ID);

        // Offer creation never fails on an unresolvable code (R1.7): a DRAFT offer is always created.
        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(OfferStatus.DRAFT);

        if (codeIsResolvable && codeResolves) {
            // R1.6: the seeded package is exactly the one resolved from appliedPackageCode.
            assertThat(created.getSelectedPackage()).isSameAs(resolved);
        } else {
            // R1.7: null / blank / unknown code yields selectedPackage == null, no failure.
            assertThat(created.getSelectedPackage()).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 17 (focus on the null/blank branch): a null or blank appliedPackageCode never even
    // consults the package DAO and always seeds selectedPackage = null.
    // Validates: Requirements 1.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 17: The initial package is seeded from the estimate's applied package")
    void nullOrBlankCodeSeedsNullPackageWithoutFailing(@ForAll("blankOrNullCodes") String blankCode) {
        Fixture f = new Fixture();
        EstimateEntity estimate = estimate(EstimateStatus.PRICED, blankCode);
        when(f.estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));
        when(f.offerDao.findByProjectIdAndStatusNotIn(anyLong(), any())).thenReturn(List.of());

        OfferEntity created = f.service.prepareOffer(PROJECT_ID);

        assertThat(created).isNotNull();
        assertThat(created.getSelectedPackage()).isNull();
        // Never resolves a package for a null/blank code.
        verify(f.offerPackageDao, never()).findByCode(any());
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link OfferService} with mocked DAOs and the real pure collaborators. Only the
     * collaborators {@code prepareOffer} touches are meaningfully stubbed per test; the rest are bare
     * mocks (unused by {@code prepareOffer}). {@code offerDao.save} echoes its argument back so the
     * created offer is returned as-is, and {@code entityManager.flush()} is a no-op.
     */
    private static final class Fixture {
        final OfferDao offerDao = mock(OfferDao.class);
        final EstimateDao estimateDao = mock(EstimateDao.class);
        final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao =
                mock(EstimateLineRoomMaterialDao.class);
        final OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
        final UserDao userDao = mock(UserDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        final DraftGateGuard draftGateGuard = mock(DraftGateGuard.class);
        final EstimateAssignmentService estimateAssignmentService = mock(EstimateAssignmentService.class);
        final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        final ClientOfferReadModelAssembler clientOfferReadModelAssembler =
                mock(ClientOfferReadModelAssembler.class);

        // Real pure collaborators — no need to mock deterministic value logic.
        final OfferStatusMachine offerStatusMachine = new OfferStatusMachine();
        final OfferTotalsCalculator offerTotalsCalculator = new OfferTotalsCalculator();
        final DiscountResolver discountResolver = new DiscountResolver();

        final OfferService service;

        Fixture() {
            // offerDao.save(offer) returns the same instance so prepareOffer returns the created offer.
            when(offerDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            // entityManager.flush() is a no-op in these in-memory tests.
            doAnswer(invocation -> null).when(entityManager).flush();

            service = new OfferService(
                    offerDao,
                    estimateDao,
                    estimateLineRoomMaterialDao,
                    offerPackageDao,
                    userDao,
                    /* offerServiceMapper */ null,
                    projectAccessCache,
                    /* auditLogDao */ null,
                    entityManager,
                    offerStatusMachine,
                    offerTotalsCalculator,
                    discountResolver,
                    draftGateGuard,
                    estimateAssignmentService,
                    eventPublisher,
                    clientOfferReadModelAssembler);
        }
    }

    /** Builds a PRICED-or-other estimate with the given applied package code and a bare project. */
    private static EstimateEntity estimate(EstimateStatus status, String appliedPackageCode) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);

        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(100L);
        estimate.setProject(project);
        estimate.setStatus(status);
        estimate.setAppliedPackageCode(appliedPackageCode);
        // No lines / no VAT rate: totals recompute to zero, which prepareOffer tolerates.
        estimate.setLines(List.of());
        return estimate;
    }

    private static OfferPackageEntity offerPackage(long id, String code) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(id);
        pkg.setCode(code);
        return pkg;
    }

    // ---- Generators ----

    /**
     * Every {@link EstimateStatus} — preparation now succeeds from an estimate in any status
     * (including the non-{@code PRICED} ones that the removed R1.2 gate used to reject).
     */
    @Provide
    Arbitrary<EstimateStatus> anyStatuses() {
        return Arbitraries.of(EstimateStatus.values());
    }

    /**
     * Applied-package codes covering all of R1.6/R1.7's cases: {@code null}, blank/whitespace,
     * canonical package codes (START/COMFORT/PRESTIGE), and arbitrary non-blank codes.
     */
    @Provide
    Arbitrary<String> appliedPackageCodes() {
        Arbitrary<String> canonical = Arbitraries.of("START", "COMFORT", "PRESTIGE");
        Arbitrary<String> arbitrary =
                Arbitraries.strings().ofMinLength(1).ofMaxLength(64).filter(s -> !s.isBlank());
        Arbitrary<String> blanks = Arbitraries.of("", "   ", "\t", "\n");
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                blanks,
                canonical,
                arbitrary);
    }

    /** Null and all-whitespace codes — the R1.7 "no package applied" cases. */
    @Provide
    Arbitrary<String> blankOrNullCodes() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of("", " ", "   ", "\t", "\n", "  \t  "));
    }
}
