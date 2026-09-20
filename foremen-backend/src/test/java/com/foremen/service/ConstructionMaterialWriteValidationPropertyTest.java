package com.foremen.service;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.MaterialProducerDao;
import com.foremen.dao.MaterialSellerDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.mapper.ConstructionMaterialServiceMapper;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.WithNull;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link ConstructionMaterialService} write-path validation
 * (FOR-04-17, Property 4 — "Write validation rejects dangling references and out-of-range prices").
 *
 * <p>The write path is {@link ConstructionMaterialService#validateCreate}/{@code validateUpdate},
 * both delegating to the private {@code normalize(...)} step. Normalization resolves-and-loads every
 * reference (mandatory {@code typeId}/{@code unitId}/{@code currencyId}, optional {@code producerId}/
 * {@code sellerId}, each {@code offerPackageId}), rejects an empty {@code offerPackageIds}, and
 * range-checks the three prices — throwing a {@link ForemenApiException} (404 for a dangling
 * reference naming the field, 400 for an out-of-range price / empty packages / long website) BEFORE
 * anything is persisted. Because {@code normalize} performs no writes, the six reference DAOs are
 * mocked to make only a fixed set of "known-valid" ids resolve, the service is constructed directly
 * with those mocks, and {@code validateCreate}/{@code validateUpdate} are driven with generated
 * models. No persistence occurs — verified by asserting the material DAO's {@code save} was never
 * invoked.
 *
 * <p>Feature: FOR-04-17-construction-materials, Property 4
 *
 * <p><b>Validates: Requirements 4.3, 4.4, 4.5</b>
 */
@Tag("Feature: FOR-04-17-construction-materials, Property 4: Write validation rejects dangling references and out-of-range prices")
class ConstructionMaterialWriteValidationPropertyTest {

    /** The single set of reference ids the mocked DAOs treat as existing rows. */
    private static final long VALID_TYPE_ID = 1L;
    private static final long VALID_PRODUCER_ID = 2L;
    private static final long VALID_SELLER_ID = 3L;
    private static final long VALID_UNIT_ID = 4L;
    private static final long VALID_CURRENCY_ID = 5L;
    private static final Set<Long> VALID_PACKAGE_IDS = Set.of(10L, 11L, 12L);

    /** Any id at or beyond this bound is guaranteed NOT to be one of the known-valid ids above. */
    private static final long DANGLING_ID_FLOOR = 1_000L;

    private static final BigDecimal PRICE_MAX = new BigDecimal("9999999999.99");

    // ------------------------------------------------------------------------------------------
    // Property 4a: a request with a dangling id in ANY reference field is rejected naming that
    //              field, with 404 and NO persistence.
    // Validates: Requirements 4.3, 4.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 4: Write validation rejects dangling references and out-of-range prices")
    void danglingReferenceIsRejectedNamingTheField(@ForAll("danglingReferenceCases") DanglingCase danglingCase) {
        Fixture fixture = new Fixture();
        ConstructionMaterialServiceExtendedModel model = validModel();
        danglingCase.apply(model);

        assertThatThrownBy(() -> fixture.service.validateCreate(model))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    // The first message param is the offending field name (see notFound(...)).
                    assertThat(ex.getMessageParams()).isNotEmpty();
                    assertThat(ex.getMessageParams()[0]).isEqualTo(danglingCase.expectedField());
                });

        fixture.verifyNothingPersisted();
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b: a request whose SOLE defect is an out-of-range price is rejected with 400
    //              naming that price field, with NO persistence.
    // Validates: Requirement 4.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 4: Write validation rejects dangling references and out-of-range prices")
    void outOfRangePriceIsRejectedNamingTheField(@ForAll("outOfRangePriceCases") PriceCase priceCase) {
        Fixture fixture = new Fixture();
        ConstructionMaterialServiceExtendedModel model = validModel();
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
    // Property 4c: an all-valid request (references exist, prices in range, required fields
    //              present, >= 1 package) is accepted — no exception, and still no persistence
    //              (normalize is a pure pre-persist check).
    // Validates: Requirements 4.3, 4.4, 4.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 4: Write validation rejects dangling references and out-of-range prices")
    void allValidRequestIsAccepted(@ForAll("validModels") ConstructionMaterialServiceExtendedModel model) {
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

    /** One dangling-reference case per reference field (mandatory nullable-or-dangling + optional dangling + package). */
    @Provide
    Arbitrary<DanglingCase> danglingReferenceCases() {
        Arbitrary<Long> dangling = danglingIds();

        Arbitrary<DanglingCase> typeDangling = dangling.map(id ->
                new DanglingCase("typeId", m -> m.setTypeId(id)));
        Arbitrary<DanglingCase> typeNull =
                Arbitraries.just(new DanglingCase("typeId", m -> m.setTypeId(null)));

        Arbitrary<DanglingCase> unitDangling = dangling.map(id ->
                new DanglingCase("unitId", m -> m.setUnitId(id)));
        Arbitrary<DanglingCase> unitNull =
                Arbitraries.just(new DanglingCase("unitId", m -> m.setUnitId(null)));

        Arbitrary<DanglingCase> currencyDangling = dangling.map(id ->
                new DanglingCase("currencyId", m -> m.setCurrencyId(id)));
        Arbitrary<DanglingCase> currencyNull =
                Arbitraries.just(new DanglingCase("currencyId", m -> m.setCurrencyId(null)));

        // Optional references: only a NON-null dangling id is rejected (null is allowed, so excluded here).
        Arbitrary<DanglingCase> producerDangling = dangling.map(id ->
                new DanglingCase("producerId", m -> m.setProducerId(id)));
        Arbitrary<DanglingCase> sellerDangling = dangling.map(id ->
                new DanglingCase("sellerId", m -> m.setSellerId(id)));

        // A dangling id mixed into the (otherwise valid) package set.
        Arbitrary<DanglingCase> packageDangling = dangling.map(id ->
                new DanglingCase("offerPackageIds", m -> {
                    Set<Long> ids = new LinkedHashSet<>(VALID_PACKAGE_IDS);
                    ids.add(id);
                    m.setOfferPackageIds(ids);
                }));

        return Arbitraries.oneOf(
                typeDangling, typeNull,
                unitDangling, unitNull,
                currencyDangling, currencyNull,
                producerDangling, sellerDangling,
                packageDangling);
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
    Arbitrary<ConstructionMaterialServiceExtendedModel> validModels() {
        Arbitrary<Long> producer = Arbitraries.oneOf(
                Arbitraries.just((Long) null), Arbitraries.just(VALID_PRODUCER_ID));
        Arbitrary<Long> seller = Arbitraries.oneOf(
                Arbitraries.just((Long) null), Arbitraries.just(VALID_SELLER_ID));

        // A non-empty subset of the valid package ids.
        Arbitrary<Set<Long>> packages = Arbitraries.subsetOf(VALID_PACKAGE_IDS)
                .filter(s -> !s.isEmpty())
                .map(LinkedHashSet::new);

        Arbitrary<BigDecimal> inRangePrice = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, PRICE_MAX)
                .ofScale(2)
                .injectNull(0.3);

        Arbitrary<String> website = Arbitraries.strings()
                .withCharRange('a', 'z').ofMaxLength(255)
                .injectNull(0.5);

        return Combinators.combine(
                producer, seller, packages,
                inRangePrice, inRangePrice, inRangePrice,
                website)
                .as((prod, sell, pkgs, purchase, gross, net, site) -> {
                    ConstructionMaterialServiceExtendedModel m = new ConstructionMaterialServiceExtendedModel();
                    m.setNameRU("Материал");
                    m.setNamePL("Materiał");
                    m.setTypeId(VALID_TYPE_ID);
                    m.setProducerId(prod);
                    m.setSellerId(sell);
                    m.setOfferPackageIds(pkgs);
                    m.setUnitId(VALID_UNIT_ID);
                    m.setCurrencyId(VALID_CURRENCY_ID);
                    m.setPurchasePrice(purchase);
                    m.setRetailGross(gross);
                    m.setRetailNet(net);
                    m.setWebsite(site);
                    m.setActive(true);
                    return m;
                });
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** A baseline all-valid model that individual defect cases then mutate one field of. */
    private static ConstructionMaterialServiceExtendedModel validModel() {
        ConstructionMaterialServiceExtendedModel m = new ConstructionMaterialServiceExtendedModel();
        m.setNameRU("Материал");
        m.setNamePL("Materiał");
        m.setTypeId(VALID_TYPE_ID);
        m.setProducerId(VALID_PRODUCER_ID);
        m.setSellerId(VALID_SELLER_ID);
        m.setOfferPackageIds(new LinkedHashSet<>(VALID_PACKAGE_IDS));
        m.setUnitId(VALID_UNIT_ID);
        m.setCurrencyId(VALID_CURRENCY_ID);
        m.setPurchasePrice(new BigDecimal("10.00"));
        m.setRetailGross(new BigDecimal("20.00"));
        m.setRetailNet(new BigDecimal("15.00"));
        m.setWebsite("https://example.com");
        m.setActive(true);
        return m;
    }

    /** A dangling/null-reference mutation plus the field name the exception must name. */
    private record DanglingCase(String expectedField,
                                java.util.function.Consumer<ConstructionMaterialServiceExtendedModel> mutation) {
        void apply(ConstructionMaterialServiceExtendedModel model) {
            mutation.accept(model);
        }
    }

    /** An out-of-range price on a named field. */
    private record PriceCase(String field, BigDecimal value) {
        void apply(ConstructionMaterialServiceExtendedModel model) {
            switch (field) {
                case "purchasePrice" -> model.setPurchasePrice(value);
                case "retailGross" -> model.setRetailGross(value);
                case "retailNet" -> model.setRetailNet(value);
                default -> throw new IllegalArgumentException("Unknown price field: " + field);
            }
        }
    }

    /**
     * A freshly-mocked {@link ConstructionMaterialService} whose six reference DAOs resolve ONLY the
     * known-valid ids and whose material DAO records whether {@code save} was ever called.
     */
    private static final class Fixture {
        final ConstructionMaterialDao dao = Mockito.mock(ConstructionMaterialDao.class);
        final ConstructionMaterialService service;

        Fixture() {
            ConstructionMaterialTypeDao typeDao = Mockito.mock(ConstructionMaterialTypeDao.class);
            MaterialProducerDao producerDao = Mockito.mock(MaterialProducerDao.class);
            MaterialSellerDao sellerDao = Mockito.mock(MaterialSellerDao.class);
            MeasurementUnitDao unitDao = Mockito.mock(MeasurementUnitDao.class);
            CurrencyDao currencyDao = Mockito.mock(CurrencyDao.class);
            OfferPackageDao offerPackageDao = Mockito.mock(OfferPackageDao.class);

            Mockito.when(typeDao.findById(VALID_TYPE_ID))
                    .thenReturn(Optional.of(new ConstructionMaterialTypeEntity()));
            Mockito.when(producerDao.findById(VALID_PRODUCER_ID))
                    .thenReturn(Optional.of(new MaterialProducerEntity()));
            Mockito.when(sellerDao.findById(VALID_SELLER_ID))
                    .thenReturn(Optional.of(new MaterialSellerEntity()));
            Mockito.when(unitDao.findById(VALID_UNIT_ID))
                    .thenReturn(Optional.of(new MeasurementUnitEntity()));
            Mockito.when(currencyDao.findById(VALID_CURRENCY_ID))
                    .thenReturn(Optional.of(new CurrencyEntity()));
            for (Long packageId : VALID_PACKAGE_IDS) {
                Mockito.when(offerPackageDao.findById(packageId))
                        .thenReturn(Optional.of(new OfferPackageEntity()));
            }
            // Every other id resolves to empty (the Mockito default), so any id >= DANGLING_ID_FLOOR
            // — or any known-valid id swapped onto the wrong DAO — is treated as dangling.

            service = new ConstructionMaterialService(
                    dao,
                    Mockito.mock(ConstructionMaterialServiceMapper.class),
                    Mockito.mock(AuditLogDao.class),
                    Mockito.mock(EntityManager.class),
                    Mockito.mock(ImageStorage.class),
                    typeDao, producerDao, sellerDao, unitDao, currencyDao, offerPackageDao);
        }

        /** Normalization is a pure pre-persist check — the material DAO must never be written to. */
        void verifyNothingPersisted() {
            Mockito.verify(dao, Mockito.never()).save(Mockito.any());
            Mockito.verify(dao, Mockito.never()).saveAll(Mockito.any());
        }
    }
}
