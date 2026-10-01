package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link OfferService#chooseFinishingConcrete(Long, Long, Long)} — the
 * CLIENT-scoped, offer-stage, finishing-Placeholder-only invocation of the reused FOR-05-05
 * {@code chooseConcrete} write path (FOR-05-07, design §Property 6, §Property 7, §Property 8).
 *
 * <p><b>Chosen test level.</b> {@code chooseFinishingConcrete} is not pure — it resolves the offer,
 * asserts the offer is non-terminal + negotiable, resolves the target material line and asserts it
 * belongs to the offer's estimate, asserts the line is a finishing Placeholder, and only then
 * delegates the single-line write to {@link EstimateAssignmentService#chooseConcrete}. Following the
 * repo's established service-level property-test pattern (mirroring
 * {@link OfferServicePrepareOfferPropertyTest}), the service is constructed directly with
 * <b>Mockito-mocked DAOs</b> ({@link OfferDao}, {@link EstimateDao},
 * {@link EstimateLineRoomMaterialDao}, {@link OfferPackageDao}, {@link UserDao}) and a
 * <b>mocked {@link EstimateAssignmentService}</b> so {@code chooseConcrete} is a recorded no-op (its
 * DRAFT gate is delegated, not re-implemented here), with the real pure collaborators. This runs
 * entirely in memory over 100+ iterations with no Spring context and no Testcontainers.
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 6: A client may write only a finishing
 * Placeholder of its own project's offer</b> — <b>Validates: Requirements 5.8, 10.6, 11.4, 11.5</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 7: A client concrete-finishing choice changes
 * only the affected finishing line</b> — <b>Validates: Requirements 10.7</b>
 *
 * <p><b>Feature: FOR-05-07-offer-approval, Property 8: Client material selection is permitted only
 * while negotiable and estimate is DRAFT</b> — <b>Validates: Requirements 11.7</b>
 */
@Tag("Feature: FOR-05-07-offer-approval, Property 6: A client may write only a finishing Placeholder of its own project's offer")
@Tag("Feature: FOR-05-07-offer-approval, Property 7: A client concrete-finishing choice changes only the affected finishing line")
@Tag("Feature: FOR-05-07-offer-approval, Property 8: Client material selection is permitted only while negotiable and estimate is DRAFT")
class ClientMaterialWriteConfinementPropertyTest {

    private static final long PROJECT_ID = 42L;
    private static final long ESTIMATE_ID = 100L;
    private static final long OFFER_ID = 7L;
    private static final long MATERIAL_LINE_ID = 555L;
    private static final long MATERIAL_ID = 999L;

