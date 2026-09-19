package com.foremen.service;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.mapper.WorkMaterialConsumptionServiceMapper;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for {@link WorkMaterialConsumptionService} write-path validation
 * (FOR-04-19, Property 1 — "Write validation accepts iff all required fields present, references
 * exist, normQty in range, and exactly one type matches branch").
 *
 * <p>The write path is {@link WorkMaterialConsumptionService#validateCreate}/{@code validateUpdate},
 * both delegating to the private {@code normalize(...)} step. Normalization (a) requires
 * {@code workItemId}/{@code offerPackageId}/{@code branch}/{@code materialUnitId}/{@code normQty}
 * and EXACTLY ONE material-type id matching {@code branch}, (b) real-loads each supplied reference,
 * (c) range-checks {@code normQty} within {@code [0, 99999999.9999]}, and (d) enforces the XOR +
 * branch-match rule — throwing a {@link ForemenApiException} (naming the offending field) BEFORE
 * anything is persisted.
 *
 * <p>Because {@code normalize} performs no writes, the five reference DAOs are mocked so ONLY a
 * fixed set of "known-valid" ids resolve, the service is constructed directly with those mocks, and
 * {@code validateCreate}/{@code validateUpdate} are driven with generated models. No persistence
 * occurs — verified by asserting the consumption DAO's {@code save}/{@code saveAll} were never
 * invoked.
 *
 * <p>The property asserts the ACCEPT partition exactly: a model is accepted iff all required fields
 * are present AND every supplied reference exists AND {@code normQty} is in range AND exactly one
 * material-type id is set matching {@code branch}; otherwise it is rejected with a
 * {@link ForemenApiException} naming the offending field and nothing persisted.
 *
 * <p>Feature: FOR-04-19-work-catalog-material-consumption, Property 1
 *
 * <p><b>Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.6</b>
 */
@Tag("Feature: FOR-04-19-work-catalog-material-consumption, Property 1: Write validation accepts iff all required fields present, references exist, normQty in range, and exactly one type matches branch")
class WorkMaterialConsumptionValidationPropertyTest {

    /** The single set of reference ids the mocked DAOs treat as existing rows. */
    private static final long VALID_WORK_ITEM_ID = 1L;
    private static final long VALID_OFFER_PACKAGE_ID = 2L;
    private static final long VALID_MATERIAL_UNIT_ID = 3L;
    private static final long VALID_CONSTRUCTION_TYPE_ID = 4L;
    private static final long VALID_FINISHING_TYPE_ID = 5L;

    /** Any id at or beyond this bound is guaranteed NOT to be one of the known-valid ids above. */
    private static final long DANGLING_ID_FLOOR = 1_000L;

    private static final BigDecimal NORM_QTY_MAX = new BigDecimal("99999999.9999");

    // ------------------------------------------------------------------------------------------
    // Property 1: over the full cross-product of defects, a model is accepted iff (all required
    //             present) AND (references exist) AND (normQty in range) AND (exactly one type
    //             matching branch); otherwise rejected with a field-naming exception and no write.
    // Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-19-work-catalog-material-consumption, Property 1: Write validation accepts iff all required fields present, references exist, normQty in range, and exactly one type matches branch")
    void acceptsIffAllInvariantsHold(@ForAll("candidates") Candidate candidate) {
        Fixture fixture = new Fixture();
        WorkMaterialConsumptionServiceExtendedModel model = candidate.toModel();

        if (candidate.isValid()) {
            // Accept: neither create nor update normalization throws, and nothing is persisted
            // (normalize is a pure pre-persist check).
            assertThatCode(() -> fixture.service.validateCreate(model)).doesNotThrowAnyException();
            assertThatCode(() -> fixture.service.validateUpdate(null, model)).doesNotThrowAnyException();
        } else {
            // Reject: a ForemenApiException naming the offending field, and nothing persisted.
            assertThatThrownBy(() -> fixture.service.validateCreate(model))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus())
                                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
                        assertThat(ex.getMessageParams()).isNotEmpty();
                        assertThat(ex.getMessageParams()[0]).isEqualTo(candidate.expectedField());
                    });
        }

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Generator
    // ------------------------------------------------------------------------------------------

    /**
     * Generates candidates independently varying each dimension the validation partitions on:
     * presence of each required scalar/reference field, the type-id combo (neither / construction /
     * finishing / both) crossed with {@code branch}, reference ids drawn from the seeded set plus
     * dangling ids, and {@code normQty} across in-range / negative / over-max boundaries.
     */
    @Provide
    Arbitrary<Candidate> candidates() {
        Arbitrary<Presence> workItem = referencePresence();
        Arbitrary<Presence> offerPackage = referencePresence();
        Arbitrary<Presence> materialUnit = referencePresence();
        Arbitrary<Boolean> branchPresent = Arbitraries.of(true, false);
        Arbitrary<ConsumptionBranch> branch = Arbitraries.of(ConsumptionBranch.construction, ConsumptionBranch.finishing);
        Arbitrary<TypeCombo> typeCombo = Arbitraries.of(TypeCombo.values());
        // Whether a present construction/finishing type id points at the seeded (valid) row or a
        // dangling one — folded into one 4-value dimension to stay within Combinators' 8-arg limit.
        Arbitrary<TypeValidity> typeValidity = Arbitraries.of(TypeValidity.values());
        Arbitrary<NormQty> normQty = normQtyArbitrary();

        return Combinators.combine(workItem, offerPackage, materialUnit, branchPresent, branch,
                        typeCombo, typeValidity, normQty)
                .as((wi, op, mu, bp, br, tc, tv, nq) ->
                        new Candidate(wi, op, mu, bp, br, tc, tv.constructionValid(), tv.finishingValid(), nq));
    }

    /** A reference id is absent (null), valid (seeded), or dangling (present but not seeded). */
    @Provide
    Arbitrary<Presence> referencePresence() {
        return Arbitraries.of(Presence.ABSENT, Presence.VALID, Presence.DANGLING);
    }

    @Provide
    Arbitrary<NormQty> normQtyArbitrary() {
        Arbitrary<BigDecimal> inRange = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, NORM_QTY_MAX).ofScale(4)
                .map(v -> v); // in [0, 99999999.9999]
        Arbitrary<BigDecimal> negative = Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000000.0000"), new BigDecimal("-0.0001")).ofScale(4);
        Arbitrary<BigDecimal> overMax = Arbitraries.bigDecimals()
                .between(NORM_QTY_MAX.add(new BigDecimal("0.0001")),
                        NORM_QTY_MAX.add(new BigDecimal("1000000.0000"))).ofScale(4);

        Arbitrary<NormQty> present = Arbitraries.oneOf(
                inRange.map(v -> new NormQty(v, true)),
                negative.map(v -> new NormQty(v, false)),
                overMax.map(v -> new NormQty(v, false)));
        Arbitrary<NormQty> absent = Arbitraries.just(new NormQty(null, false));
        return Arbitraries.oneOf(present, present, absent); // bias toward present values
    }

    // ------------------------------------------------------------------------------------------
    // Candidate model
    // ------------------------------------------------------------------------------------------

    /** Whether a nullable reference id is absent, points at a seeded row, or is dangling. */
    private enum Presence { ABSENT, VALID, DANGLING }

    /** The four ways the two material-type ids can be populated. */
    private enum TypeCombo { NEITHER, CONSTRUCTION_ONLY, FINISHING_ONLY, BOTH }

    /** Whether each present material-type id points at the seeded (valid) row or a dangling one. */
    private enum TypeValidity {
        BOTH_VALID(true, true),
        CONSTRUCTION_DANGLING(false, true),
        FINISHING_DANGLING(true, false),
        BOTH_DANGLING(false, false);

        private final boolean constructionValid;
        private final boolean finishingValid;

        TypeValidity(boolean constructionValid, boolean finishingValid) {
            this.constructionValid = constructionValid;
            this.finishingValid = finishingValid;
        }

        boolean constructionValid() {
            return constructionValid;
        }

        boolean finishingValid() {
            return finishingValid;
        }
    }

    /** A generated {@code normQty} value paired with whether it lies in the valid range. */
    private record NormQty(BigDecimal value, boolean inRange) {
    }

    /**
     * One generated write-model candidate across all validation dimensions. {@link #isValid()}
     * computes the oracle (independent of the service) and {@link #expectedField()} names the field
     * the FIRST-failing validation step reports, mirroring the {@code normalize(...)} order:
     * required-fields → material-type/branch → reference-resolution → normQty-range.
     */
    private static final class Candidate {
        private final Presence workItem;
        private final Presence offerPackage;
        private final Presence materialUnit;
        private final boolean branchPresent;
        private final ConsumptionBranch branch;
        private final TypeCombo typeCombo;
        private final boolean constructionTypeValid;
        private final boolean finishingTypeValid;
        private final NormQty normQty;

        Candidate(Presence workItem, Presence offerPackage, Presence materialUnit,
                  boolean branchPresent, ConsumptionBranch branch, TypeCombo typeCombo,
                  boolean constructionTypeValid, boolean finishingTypeValid, NormQty normQty) {
            this.workItem = workItem;
            this.offerPackage = offerPackage;
            this.materialUnit = materialUnit;
            this.branchPresent = branchPresent;
            this.branch = branch;
            this.typeCombo = typeCombo;
            this.constructionTypeValid = constructionTypeValid;
            this.finishingTypeValid = finishingTypeValid;
            this.normQty = normQty;
        }

        WorkMaterialConsumptionServiceExtendedModel toModel() {
            WorkMaterialConsumptionServiceExtendedModel m = new WorkMaterialConsumptionServiceExtendedModel();
            m.setWorkItemId(idFor(workItem, VALID_WORK_ITEM_ID, DANGLING_ID_FLOOR));
            m.setOfferPackageId(idFor(offerPackage, VALID_OFFER_PACKAGE_ID, DANGLING_ID_FLOOR + 1));
            m.setMaterialUnitId(idFor(materialUnit, VALID_MATERIAL_UNIT_ID, DANGLING_ID_FLOOR + 2));
            m.setBranch(branchPresent ? branch : null);
            if (typeCombo == TypeCombo.CONSTRUCTION_ONLY || typeCombo == TypeCombo.BOTH) {
                m.setConstructionMaterialTypeId(constructionTypeValid
                        ? VALID_CONSTRUCTION_TYPE_ID : DANGLING_ID_FLOOR + 3);
            }
            if (typeCombo == TypeCombo.FINISHING_ONLY || typeCombo == TypeCombo.BOTH) {
                m.setFinishingMaterialTypeId(finishingTypeValid
                        ? VALID_FINISHING_TYPE_ID : DANGLING_ID_FLOOR + 4);
            }
            m.setNormQty(normQty.value());
            m.setSourceType("expert");
            m.setSourceDoc("doc");
            m.setSourceRef("ref");
            return m;
        }

        private static Long idFor(Presence presence, long validId, long danglingId) {
            return switch (presence) {
                case ABSENT -> null;
                case VALID -> validId;
                case DANGLING -> danglingId;
            };
        }

        /** The oracle: accepted iff every invariant the write path enforces holds. */
        boolean isValid() {
            return requiredFieldsPresent()
                    && exactlyOneTypeMatchingBranch()
                    && referencesExist()
                    && normQty.inRange();
        }

        private boolean requiredFieldsPresent() {
            return workItem != Presence.ABSENT
                    && offerPackage != Presence.ABSENT
                    && materialUnit != Presence.ABSENT
                    && normQty.value() != null
                    && branchPresent;
        }

        private boolean exactlyOneTypeMatchingBranch() {
            boolean hasConstruction = typeCombo == TypeCombo.CONSTRUCTION_ONLY || typeCombo == TypeCombo.BOTH;
            boolean hasFinishing = typeCombo == TypeCombo.FINISHING_ONLY || typeCombo == TypeCombo.BOTH;
            if (hasConstruction == hasFinishing) {
                return false; // NEITHER or BOTH
            }
            return branch == ConsumptionBranch.construction ? hasConstruction : hasFinishing;
        }

        private boolean referencesExist() {
            if (workItem != Presence.VALID || offerPackage != Presence.VALID || materialUnit != Presence.VALID) {
                return false;
            }
            // Only the type id that is set (per the XOR) is loaded; validity is checked here.
            if (typeCombo == TypeCombo.CONSTRUCTION_ONLY && !constructionTypeValid) {
                return false;
            }
            if (typeCombo == TypeCombo.FINISHING_ONLY && !finishingTypeValid) {
                return false;
            }
            return true;
        }

        /**
         * The field the FIRST-failing step names, following the service's {@code normalize(...)}
         * order: {@code validateRequiredFields} → {@code validateMaterialTypeBranch} →
         * {@code resolveReferences} → {@code validateNormQty}.
         */
        String expectedField() {
            // 1. Required fields (workItemId, offerPackageId, materialUnitId, normQty, branch).
            if (workItem == Presence.ABSENT) {
                return "workItemId";
            }
            if (offerPackage == Presence.ABSENT) {
                return "offerPackageId";
            }
            if (materialUnit == Presence.ABSENT) {
                return "materialUnitId";
            }
            if (normQty.value() == null) {
                return "normQty";
            }
            if (!branchPresent) {
                return "branch";
            }
            // 2. XOR + branch-match.
            boolean hasConstruction = typeCombo == TypeCombo.CONSTRUCTION_ONLY || typeCombo == TypeCombo.BOTH;
            boolean hasFinishing = typeCombo == TypeCombo.FINISHING_ONLY || typeCombo == TypeCombo.BOTH;
            if (hasConstruction == hasFinishing) {
                return "materialType";
            }
            if (branch == ConsumptionBranch.construction && !hasConstruction) {
                return "constructionMaterialTypeId";
            }
            if (branch == ConsumptionBranch.finishing && !hasFinishing) {
                return "finishingMaterialTypeId";
            }
            // 3. Reference resolution (mandatory first, then the set type id).
            if (workItem != Presence.VALID) {
                return "workItemId";
            }
            if (offerPackage != Presence.VALID) {
                return "offerPackageId";
            }
            if (materialUnit != Presence.VALID) {
                return "materialUnitId";
            }
            if (hasConstruction && !constructionTypeValid) {
                return "constructionMaterialTypeId";
            }
            if (hasFinishing && !finishingTypeValid) {
                return "finishingMaterialTypeId";
            }
            // 4. normQty range.
            return "normQty";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------------------------------

    /**
     * A freshly-mocked {@link WorkMaterialConsumptionService} whose five reference DAOs resolve ONLY
     * the known-valid ids and whose consumption DAO records whether {@code save}/{@code saveAll} were
     * ever called.
     */
    private static final class Fixture {
        final WorkMaterialConsumptionDao dao = Mockito.mock(WorkMaterialConsumptionDao.class);
        final WorkMaterialConsumptionService service;

        Fixture() {
            WorkItemDao workItemDao = Mockito.mock(WorkItemDao.class);
            OfferPackageDao offerPackageDao = Mockito.mock(OfferPackageDao.class);
            MeasurementUnitDao measurementUnitDao = Mockito.mock(MeasurementUnitDao.class);
            ConstructionMaterialTypeDao constructionMaterialTypeDao =
                    Mockito.mock(ConstructionMaterialTypeDao.class);
            MaterialTypeDao materialTypeDao = Mockito.mock(MaterialTypeDao.class);

            Mockito.when(workItemDao.findById(VALID_WORK_ITEM_ID))
                    .thenReturn(Optional.of(new WorkItemEntity()));
            Mockito.when(offerPackageDao.findById(VALID_OFFER_PACKAGE_ID))
                    .thenReturn(Optional.of(new OfferPackageEntity()));
            Mockito.when(measurementUnitDao.findById(VALID_MATERIAL_UNIT_ID))
                    .thenReturn(Optional.of(new MeasurementUnitEntity()));
            Mockito.when(constructionMaterialTypeDao.findById(VALID_CONSTRUCTION_TYPE_ID))
                    .thenReturn(Optional.of(new ConstructionMaterialTypeEntity()));
            Mockito.when(materialTypeDao.findById(VALID_FINISHING_TYPE_ID))
                    .thenReturn(Optional.of(new MaterialTypeEntity()));
            // Every other id resolves to empty (the Mockito default), so any id >= DANGLING_ID_FLOOR
            // — or any known-valid id swapped onto the wrong DAO — is treated as dangling.

            service = new WorkMaterialConsumptionService(
                    dao,
                    Mockito.mock(WorkMaterialConsumptionServiceMapper.class),
                    Mockito.mock(AuditLogDao.class),
                    Mockito.mock(EntityManager.class),
                    workItemDao, offerPackageDao, measurementUnitDao,
                    constructionMaterialTypeDao, materialTypeDao);
        }

        /** Normalization is a pure pre-persist check — the consumption DAO must never be written to. */
        void verifyNothingPersisted() {
            Mockito.verify(dao, Mockito.never()).save(Mockito.any());
            Mockito.verify(dao, Mockito.never()).saveAll(Mockito.any());
        }
    }
}
