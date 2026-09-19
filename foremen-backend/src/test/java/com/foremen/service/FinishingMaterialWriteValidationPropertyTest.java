package com.foremen.service;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.FinishingMaterialServiceExtendedModel;
import com.foremen.service.model.mapper.FinishingMaterialServiceMapper;
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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link FinishingMaterialService} write-path validation
 * (FOR-04-18, Property 1 — "Write validation rejects missing required fields, dangling references,
 * and out-of-range prices").
 *
 * <p>The write path is {@link FinishingMaterialService#validateCreate}/{@code validateUpdate}, both
 * delegating to the private {@code normalize(...)} step. Normalization resolves-and-loads every
 * reference (mandatory {@code categoryId}/{@code materialId}/{@code unitId}, optional {@code typeId}/
 * {@code producerId}, each {@code offerPackageId}), rejects an empty {@code offerPackageIds}, and
 * range-checks the three prices — throwing a {@link ForemenApiException} (404 for a missing/dangling
 * reference naming the field, 400 for an empty package set / out-of-range price naming the field)
 * BEFORE anything is persisted. Because {@code normalize} performs no writes, the six reference DAOs
 * are mocked so only a fixed set of "known-valid" ids resolve, the service is constructed directly
 * with those mocks, and {@code validateCreate}/{@code validateUpdate} are driven with generated
 * models. No persistence occurs — verified by asserting the material DAO's {@code save} was never
 * invoked.
 *
 * <p>Feature: FOR-04-18-finishing-materials, Property 1
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 2.5</b>
 */
@Tag("Feature: FOR-04-18-finishing-materials, Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices")
class FinishingMaterialWriteValidationPropertyTest {

    /** The single set of reference ids the mocked DAOs treat as existing rows. */
    private static final long VALID_CATEGORY_ID = 1L;
    private static final long VALID_MATERIAL_ID = 2L;
    private static final long VALID_TYPE_ID = 3L;
    private static final long VALID_PRODUCER_ID = 4L;
    private static final long VALID_UNIT_ID = 5L;
    private static final Set<Long> VALID_PACKAGE_IDS = Set.of(10L, 11L, 12L);

    /** Any id at or beyond this bound is guaranteed NOT to be one of the known-valid ids above. */
    private static final long DANGLING_ID_FLOOR = 1_000L;

    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999.99");

    // ------------------------------------------------------------------------------------------
    // Property 1a: a request that omits a required reference (categoryId/materialId/unitId) OR has
    //              an empty packages set is rejected naming the missing field, with NO persistence.
    // Validates: Requirements 2.2, 2.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices")
    void missingRequiredFieldIsRejectedNamingTheField(@ForAll("missingRequiredCases") DefectCase defectCase) {
        Fixture fixture = new Fixture();
        FinishingMaterialServiceExtendedModel model = validModel();
        defectCase.apply(model);

        assertThatThrownBy(() -> fixture.service.validateCreate(model))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    // Missing required reference -> 404; empty package set -> 400. Either way the
                    // first message param names the offending field.
                    assertThat(ex.getStatus())
                            .isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageParams()).isNotEmpty();
                    assertThat(ex.getMessageParams()[0]).isEqualTo(defectCase.expectedField());
                });

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b: a request whose SOLE defect is a dangling id in ANY of the six reference fields
    //              is rejected with 404 naming that field, with NO persistence.
    // Validates: Requirement 2.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices")
    void danglingReferenceIsRejectedNamingTheField(@ForAll("danglingReferenceCases") DefectCase defectCase) {
        Fixture fixture = new Fixture();
        FinishingMaterialServiceExtendedModel model = validModel();
        defectCase.apply(model);

        assertThatThrownBy(() -> fixture.service.validateCreate(model))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    // The first message param is the offending field name (see notFound(...)).
                    assertThat(ex.getMessageParams()).isNotEmpty();
                    assertThat(ex.getMessageParams()[0]).isEqualTo(defectCase.expectedField());
                });

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Property 1c: a request whose SOLE defect is an out-of-range price is rejected with 400
    //              naming that price field, with NO persistence.
    // Validates: Requirement 2.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices")
    void outOfRangePriceIsRejectedNamingTheField(@ForAll("outOfRangePriceCases") PriceCase priceCase) {
        Fixture fixture = new Fixture();
        FinishingMaterialServiceExtendedModel model = validModel();
        priceCase.apply(model);

        assertThatThrownBy(() -> fixture.service.validateUpdate(null, model))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageParams()).isNotEmpty();
                    assertThat(ex.getMessageParams()[0]).isEqualTo(priceCase.field());
                });

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Property 1d: an all-valid request (required references present and existing, optional
    //              references null-or-existing, >= 1 existing package, prices in range) is accepted
    //              — no exception, and still no persistence (normalize is a pure pre-persist check).
    // Validates: Requirements 2.2, 2.3, 2.4, 2.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 1: Write validation rejects missing required fields, dangling references, and out-of-range prices")
    void allValidRequestIsAccepted(@ForAll("validModels") FinishingMaterialServiceExtendedModel model) {
        Fixture fixture = new Fixture();

        fixture.service.validateCreate(model);
        fixture.service.validateUpdate(null, model);

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A dangling id guaranteed to be absent from the known-valid set. */
    @Provide
    Arbitrary<Long> danglingIds() {
        return Arbitraries.longs().between(DANGLING_ID_FLOOR, Long.MAX_VALUE);
    }

    /** One missing-required-field case per mandatory reference plus the empty-packages case. */
    @Provide
    Arbitrary<DefectCase> missingRequiredCases() {
        return Arbitraries.of(
                new DefectCase("categoryId", m -> m.setCategoryId(null)),
                new DefectCase("materialId", m -> m.setMaterialId(null)),
                new DefectCase("unitId", m -> m.setUnitId(null)),
                new DefectCase("packages", m -> m.setOfferPackageIds(new LinkedHashSet<>())));
    }

    /** One dangling-reference case per reference field (mandatory + optional + package). */
    @Provide
    Arbitrary<DefectCase> danglingReferenceCases() {
        Arbitrary<Long> dangling = danglingIds();

        Arbitrary<DefectCase> categoryDangling = dangling.map(id ->
                new DefectCase("categoryId", m -> m.setCategoryId(id)));
        Arbitrary<DefectCase> materialDangling = dangling.map(id ->
                new DefectCase("materialId", m -> m.setMaterialId(id)));
        Arbitrary<DefectCase> unitDangling = dangling.map(id ->
                new DefectCase("unitId", m -> m.setUnitId(id)));

        // Optional references: only a NON-null dangling id is rejected (null is allowed, so excluded here).
        Arbitrary<DefectCase> typeDangling = dangling.map(id ->
                new DefectCase("typeId", m -> m.setTypeId(id)));
        Arbitrary<DefectCase> producerDangling = dangling.map(id ->
                new DefectCase("producerId", m -> m.setProducerId(id)));

        // A dangling id mixed into the (otherwise valid) package set.
        Arbitrary<DefectCase> packageDangling = dangling.map(id ->
                new DefectCase("offerPackageIds", m -> {
                    Set<Long> ids = new LinkedHashSet<>(VALID_PACKAGE_IDS);
                    ids.add(id);
                    m.setOfferPackageIds(ids);
                }));

        return Arbitraries.oneOf(
                categoryDangling, materialDangling, unitDangling,
                typeDangling, producerDangling, packageDangling);
    }

    /** One out-of-range price per price field (negative or above the max), on an otherwise valid model. */
    @Provide
    Arbitrary<PriceCase> outOfRangePriceCases() {
        Arbitrary<BigDecimal> negative = Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000000.00"), new BigDecimal("-0.01"))
                .ofScale(2);
        Arbitrary<BigDecimal> aboveMax = Arbitraries.bigDecimals()
                .between(PRICE_MAX.add(new BigDecimal("0.01")), PRICE_MAX.add(new BigDecimal("1000000.00")))
                .ofScale(2);
        Arbitrary<BigDecimal> outOfRange = Arbitraries.oneOf(negative, aboveMax);

        Arbitrary<String> field = Arbitraries.of("purchasePrice", "retailGross", "retailNet");

        return Combinators.combine(field, outOfRange).as(PriceCase::new);
    }

    /** Fully-valid write models: references from the known-valid set, in-range prices, >= 1 package. */
    @Provide
    Arbitrary<FinishingMaterialServiceExtendedModel> validModels() {
        Arbitrary<Long> type = Arbitraries.oneOf(
                Arbitraries.just((Long) null), Arbitraries.just(VALID_TYPE_ID));
        Arbitrary<Long> producer = Arbitraries.oneOf(
                Arbitraries.just((Long) null), Arbitraries.just(VALID_PRODUCER_ID));

        // A non-empty subset of the valid package ids.
        Arbitrary<Set<Long>> packages = Arbitraries.subsetOf(VALID_PACKAGE_IDS)
                .filter(s -> !s.isEmpty())
                .map(LinkedHashSet::new);

        Arbitrary<BigDecimal> inRangePrice = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, PRICE_MAX)
                .ofScale(2)
                .injectNull(0.3);

        return Combinators.combine(
                type, producer, packages,
                inRangePrice, inRangePrice, inRangePrice)
                .as((typeId, producerId, pkgs, purchase, gross, net) -> {
                    FinishingMaterialServiceExtendedModel m = new FinishingMaterialServiceExtendedModel();
                    m.setCategoryId(VALID_CATEGORY_ID);
                    m.setMaterialId(VALID_MATERIAL_ID);
                    m.setTypeId(typeId);
                    m.setProducerId(producerId);
                    m.setOfferPackageIds(pkgs);
                    m.setUnitId(VALID_UNIT_ID);
                    m.setModel("Model");
                    m.setSku("SKU-1");
                    m.setPurchasePrice(purchase);
                    m.setRetailGross(gross);
                    m.setRetailNet(net);
                    m.setActive(true);
                    return m;
                });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** A baseline all-valid model that individual defect cases then mutate one field of. */
    private static FinishingMaterialServiceExtendedModel validModel() {
        FinishingMaterialServiceExtendedModel m = new FinishingMaterialServiceExtendedModel();
        m.setCategoryId(VALID_CATEGORY_ID);
        m.setMaterialId(VALID_MATERIAL_ID);
        m.setTypeId(VALID_TYPE_ID);
        m.setProducerId(VALID_PRODUCER_ID);
        m.setOfferPackageIds(new LinkedHashSet<>(VALID_PACKAGE_IDS));
        m.setUnitId(VALID_UNIT_ID);
        m.setModel("Model");
        m.setSku("SKU-1");
        m.setPurchasePrice(new BigDecimal("10.00"));
        m.setRetailGross(new BigDecimal("20.00"));
        m.setRetailNet(new BigDecimal("15.00"));
        m.setLink("https://example.com");
        m.setActive(true);
        return m;
    }

    /** A single-field defect mutation plus the field name the exception must name. */
    private record DefectCase(String expectedField,
                              java.util.function.Consumer<FinishingMaterialServiceExtendedModel> mutation) {
        void apply(FinishingMaterialServiceExtendedModel model) {
            mutation.accept(model);
        }
    }

    /** An out-of-range price on a named field. */
    private record PriceCase(String field, BigDecimal value) {
        void apply(FinishingMaterialServiceExtendedModel model) {
            switch (field) {
                case "purchasePrice" -> model.setPurchasePrice(value);
                case "retailGross" -> model.setRetailGross(value);
                case "retailNet" -> model.setRetailNet(value);
                default -> throw new IllegalArgumentException("Unknown price field: " + field);
            }
        }
    }

    /**
     * A freshly-mocked {@link FinishingMaterialService} whose six reference DAOs resolve ONLY the
     * known-valid ids and whose material DAO records whether {@code save} was ever called.
     */
    private static final class Fixture {
        final FinishingMaterialDao dao = Mockito.mock(FinishingMaterialDao.class);
        final FinishingMaterialService service;

        Fixture() {
            MaterialCategoryDao categoryDao = Mockito.mock(MaterialCategoryDao.class);
            MaterialDao materialDao = Mockito.mock(MaterialDao.class);
            MaterialTypeDao typeDao = Mockito.mock(MaterialTypeDao.class);
            MaterialProducerDao producerDao = Mockito.mock(MaterialProducerDao.class);
            MeasurementUnitDao unitDao = Mockito.mock(MeasurementUnitDao.class);
            OfferPackageDao offerPackageDao = Mockito.mock(OfferPackageDao.class);

            Mockito.when(categoryDao.findById(VALID_CATEGORY_ID))
                    .thenReturn(Optional.of(new MaterialCategoryEntity()));
            Mockito.when(materialDao.findById(VALID_MATERIAL_ID))
                    .thenReturn(Optional.of(new MaterialEntity()));
            Mockito.when(typeDao.findById(VALID_TYPE_ID))
                    .thenReturn(Optional.of(new MaterialTypeEntity()));
            Mockito.when(producerDao.findById(VALID_PRODUCER_ID))
                    .thenReturn(Optional.of(new MaterialProducerEntity()));
            Mockito.when(unitDao.findById(VALID_UNIT_ID))
                    .thenReturn(Optional.of(new MeasurementUnitEntity()));
            for (Long packageId : VALID_PACKAGE_IDS) {
                Mockito.when(offerPackageDao.findById(packageId))
                        .thenReturn(Optional.of(new OfferPackageEntity()));
            }
            // Every other id resolves to empty (the Mockito default), so any id >= DANGLING_ID_FLOOR
            // — or any known-valid id swapped onto the wrong DAO — is treated as dangling.

            service = new FinishingMaterialService(
                    dao,
                    Mockito.mock(FinishingMaterialServiceMapper.class),
                    Mockito.mock(AuditLogDao.class),
                    Mockito.mock(EntityManager.class),
                    Mockito.mock(ImageStorage.class),
                    categoryDao, materialDao, typeDao, producerDao, unitDao, offerPackageDao);
        }

        /** Normalization is a pure pre-persist check — the material DAO must never be written to. */
        void verifyNothingPersisted() {
            Mockito.verify(dao, Mockito.never()).save(Mockito.any());
            Mockito.verify(dao, Mockito.never()).saveAll(Mockito.any());
        }
    }
}