    // ------------------------------------------------------------------------------------------
    // Property 6: A client may write only a finishing Placeholder of its own project's offer.
    //
    // For a negotiable offer, chooseFinishingConcrete SUCCEEDS (and delegates to chooseConcrete)
    // IF AND ONLY IF the target line is a finishing Placeholder (branch == finishing AND
    // concreteFinishingMaterial == null); a construction line is rejected 400 not.finishing, an
    // already-chosen finishing line is rejected 400 not.placeholder, and a line NOT belonging to the
    // offer's estimate is rejected 404 (own-project confinement facet). In every rejection nothing is
    // delegated.
    // Validates: Requirements 5.8, 10.6, 11.4, 11.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 6: A client may write only a finishing Placeholder of its own project's offer")
    void onlyFinishingPlaceholderOfOwnEstimateIsWritable(@ForAll("lineKinds") LineKind kind) {
        Fixture f = new Fixture();
        // A negotiable offer isolates the branch/placeholder/membership gate from the status gate.
        OfferEntity offer = offer(OfferStatus.SENT);
        when(f.offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        EstimateLineRoomMaterialEntity line = materialLine(kind);
        when(f.estimateLineRoomMaterialDao.findById(MATERIAL_LINE_ID)).thenReturn(Optional.of(line));

        boolean isWritable = kind == LineKind.FINISHING_PLACEHOLDER;

        if (isWritable) {
            OfferEntity result =
                    f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID);
            assertThat(result).isSameAs(offer);
            // The single-line write is delegated exactly once for the targeted line.
            verify(f.estimateAssignmentService)
                    .chooseConcrete(PROJECT_ID, MATERIAL_LINE_ID, MATERIAL_ID);
        } else {
            ForemenApiException ex = catchThrowableOfType(
                    () -> f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID),
                    ForemenApiException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo(expectedCode(kind));
            // Nothing is written for a non-writable target.
            verify(f.estimateAssignmentService, never()).chooseConcrete(anyLong(), anyLong(), anyLong());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 7: A client concrete-finishing choice changes only the affected finishing line.
    //
    // For a successful choose, the ONLY write the service performs is a single delegated
    // chooseConcrete scoped to exactly (projectId, materialLineId, materialId) -- no other line id
    // is ever touched, and no other estimate write path is invoked. (chooseConcrete is mocked, so we
    // assert the delegation is confined to the one target line.)
    // Validates: Requirements 10.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-07-offer-approval, Property 7: A client concrete-finishing choice changes only the affected finishing line")
    void chooseAltersOnlyTheTargetedLine(
            @ForAll("otherLineIds") long otherLineId,
            @ForAll("otherMaterialIds") long otherMaterialId) {

        Fixture f = new Fixture();
        OfferEntity offer = offer(OfferStatus.SENT);
        when(f.offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        EstimateLineRoomMaterialEntity target = materialLine(LineKind.FINISHING_PLACEHOLDER);
        when(f.estimateLineRoomMaterialDao.findById(MATERIAL_LINE_ID)).thenReturn(Optional.of(target));

        f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID);

        // Exactly one delegated write, scoped to the target line + the requested material only.
        verify(f.estimateAssignmentService, times(1))
                .chooseConcrete(PROJECT_ID, MATERIAL_LINE_ID, MATERIAL_ID);
        // No other line id and no other material id is ever written (any distinct id is untouched).
        if (otherLineId != MATERIAL_LINE_ID) {
            verify(f.estimateAssignmentService, never())
                    .chooseConcrete(anyLong(), eq(otherLineId), anyLong());
        }
        if (otherMaterialId != MATERIAL_ID) {
            verify(f.estimateAssignmentService, never())
                    .chooseConcrete(anyLong(), anyLong(), eq(otherMaterialId));
        }
        // No other estimate write path is exercised (only chooseConcrete is delegated).
        verify(f.estimateAssignmentService, never()).applyAssignments(anyLong(), any());
    }

    // ------------------------------------------------------------------------------------------
    // Property 8: Client material selection is permitted only while negotiable and estimate is DRAFT.
    //
    // For all offer statuses, chooseFinishingConcrete is permitted (delegates to chooseConcrete) IF
    // AND ONLY IF the offer is in a non-terminal negotiable state (SENT / CHANGES_REQUESTED /
    // COUNTERED); DRAFT and every terminal status are rejected 409 error.offer.illegal.transition
    // BEFORE any delegation. (The estimate-DRAFT half of R11.7 is enforced by the reused
    // chooseConcrete write path via DraftGateGuard, which this service delegates to.)
    // Validates: Requirements 11.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    @Tag("Feature: FOR-05-07-offer-approval, Property 8: Client material selection is permitted only while negotiable and estimate is DRAFT")
    void materialSelectionPermittedOnlyWhileNegotiable(@ForAll("allStatuses") OfferStatus status) {
        Fixture f = new Fixture();
        OfferEntity offer = offer(status);
        when(f.offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        // A finishing Placeholder isolates the status gate from the branch/placeholder gate.
        EstimateLineRoomMaterialEntity line = materialLine(LineKind.FINISHING_PLACEHOLDER);
        when(f.estimateLineRoomMaterialDao.findById(MATERIAL_LINE_ID)).thenReturn(Optional.of(line));

        boolean negotiable = status == OfferStatus.SENT
                || status == OfferStatus.CHANGES_REQUESTED
                || status == OfferStatus.COUNTERED;

        if (negotiable) {
            f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID);
            verify(f.estimateAssignmentService)
                    .chooseConcrete(PROJECT_ID, MATERIAL_LINE_ID, MATERIAL_ID);
        } else {
            ForemenApiException ex = catchThrowableOfType(
                    () -> f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID),
                    ForemenApiException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getMessageCode()).isEqualTo("error.offer.illegal.transition");
            // A non-negotiable offer is rejected before any write is delegated.
            verify(f.estimateAssignmentService, never()).chooseConcrete(anyLong(), anyLong(), anyLong());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 6 (confinement facet): a line that does NOT belong to the offer's estimate is rejected
    // 404 -- the server-side own-project confinement facet (a client cannot reach another offer's /
    // another project's line). Kept as a separate focused property so the 404 path is not diluted by
    // the branch/placeholder cases above.
    // Validates: Requirements 11.4, 11.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-07-offer-approval, Property 6: A client may write only a finishing Placeholder of its own project's offer")
    void lineNotInOffersEstimateIsRejected(@ForAll("foreignEstimateIds") long foreignEstimateId) {
        Fixture f = new Fixture();
        OfferEntity offer = offer(OfferStatus.SENT);
        when(f.offerDao.findById(OFFER_ID)).thenReturn(Optional.of(offer));

        // A finishing Placeholder that belongs to a DIFFERENT estimate than the offer's.
        EstimateLineRoomMaterialEntity line =
                materialLine(LineKind.FINISHING_PLACEHOLDER, foreignEstimateId);
        when(f.estimateLineRoomMaterialDao.findById(MATERIAL_LINE_ID)).thenReturn(Optional.of(line));

        ForemenApiException ex = catchThrowableOfType(
                () -> f.service.chooseFinishingConcrete(OFFER_ID, MATERIAL_LINE_ID, MATERIAL_ID),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
        verify(f.estimateAssignmentService, never()).chooseConcrete(anyLong(), anyLong(), anyLong());
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a fresh {@link OfferService} with mocked DAOs, a mocked
     * {@link EstimateAssignmentService} (its {@code chooseConcrete} is a recorded no-op), and the
     * real pure collaborators. {@code offerDao.save} echoes its argument back and
     * {@code entityManager.flush()} is a no-op, so a successful choose returns the same offer.
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

        // Real pure collaborators — deterministic value logic, no need to mock.
        final OfferStatusMachine offerStatusMachine = new OfferStatusMachine();
        final OfferTotalsCalculator offerTotalsCalculator = new OfferTotalsCalculator();
        final DiscountResolver discountResolver = new DiscountResolver();

        final OfferService service;

        Fixture() {
            when(offerDao.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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

    /** The classification of a target material line for Property 6. */
    private enum LineKind {
        /** branch == finishing, no concrete chosen — the only writable target. */
        FINISHING_PLACEHOLDER,
        /** branch == construction — rejected 400 not.finishing. */
        CONSTRUCTION,
        /** branch == finishing but a concrete product already chosen — rejected 400 not.placeholder. */
        FINISHING_ALREADY_CHOSEN
    }

    private static String expectedCode(LineKind kind) {
        return switch (kind) {
            case CONSTRUCTION -> "error.offer.material.not.finishing";
            case FINISHING_ALREADY_CHOSEN -> "error.offer.material.not.placeholder";
            case FINISHING_PLACEHOLDER -> throw new IllegalArgumentException("placeholder is writable");
        };
    }

    /** Builds a negotiable/other offer referencing the standard project + estimate. */
    private static OfferEntity offer(OfferStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);

        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(ESTIMATE_ID);
        estimate.setProject(project);

        OfferEntity offer = new OfferEntity();
        offer.setId(OFFER_ID);
        offer.setProject(project);
        offer.setEstimate(estimate);
        offer.setStatus(status);
        offer.setRevision(1);
        return offer;
    }

    private static EstimateLineRoomMaterialEntity materialLine(LineKind kind) {
        return materialLine(kind, ESTIMATE_ID);
    }

    /** Builds a material line of the given kind, owned by the estimate with {@code owningEstimateId}. */
    private static EstimateLineRoomMaterialEntity materialLine(LineKind kind, long owningEstimateId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);

        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(owningEstimateId);
        estimate.setProject(project);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(1L);
        line.setEstimate(estimate);

        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
        roomQty.setId(1L);
        roomQty.setLine(line);

        EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
        material.setId(MATERIAL_LINE_ID);
        material.setRoomQty(roomQty);

        switch (kind) {
            case CONSTRUCTION -> material.setBranch(ConsumptionBranch.construction);
            case FINISHING_PLACEHOLDER -> material.setBranch(ConsumptionBranch.finishing);
            case FINISHING_ALREADY_CHOSEN -> {
                material.setBranch(ConsumptionBranch.finishing);
                FinishingMaterialEntity chosen = new FinishingMaterialEntity();
                chosen.setId(321L);
                material.setConcreteFinishingMaterial(chosen);
                material.setConcreteNet(new BigDecimal("12.34"));
            }
        }
        return material;
    }

    // ---- Generators ----

    /** All three line kinds — writable Placeholder, construction, and already-chosen finishing. */
    @Provide
    Arbitrary<LineKind> lineKinds() {
        return Arbitraries.of(LineKind.class);
    }

    /** Every offer status, so both the negotiable window and DRAFT/terminal statuses are covered. */
    @Provide
    Arbitrary<OfferStatus> allStatuses() {
        return Arbitraries.of(OfferStatus.class);
    }

    /** Estimate ids distinct from the offer's estimate (own-project confinement 404 facet). */
    @Provide
    Arbitrary<Long> foreignEstimateIds() {
        return Arbitraries.longs().between(ESTIMATE_ID + 1, ESTIMATE_ID + 100_000);
    }

    /** Arbitrary line ids used to assert no OTHER line is written by a targeted choose. */
    @Provide
    Arbitrary<Long> otherLineIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    /** Arbitrary material ids used to assert no OTHER material is written by a targeted choose. */
    @Provide
    Arbitrary<Long> otherMaterialIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }
}
