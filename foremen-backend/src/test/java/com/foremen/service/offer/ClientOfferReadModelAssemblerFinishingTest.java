package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.service.image.ImageStorage;

/**
 * FOR-05-07 — unit guard for {@link ClientOfferReadModelAssembler}'s finishing-selection surface.
 *
 * <p>The assembler previously hardcoded {@code new ArrayList<>()} for the client read model's
 * {@code finishing} field (a leftover skeleton stub), so the offer tab's finishing section was
 * always empty. This test pins the fix: {@link ClientOfferReadModelAssembler#toClientReadModel} now
 * projects the referenced estimate's FINISHING material lines — one {@link FinishingSelectionView}
 * per line (R11, keyed by {@code materialLineId}, not pre-aggregated) — covering both an unchosen
 * Placeholder and a chosen line, mapping ONLY the offer-facing {@code Type_Price_Range} band and the
 * chosen product's offer price ({@code concreteNet}), never any cost/margin (Property 21 / R15).
 *
 * <p>The DAO finder is mocked (the traversal is covered by the DAO's query); the test asserts the
 * mapping the assembler performs over the returned lines, plus that the finishing view's shape stays
 * confidential-free.
 *
 * <p>Validates: Requirements 11.3, 11.4, 15.1, 15.2, 19.3
 */
@DisplayName("ClientOfferReadModelAssembler — finishing selection surface (R11, R15)")
@Tag("Feature: FOR-05-07-offer-approval, offer finishing-selection surface")
class ClientOfferReadModelAssemblerFinishingTest {

    private static final Long ESTIMATE_ID = 77L;
    private static final Long TYPE_ID = 555L;

    private final EstimateLineRoomMaterialDao dao = mock(EstimateLineRoomMaterialDao.class);
    private final ImageStorage imageStorage = mock(ImageStorage.class);
    private final ClientOfferReadModelAssembler assembler =
            new ClientOfferReadModelAssembler(new OfferVisibilityResolver(), dao, imageStorage);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("projects both an unchosen Placeholder and a chosen finishing line with correct flags/prices")
    void projectsPlaceholderAndChosenFinishingLines() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        when(imageStorage.toCdnUrl(eq("finishing/photo.webp"))).thenReturn("https://cdn/finishing/photo.webp");

