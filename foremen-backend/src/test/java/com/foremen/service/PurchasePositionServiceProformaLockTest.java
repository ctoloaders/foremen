package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.DeliveryCategoryDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PurchasePositionServiceExtendedModel;
import com.foremen.service.model.mapper.PurchasePositionServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.proforma.ProformaStatus;

import jakarta.persistence.EntityManager;

/** FOR-11-03 task 3.2 (R4.6): positions on an issued proforma keep quantity, sale price, VAT and material. */
class PurchasePositionServiceProformaLockTest {

    private static final String LOCKED = "error.proforma.position.locked";

    private ClientProformaLineDao lineDao;
    private PurchasePositionVariantDao variantDao;
    private PurchasePositionService service;
    private PurchasePositionEntity position;
    private FinishingMaterialDao materialDao;

    @BeforeEach
    void setUp() {
        lineDao = mock(ClientProformaLineDao.class);
        variantDao = mock(PurchasePositionVariantDao.class);
        when(variantDao.countByPositionIds(anyCollection())).thenReturn(List.of());
        materialDao = mock(FinishingMaterialDao.class);
        EntityManager em = mock(EntityManager.class);
        VatRateDao vatRateDao = mock(VatRateDao.class);
        when(vatRateDao.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(new VatRateEntity()));
        service = new PurchasePositionService(mock(PurchasePositionDao.class), new PurchasePositionServiceMapper(em),
                mock(AuditLogDao.class), em, mock(ProcurementProjectAccess.class),
                mock(PositionAudienceResolver.class), mock(ProjectDao.class), materialDao,
                mock(MeasurementUnitDao.class), mock(DeliveryCategoryDao.class), vatRateDao,
                variantDao, mock(WorkCategoryDao.class), lineDao);

        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        FinishingMaterialEntity material = new FinishingMaterialEntity();
        material.setId(7L);
        VatRateEntity vat = new VatRateEntity();
        vat.setId(3L);
        position = new PurchasePositionEntity();
        position.setId(42L);
        position.setProject(project);
        position.setSourcingPath(SourcingPath.AGREED);
        position.setStatus(PositionStatus.IN_PROFORMA);
        position.setQuantity(new BigDecimal("2.000"));
        position.setSalePrice(new BigDecimal("10.00"));
        position.setSaleVatRate(vat);
        position.setPlannedMaterial(material);
    }

    @Test
    void issuedProformaLocksSalePriceVatAndActualMaterial() {
        onProforma(ProformaStatus.ISSUED);

        assertLocked(m -> m.setSalePrice(new BigDecimal("11.00")));
        assertLocked(m -> m.setSaleVatRateId(4L));
        assertLocked(m -> m.setActualMaterialId(8L));
        assertLocked(m -> m.setQuantity(new BigDecimal("3")));
        assertThatThrownBy(() -> service.setPropertiesToNull(42L, Set.of("salePrice")))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(LOCKED));
    }

    @Test
    void unchangedValuesAndOtherFieldsPassOnAnIssuedProforma() {
        onProforma(ProformaStatus.PARTIALLY_PAID);

        PurchasePositionServiceExtendedModel same = new PurchasePositionServiceExtendedModel();
        same.setSalePrice(new BigDecimal("10.0"));
        same.setSaleVatRateId(3L);
        same.setNotes("note");
        assertThatCode(() -> service.validateUpdate(position, same)).doesNotThrowAnyException();
    }

    @Test
    void pendingPlannedPositionMayChooseItsFirstMaterial() {
        // FOR-11-06 task 4.2 (R5.3, R5.7): estimate-derived position without material, still PLANNED
        position.setStatus(PositionStatus.PLANNED);
        position.setPlannedMaterial(null);
        FinishingMaterialEntity chosen = new FinishingMaterialEntity();
        chosen.setId(9L);
        when(materialDao.findById(9L)).thenReturn(java.util.Optional.of(chosen));

        PurchasePositionServiceExtendedModel m = new PurchasePositionServiceExtendedModel();
        m.setPlannedMaterialId(9L);
        assertThatCode(() -> service.validateUpdate(position, m)).doesNotThrowAnyException();

        position.setStatus(PositionStatus.ORDERED);
        assertThatThrownBy(() -> service.validateUpdate(position, m)).isInstanceOf(ForemenApiException.class);
    }

    @Test
    void draftProformaLocksNothing() {
        position.setStatus(PositionStatus.ACCEPTED);
        onProforma(ProformaStatus.DRAFT);

        PurchasePositionServiceExtendedModel m = new PurchasePositionServiceExtendedModel();
        m.setSaleVatRateId(4L);
        assertThatCode(() -> service.validateUpdate(position, m)).doesNotThrowAnyException();
    }

    private void onProforma(ProformaStatus status) {
        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setStatus(status);
        ClientProformaLineEntity line = new ClientProformaLineEntity();
        line.setProforma(proforma);
        line.setPosition(position);
        when(lineDao.findActiveByPositionId(42L)).thenReturn(List.of(line));
    }

    private void assertLocked(java.util.function.Consumer<PurchasePositionServiceExtendedModel> change) {
        PurchasePositionServiceExtendedModel m = new PurchasePositionServiceExtendedModel();
        change.accept(m);
        assertThatThrownBy(() -> service.validateUpdate(position, m))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessageCode()).isEqualTo(LOCKED);
                });
    }
}
