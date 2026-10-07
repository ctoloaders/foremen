package com.foremen.service.estimate.matrix;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * Unit guard for {@link EstimateMatrixAssembler}'s package-divergence surface on the matrix read
 * model (FOR-05-07, mirroring the offer side). A finishing material line now carries whether its
 * CHOSEN concrete product belongs to a package DIFFERENT from the estimate's applied package
 * ({@code chosenPackageDiffersFromApplied}) and, when it does, a representative localized package
 * name ({@code chosenProductPackageName}) — the lowest package code's localized name.
 *
 * <p>The assembler compares the chosen finishing product's package CODES against the estimate's
 * {@code appliedPackageCode} string (never an {@code OfferPackageEntity}). Construction lines,
 * Placeholders, and the no-applied-package case always resolve to {@code false} / {@code null}.
 *
 * <p>The four catalog DAOs are mocked; the test drives the real {@code assemble} over a hand-built
 * single-cell estimate graph and asserts the per-line divergence flags/name across the five cases.
 */
@DisplayName("EstimateMatrixAssembler — chosen-product package divergence surface")
@Tag("Feature: FOR-05-07-offer-approval, estimate matrix package-divergence surface")
class EstimateMatrixAssemblerPackageDivergenceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ROOM_ID = 7L;
    private static final Long WORK_ID = 3L;
    private static final String APPLIED_CODE = "STD";

    private final RoomDao roomDao = mock(RoomDao.class);
    private final WorkItemDao workItemDao = mock(WorkItemDao.class);
    private final WorkVolumeFormulaDao workVolumeFormulaDao = mock(WorkVolumeFormulaDao.class);
    private final WorkMaterialConsumptionDao workMaterialConsumptionDao = mock(WorkMaterialConsumptionDao.class);

    private final EstimateMatrixAssembler assembler = new EstimateMatrixAssembler(
            roomDao, workItemDao, workVolumeFormulaDao, workMaterialConsumptionDao);

    private RoomEntity room;
    private WorkItemEntity work;

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void wireCatalog() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        room = room();
        work = work();
        when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(room));
        when(workItemDao.findAll()).thenReturn(List.of(work));
        when(workVolumeFormulaDao.findAllWithWorkItem()).thenReturn(List.of());
        lenient().when(workMaterialConsumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("chosen finishing product NOT in the applied package → differs=true + lowest-code package name")
    void finishingChosenNotInAppliedPackageDiffers() {
        wireCatalog();
        OfferPackageEntity premium = offerPackage("PRM", "Premium RU", "Premium PL");
        OfferPackageEntity gold = offerPackage("GLD", "Gold RU", "Gold PL");
        // Chosen product belongs to PRM + GLD, neither is the applied STD → differs; the representative
        // name is the LOWEST package code's localized (PL) name, i.e. GLD → "Gold PL".
        EstimateLineRoomMaterialEntity line = finishingLine("Farba", "Dulux", Set.of(premium, gold));

        EstimateMatrixDto matrix = assembleWith(line, APPLIED_CODE);
        MaterialLineDto dto = onlyFinishingLine(matrix);

        assertThat(dto.chosenPackageDiffersFromApplied()).isTrue();
        assertThat(dto.chosenProductPackageName()).isEqualTo("Gold PL");
    }

    @Test
    @DisplayName("chosen finishing product IN the applied package → differs=false + null name")
    void finishingChosenInAppliedPackageDoesNotDiffer() {
        wireCatalog();
        OfferPackageEntity standard = offerPackage("STD", "Standard RU", "Standard PL");
        EstimateLineRoomMaterialEntity line = finishingLine("Farba", "Dulux", Set.of(standard));

        EstimateMatrixDto matrix = assembleWith(line, APPLIED_CODE);
        MaterialLineDto dto = onlyFinishingLine(matrix);

        assertThat(dto.chosenPackageDiffersFromApplied()).isFalse();
        assertThat(dto.chosenProductPackageName()).isNull();
    }

    @Test
    @DisplayName("placeholder finishing line (no chosen product) → differs=false + null name")
    void placeholderFinishingLineNeverDiffers() {
        wireCatalog();
        EstimateLineRoomMaterialEntity line = finishingLine("Tynk", /* no chosen product */ null, Set.of());

        EstimateMatrixDto matrix = assembleWith(line, APPLIED_CODE);
        MaterialLineDto dto = onlyFinishingLine(matrix);

        assertThat(dto.isConcrete()).isFalse();
        assertThat(dto.chosenPackageDiffersFromApplied()).isFalse();
        assertThat(dto.chosenProductPackageName()).isNull();
    }

    @Test
    @DisplayName("construction line → differs=false + null name even with a chosen product")
    void constructionLineNeverDiffers() {
        wireCatalog();
        EstimateLineRoomMaterialEntity line = constructionLine("Klej", "Ceresit");

        EstimateMatrixDto matrix = assembleWith(line, APPLIED_CODE);
        MaterialLineDto dto = onlyLine(matrix, ConsumptionBranch.construction);

        assertThat(dto.chosenPackageDiffersFromApplied()).isFalse();
        assertThat(dto.chosenProductPackageName()).isNull();
    }

    @Test
    @DisplayName("no applied package code → differs=false + null name even when the product has packages")
    void noAppliedPackageNeverDiffers() {
        wireCatalog();
        OfferPackageEntity premium = offerPackage("PRM", "Premium RU", "Premium PL");
        EstimateLineRoomMaterialEntity line = finishingLine("Farba", "Dulux", Set.of(premium));

        EstimateMatrixDto matrix = assembleWith(line, /* appliedPackageCode */ null);
        MaterialLineDto dto = onlyFinishingLine(matrix);

        assertThat(dto.chosenPackageDiffersFromApplied()).isFalse();
        assertThat(dto.chosenProductPackageName()).isNull();
    }

    // --- assembly plumbing --------------------------------------------------------------------

    /** Builds a single estimate line / room-qty / material graph and runs the real assembler. */
    private EstimateMatrixDto assembleWith(EstimateLineRoomMaterialEntity material, String appliedPackageCode) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(1L);
        estimate.setAppliedPackageCode(appliedPackageCode);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(10L);
        line.setWorkItem(work);
        line.setUnitPrice(BigDecimal.ZERO);

        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
        roomQty.setId(20L);
        roomQty.setLine(line);
        roomQty.setRoom(room);
        roomQty.setQuantity(BigDecimal.ONE);
        roomQty.getMaterials().add(material);
        material.setRoomQty(roomQty);

        line.getRoomQtys().add(roomQty);
        estimate.getLines().add(line);

        return assembler.assemble(estimate, PROJECT_ID, false);
    }

    private static MaterialLineDto onlyFinishingLine(EstimateMatrixDto matrix) {
        return onlyLine(matrix, ConsumptionBranch.finishing);
    }

    private static MaterialLineDto onlyLine(EstimateMatrixDto matrix, ConsumptionBranch branch) {
        List<MaterialLineDto> lines = matrix.groups().stream()
                .flatMap(g -> g.rows().stream())
                .flatMap(r -> r.cells().stream())
                .flatMap(c -> c.materials().stream())
                .filter(m -> m.branch() == branch)
                .toList();
        assertThat(lines).as("exactly one %s material line", branch).hasSize(1);
        return lines.get(0);
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static RoomEntity room() {
        RoomEntity r = new RoomEntity();
        r.setId(ROOM_ID);
        r.setLabel("room-1");
        return r;
    }

    private static WorkItemEntity work() {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("m2");
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(100L);
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        WorkItemEntity w = new WorkItemEntity();
        w.setId(WORK_ID);
        w.setNameRU("Работа");
        w.setNamePL("Praca");
        w.setUnit(unit);
        w.setWorkCategory(category);
        return w;
    }

    private static OfferPackageEntity offerPackage(String code, String nameRU, String namePL) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(code);
        pkg.setNameRU(nameRU);
        pkg.setNamePL(namePL);
        return pkg;
    }

    /**
     * A FINISHING material line. When {@code chosenProductName} is non-null the line is "chosen" (a
     * concrete finishing product with that localized name belonging to {@code packages}); when null
     * it is an unchosen Placeholder.
     */
    private static EstimateLineRoomMaterialEntity finishingLine(
            String typeNamePL, String chosenProductName, Set<OfferPackageEntity> packages) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setId(500L);
        type.setNameRU(typeNamePL + " RU");
        type.setNamePL(typeNamePL);

        EstimateLineRoomMaterialEntity line = new EstimateLineRoomMaterialEntity();
        line.setId(301L);
        line.setBranch(ConsumptionBranch.finishing);
        line.setFinishingType(type);
        line.setNormQty(BigDecimal.ONE);
        line.setRangeMin(new BigDecimal("1.00"));
        line.setRangeMax(new BigDecimal("2.00"));

        if (chosenProductName != null) {
            MaterialEntity material = new MaterialEntity();
            material.setNameRU(chosenProductName + " RU");
            material.setNamePL(chosenProductName);
            FinishingMaterialEntity concrete = new FinishingMaterialEntity();
            concrete.setId(9001L);
            concrete.setMaterial(material);
            concrete.setPackages(packages);
            line.setConcreteFinishingMaterial(concrete);
            line.setConcreteNet(new BigDecimal("1.50"));
        }
        return line;
    }

    /** A CONSTRUCTION material line with a chosen concrete product (used to assert it never diverges). */
    private static EstimateLineRoomMaterialEntity constructionLine(String typeNamePL, String chosenProductName) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setId(600L);
        type.setNameRU(typeNamePL + " RU");
        type.setNamePL(typeNamePL);

        ConstructionMaterialEntity concrete = new ConstructionMaterialEntity();
        concrete.setId(8001L);
        concrete.setNameRU(chosenProductName + " RU");
        concrete.setNamePL(chosenProductName);

        EstimateLineRoomMaterialEntity line = new EstimateLineRoomMaterialEntity();
        line.setId(302L);
        line.setBranch(ConsumptionBranch.construction);
        line.setConstructionType(type);
        line.setNormQty(BigDecimal.ONE);
        line.setRangeMin(new BigDecimal("1.00"));
        line.setRangeMax(new BigDecimal("2.00"));
        line.setConcreteConstructionMaterial(concrete);
        line.setConcreteNet(new BigDecimal("1.50"));
        return line;
    }
}