        EstimateLineRoomMaterialEntity placeholder = finishingLine(
                101L, "Tynk", /* placeholderName */ null,
                new BigDecimal("10.00"), new BigDecimal("20.00"), /* concreteNet */ null);
        placeholder.setAppliedFromPackage(true);
        EstimateLineRoomMaterialEntity chosen = finishingLine(
                102L, "Farba", /* chosenProductName */ "Dulux Biala",
                new BigDecimal("5.00"), new BigDecimal("30.00"), new BigDecimal("18.50"));
        chosen.setAppliedFromPackage(true);
        chosen.getConcreteFinishingMaterial().setPhoto("finishing/photo.webp");
        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(placeholder, chosen));

        OfferEntity offer = offerWithEstimate();

        List<FinishingSelectionView> finishing =
                assembler.toClientReadModel(offer).finishing();

        assertThat(finishing).hasSize(2);

        FinishingSelectionView pv = finishing.get(0);
        assertThat(pv.materialLineId()).isEqualTo(101L);
        assertThat(pv.materialLineName()).isEqualTo("Tynk"); // PL localized finishing type name
        assertThat(pv.placeholder()).isTrue();
        assertThat(pv.chosenProductId()).isNull();
        assertThat(pv.chosenProductName()).isNull();
        assertThat(pv.chosenOfferPrice()).isNull();
        assertThat(pv.priceRangeMin()).isEqualByComparingTo("10.00");
        assertThat(pv.priceRangeMax()).isEqualByComparingTo("20.00");
        // New surface fields: appliedFromPackage carried through, finishingTypeId mapped, no chosen
        // product → no image, no differs flag.
        assertThat(pv.appliedFromPackage()).isTrue();
        assertThat(pv.finishingTypeId()).isEqualTo(TYPE_ID);
        assertThat(pv.imageUrl()).isNull();
        assertThat(pv.chosenPackageDiffersFromSelected()).isFalse();
        assertThat(pv.chosenProductPackageName()).isNull();

        FinishingSelectionView cv = finishing.get(1);
        assertThat(cv.materialLineId()).isEqualTo(102L);
        assertThat(cv.materialLineName()).isEqualTo("Farba");
        assertThat(cv.placeholder()).isFalse();
        assertThat(cv.chosenProductId()).isEqualTo(9001L);
        assertThat(cv.chosenProductName()).isEqualTo("Dulux Biala");
        assertThat(cv.chosenOfferPrice()).isEqualByComparingTo("18.50");
        assertThat(cv.priceRangeMin()).isEqualByComparingTo("5.00");
        assertThat(cv.priceRangeMax()).isEqualByComparingTo("30.00");
        // New surface fields: appliedFromPackage carried through, finishingTypeId mapped, image URL
        // resolved from the chosen product's photo via ImageStorage. No selected package on the offer
        // here → differs flag false / name null.
        assertThat(cv.appliedFromPackage()).isTrue();
        assertThat(cv.finishingTypeId()).isEqualTo(TYPE_ID);
        assertThat(cv.imageUrl()).isEqualTo("https://cdn/finishing/photo.webp");
        assertThat(cv.chosenPackageDiffersFromSelected()).isFalse();
        assertThat(cv.chosenProductPackageName()).isNull();
    }

    @Test
    @DisplayName("chosen product IN the selected package → differs=false/name=null; NOT in → differs=true/name; placeholder → false/null")
    void differsFromSelectedPackageLogic() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        lenient().when(imageStorage.toCdnUrl(any())).thenReturn(null);

        OfferPackageEntity standard = offerPackage("STD", "Standard RU", "Standard PL");
        OfferPackageEntity premium = offerPackage("PRM", "Premium RU", "Premium PL");

        // Chosen product that IS in the offer's selected package (STD).
        EstimateLineRoomMaterialEntity inSelected = finishingLine(
                201L, "Farba", "W selected", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"));
        inSelected.getConcreteFinishingMaterial().setPackages(Set.of(standard));

        // Chosen product that is NOT in the selected package (only PRM) → differs, name = Premium PL.
        EstimateLineRoomMaterialEntity notInSelected = finishingLine(
                202L, "Panel", "Poza selected", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"));
        notInSelected.getConcreteFinishingMaterial().setPackages(Set.of(premium));

        // A placeholder (no chosen product) → always differs=false/name=null.
        EstimateLineRoomMaterialEntity placeholder = finishingLine(
                203L, "Tynk", null, new BigDecimal("1.00"), new BigDecimal("2.00"), null);

        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(inSelected, notInSelected, placeholder));

        OfferEntity offer = offerWithEstimate();
        offer.setSelectedPackage(standard);

        List<FinishingSelectionView> finishing = assembler.toClientReadModel(offer).finishing();

        FinishingSelectionView inSel = finishing.get(0);
        assertThat(inSel.chosenPackageDiffersFromSelected()).isFalse();
        assertThat(inSel.chosenProductPackageName()).isNull();

        FinishingSelectionView notInSel = finishing.get(1);
        assertThat(notInSel.chosenPackageDiffersFromSelected()).isTrue();
        assertThat(notInSel.chosenProductPackageName()).isEqualTo("Premium PL");

        FinishingSelectionView ph = finishing.get(2);
        assertThat(ph.chosenPackageDiffersFromSelected()).isFalse();
        assertThat(ph.chosenProductPackageName()).isNull();
    }

    @Test
    @DisplayName("Russian locale selects the RU finishing-type name")
    void localizesFinishingTypeNameForRussianLocale() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("ru"));

        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setNameRU("Штукатурка");
        type.setNamePL("Tynk");
        EstimateLineRoomMaterialEntity line = new EstimateLineRoomMaterialEntity();
        line.setId(201L);
        line.setBranch(ConsumptionBranch.finishing);
        line.setFinishingType(type);
        line.setRangeMin(new BigDecimal("1.00"));
        line.setRangeMax(new BigDecimal("2.00"));
        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(line));

        List<FinishingSelectionView> finishing =
                assembler.toClientReadModel(offerWithEstimate()).finishing();

        assertThat(finishing).singleElement()
                .extracting(FinishingSelectionView::materialLineName)
                .isEqualTo("Штукатурка");
    }

    @Test
    @DisplayName("a finishing line with no type falls back to a non-null label (never NPEs)")
    void fallsBackToNonNullLabelWhenTypeMissing() {
        EstimateLineRoomMaterialEntity line = new EstimateLineRoomMaterialEntity();
        line.setId(301L);
        line.setBranch(ConsumptionBranch.finishing);
        line.setFinishingType(null);
        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(line));

        List<FinishingSelectionView> finishing =
                assembler.toClientReadModel(offerWithEstimate()).finishing();

        assertThat(finishing).singleElement()
                .satisfies(v -> {
                    assertThat(v.materialLineName()).isNotNull();
                    assertThat(v.placeholder()).isTrue();
                });
    }

    @Test
    @DisplayName("chosenProductName composes '{model} · {producer}' when the chosen product has both")
    void composesModelAndProducerForChosenProductName() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        lenient().when(imageStorage.toCdnUrl(any())).thenReturn(null);

        // Chosen product with a specific model AND a producer → product line shows "{model} · {producer}".
        EstimateLineRoomMaterialEntity withModelAndProducer = finishingLine(
                401L, "Drzwi", "Drzwi RU"/*material (generic fallback, not used)*/,
                new BigDecimal("1.00"), new BigDecimal("2.00"), new BigDecimal("1.50"));
        setModelAndProducer(withModelAndProducer, "Porta Verte", "Porta", "Porta");

        // Chosen product with a model but NO producer → just the model.
        EstimateLineRoomMaterialEntity withModelOnly = finishingLine(
                402L, "Klamki", "Klamki RU", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"));
        setModelAndProducer(withModelOnly, "Hoppe Amsterdam", null, null);

        // Chosen product with NEITHER model NOR producer but a material name → the material name.
        EstimateLineRoomMaterialEntity materialOnly = finishingLine(
                403L, "Panel", "Panel winylowy", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"));

        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(withModelAndProducer, withModelOnly, materialOnly));

        List<FinishingSelectionView> finishing =
                assembler.toClientReadModel(offerWithEstimate()).finishing();

        assertThat(finishing.get(0).chosenProductName()).isEqualTo("Porta Verte · Porta");
        assertThat(finishing.get(1).chosenProductName()).isEqualTo("Hoppe Amsterdam");
        assertThat(finishing.get(2).chosenProductName()).isEqualTo("Panel winylowy");
    }

    @Test
    @DisplayName("Russian locale composes the RU producer name into the chosen product name")
    void composesRussianProducerName() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("ru"));
        lenient().when(imageStorage.toCdnUrl(any())).thenReturn(null);

        EstimateLineRoomMaterialEntity line = finishingLine(
                501L, "Двери", "Двери RU", new BigDecimal("1.00"), new BigDecimal("2.00"),
                new BigDecimal("1.50"));
        setModelAndProducer(line, "Porta Verte", "Порта", "Porta");

        when(dao.findByEstimateIdAndBranch(eq(ESTIMATE_ID), eq(ConsumptionBranch.finishing)))
                .thenReturn(List.of(line));

        List<FinishingSelectionView> finishing =
                assembler.toClientReadModel(offerWithEstimate()).finishing();

        assertThat(finishing).singleElement()
                .extracting(FinishingSelectionView::chosenProductName)
                .isEqualTo("Porta Verte · Порта");
    }

    @Test
    @DisplayName("an offer whose estimate is null yields an empty (non-null) finishing surface")
    void nullEstimateYieldsEmptySurface() {
        OfferEntity offer = new OfferEntity();
        offer.setStatus(OfferStatus.SENT);
        offer.setEstimate(null);

        assertThat(assembler.toClientReadModel(offer).finishing()).isEmpty();
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static OfferPackageEntity offerPackage(String code, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(code);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        return pkg;
    }

    private static OfferEntity offerWithEstimate() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(ESTIMATE_ID);
        OfferEntity offer = new OfferEntity();
        offer.setId(1L);
        offer.setStatus(OfferStatus.SENT);
        offer.setRevision(1);
        offer.setEstimate(estimate);
        return offer;
    }

    /**
     * A finishing material line. When {@code chosenProductName} is non-null the line is "chosen" (a
     * concrete finishing product with that localized name and the given {@code concreteNet}); when
     * null it is an unchosen Placeholder.
     */
    private static EstimateLineRoomMaterialEntity finishingLine(
            Long lineId, String typeNamePL, String chosenProductName,
            BigDecimal rangeMin, BigDecimal rangeMax, BigDecimal concreteNet) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setId(TYPE_ID);
        type.setNameRU(typeNamePL + " RU");
        type.setNamePL(typeNamePL);

        EstimateLineRoomMaterialEntity line = new EstimateLineRoomMaterialEntity();
        line.setId(lineId);
        line.setBranch(ConsumptionBranch.finishing);
        line.setFinishingType(type);
        line.setRangeMin(rangeMin);
        line.setRangeMax(rangeMax);

        if (chosenProductName != null) {
            MaterialEntity material = new MaterialEntity();
            material.setNameRU(chosenProductName + " RU");
            material.setNamePL(chosenProductName);
            FinishingMaterialEntity concrete = new FinishingMaterialEntity();
            concrete.setId(9001L);
            concrete.setMaterial(material);
            line.setConcreteFinishingMaterial(concrete);
            line.setConcreteNet(concreteNet);
        }
        return line;
    }

    /**
     * Sets the chosen product's specific {@code model} (its concrete product name) and — when
     * {@code producerNameRU} is non-null — a {@link MaterialProducerEntity} with the given localized
     * names, so the composed {@code chosenProductName} resolves to {@code "{model} · {producer}"}.
     */
    private static void setModelAndProducer(
            EstimateLineRoomMaterialEntity line, String model, String producerNameRU, String producerNamePL) {
        FinishingMaterialEntity concrete = line.getConcreteFinishingMaterial();
        concrete.setModel(model);
        if (producerNameRU != null || producerNamePL != null) {
            MaterialProducerEntity producer = new MaterialProducerEntity();
            producer.setNameRU(producerNameRU);
            producer.setNamePL(producerNamePL);
            concrete.setProducer(producer);
        }
    }
}
