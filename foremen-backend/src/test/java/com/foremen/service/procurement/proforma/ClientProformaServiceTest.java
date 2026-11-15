package com.foremen.service.procurement.proforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/** FOR-11-03 task 3.2: draft create / update / delete and position validation (R3.1 - R3.7). */
class ClientProformaServiceTest {

    private static final long PROJECT_ID = 10L;

    private ClientProformaDao dao;
    private ClientProformaLineDao lineDao;
    private ProjectDao projectDao;
    private PurchasePositionDao positionDao;
    private EntityManager entityManager;
    private ClientProformaService service;
    private ProjectEntity project;
    private final List<PurchasePositionEntity> positions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dao = mock(ClientProformaDao.class);
        lineDao = mock(ClientProformaLineDao.class);
        projectDao = mock(ProjectDao.class);
        positionDao = mock(PurchasePositionDao.class);
        entityManager = mock(EntityManager.class);
        service = new ClientProformaService(dao, lineDao, new ClientProformaServiceMapper(entityManager, lineDao,
                mock(com.foremen.dao.BankTransactionLinkDao.class)),
                mock(AuditLogDao.class), entityManager, mock(ProcurementProjectAccess.class), projectDao,
                positionDao, new ProformaLineSnapshotBuilder());

        project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(entityManager.getReference(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
        when(positionDao.findAllByIdIn(anyCollection())).thenAnswer(inv -> positions);
        when(lineDao.findActivePositionIds(anyCollection())).thenReturn(List.of());
        when(dao.save(any())).thenAnswer(inv -> {
            ClientProformaEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(99L);
            }
            return e;
        });

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createBuildsDraftWithLinesAndTotalsAndLeavesPositionStatusAlone() {
        PurchasePositionEntity a = position(1L, "100.00", "2.000", "8");
        PurchasePositionEntity b = position(2L, "33.33", "3.000", "23");

        service.create(model(1L, 2L));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ClientProformaLineEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(lineDao).saveAll(captor.capture());
        List<ClientProformaLineEntity> lines = captor.getValue();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getLineNo()).isEqualTo(1);
        assertThat(lines.get(0).getDescription()).isEqualTo("Acme M-1");
        assertThat(lines.get(0).getGross()).isEqualByComparingTo("216.00");
        // 33.33 x 3 = 99.99 net; VAT 23 % = 23.00 (HALF_UP) -> 122.99
        assertThat(lines.get(1).getGross()).isEqualByComparingTo("122.99");

        org.mockito.ArgumentCaptor<ClientProformaEntity> saved =
                org.mockito.ArgumentCaptor.forClass(ClientProformaEntity.class);
        verify(dao, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        ClientProformaEntity draft = saved.getValue();
        assertThat(draft.getStatus()).isEqualTo(ProformaStatus.DRAFT);
        assertThat(draft.getTotalNet()).isEqualByComparingTo("299.99");
        assertThat(draft.getTotalGross()).isEqualByComparingTo("338.99");
        assertThat(a.getStatus()).isEqualTo(PositionStatus.ACCEPTED);
        assertThat(b.getStatus()).isEqualTo(PositionStatus.ACCEPTED);
    }

    @Test
    void rejectsEmptyDuplicateOrTooManyPositions() {
        assertCode(() -> service.create(model()), HttpStatus.BAD_REQUEST, ClientProformaService.POSITIONS_INVALID);
        assertCode(() -> service.create(model(1L, 1L)), HttpStatus.BAD_REQUEST,
                ClientProformaService.POSITIONS_INVALID);
        long[] many = LongStream.rangeClosed(1, ClientProformaService.MAX_POSITIONS + 1).toArray();
        assertCode(() -> service.create(model(many)), HttpStatus.BAD_REQUEST,
                ClientProformaService.POSITIONS_INVALID);
    }

    @Test
    void rejectsProjectNotInExecution() {
        position(1L, "10.00", "1", "8");
        project.setStatus(ProjectStatus.APPROVED);
        assertCode(() -> service.create(model(1L)), HttpStatus.CONFLICT, ClientProformaService.PROJECT_NOT_ACTIVE);
    }

    @Test
    void rejectsEachInvalidPositionWithItsKeyNamingTheFirstOffender() {
        position(1L, "10.00", "1", "8");
        PurchasePositionEntity bad = position(2L, "10.00", "1", "8");

        ProjectEntity other = new ProjectEntity();
        other.setId(11L);
        bad.setProject(other);
        ForemenApiException ex = assertCode(() -> service.create(model(1L, 2L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_PROJECT_MISMATCH);
        assertThat(ex.getMessageParams()).containsExactly(2L);

        bad.setProject(project);
        bad.setSourcingPath(SourcingPath.PACKAGE);
        assertCode(() -> service.create(model(1L, 2L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_NOT_AGREED);

        bad.setSourcingPath(SourcingPath.AGREED);
        bad.setStatus(PositionStatus.PROPOSED);
        assertCode(() -> service.create(model(1L, 2L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_NOT_ACCEPTED);

        bad.setStatus(PositionStatus.ACCEPTED);
        bad.setSaleVatRate(null);
        assertCode(() -> service.create(model(1L, 2L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_NO_SALE_PRICE);

        bad.setSaleVatRate(vat("8"));
        when(lineDao.findActivePositionIds(anyCollection())).thenReturn(List.of(2L));
        assertCode(() -> service.create(model(1L, 2L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_ALREADY_IN_PROFORMA);
        verify(lineDao, never()).saveAll(any());
    }

    @Test
    void uniqueIndexViolationOnClaimMapsToAlreadyInProforma() {
        position(1L, "10.00", "1", "8");
        when(lineDao.saveAll(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new ConstraintViolationException("duplicate key value violates unique constraint "
                        + "\"uk_proforma_lines_position_active\"", null, "uk_proforma_lines_position_active")));

        assertCode(() -> service.create(model(1L)), HttpStatus.CONFLICT,
                ClientProformaService.POSITION_ALREADY_IN_PROFORMA);
    }

    @Test
    void otherIntegrityViolationsAreNotMapped() {
        position(1L, "10.00", "1", "8");
        when(lineDao.saveAll(any())).thenThrow(new DataIntegrityViolationException("uk_something_else"));

        assertThatThrownBy(() -> service.create(model(1L))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateAndDeleteRequireDraft() {
        ClientProformaEntity issued = new ClientProformaEntity();
        issued.setId(5L);
        issued.setProject(project);
        issued.setStatus(ProformaStatus.ISSUED);
        when(dao.findById(5L)).thenReturn(Optional.of(issued));

        assertCode(() -> service.update(5L, model(1L)), HttpStatus.CONFLICT, ClientProformaService.NOT_DRAFT);
        assertCode(() -> service.deleteById(5L), HttpStatus.CONFLICT, ClientProformaService.NOT_DRAFT);
        verify(dao, never()).deleteById(any());
    }

    @Test
    void updateKeepsOwnPositionsClaimableAndReplacesLines() {
        PurchasePositionEntity kept = position(1L, "10.00", "1", "8");
        position(2L, "20.00", "1", "8");
        ClientProformaEntity draft = new ClientProformaEntity();
        draft.setId(5L);
        draft.setProject(project);
        when(dao.findById(5L)).thenReturn(Optional.of(draft));
        ClientProformaLineEntity own = new ClientProformaLineEntity();
        own.setPosition(kept);
        when(lineDao.findByProformaIdOrderByLineNo(5L)).thenReturn(List.of(own));
        when(lineDao.findActivePositionIds(anyCollection())).thenReturn(List.of(1L));

        service.update(5L, model(1L, 2L));

        verify(lineDao).deleteAll(List.of(own));
        assertThat(draft.getTotalGross()).isEqualByComparingTo("32.40");
    }

    @Test
    void positionClaimViolationDetectionWalksTheCauseChain() {
        assertThat(ClientProformaService.isPositionClaimViolation(new RuntimeException("x",
                new RuntimeException("violates \"uk_proforma_lines_position_active\"")))).isTrue();
        assertThat(ClientProformaService.isPositionClaimViolation(new RuntimeException("uk_other"))).isFalse();
    }

    // --- helpers ---

    private PurchasePositionEntity position(Long id, String salePrice, String qty, String vatPercent) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project);
        p.setSourcingPath(SourcingPath.AGREED);
        p.setStatus(PositionStatus.ACCEPTED);
        p.setSalePrice(new BigDecimal(salePrice));
        p.setQuantity(new BigDecimal(qty));
        p.setSaleVatRate(vat(vatPercent));
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setNamePL("szt.");
        unit.setNameRU("шт.");
        p.setUnit(unit);
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setId(100L + id);
        m.setModel("M-" + id);
        m.setSku("SKU-" + id);
        MaterialProducerEntity producer = new MaterialProducerEntity();
        producer.setNamePL("Acme");
        producer.setNameRU("Акме");
        m.setProducer(producer);
        p.setPlannedMaterial(m);
        positions.add(p);
        return p;
    }

    private static VatRateEntity vat(String percent) {
        VatRateEntity v = new VatRateEntity();
        v.setRate(new BigDecimal(percent));
        return v;
    }

    private static ClientProformaServiceExtendedModel model(long... ids) {
        ClientProformaServiceExtendedModel m = new ClientProformaServiceExtendedModel();
        m.setProjectId(PROJECT_ID);
        m.setPositionIds(LongStream.of(ids).boxed().toList());
        return m;
    }

    private static ForemenApiException assertCode(Runnable call, HttpStatus status, String code) {
        try {
            call.run();
        } catch (ForemenApiException ex) {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getMessageCode()).isEqualTo(code);
            return ex;
        }
        throw new AssertionError("expected " + code);
    }
}
