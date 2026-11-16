package com.foremen.service.clientpayment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.ClientPaymentDto;
import com.foremen.controller.model.ClientPaymentView;
import com.foremen.controller.model.client.ClientDtoLeakGuard;
import com.foremen.controller.model.client.ClientPaymentClientDto;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.payment.ScheduleStatus;
import com.foremen.service.payment.TrancheStatus;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-06 task 2.3 (R1.3, R1.4, R1.7): read assembler, per-role visibility, client allow-list.
 *
 * <p>Validates: Requirements 1.3, 1.4, 1.7
 */
class ClientPaymentAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 10);
    private static final String COMPANY_IBAN = "PL61109010140000071219812874";
    private static final String SNAPSHOT_IBAN = "DE89370400440532013000";

    private static ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setId(7L);
        return p;
    }

    private static ClientPaymentEntity works(ScheduleStatus scheduleStatus, TrancheStatus status) {
        PaymentScheduleEntity s = new PaymentScheduleEntity();
        s.setId(3L);
        s.setProject(project());
        s.setStatus(scheduleStatus);
        PaymentTrancheEntity t = new PaymentTrancheEntity();
        t.setId(11L);
        t.setSeqNo(2);
        t.setSchedule(s);
        t.setStatus(status);
        t.setDueDate(TODAY.plusDays(5));
        t.setAmountGross(new BigDecimal("1000.00"));
        t.setPaidGross(new BigDecimal("250.00"));
        ClientPaymentEntity p = t.getPayment();
        p.setId(100L);
        p.setNote("INTERNAL_NOTE");
        return p;
    }

    private static ClientPaymentEntity materials(ProformaStatus status) {
        ClientProformaEntity pf = new ClientProformaEntity();
        pf.setId(21L);
        pf.setProject(project());
        pf.setStatus(status);
        pf.setNumber("PF/1");
        pf.setDueDate(TODAY.minusDays(1));
        pf.setTotalGross(new BigDecimal("500.00"));
        pf.setPaidGross(new BigDecimal("100.00"));
        pf.setCancelReason("INTERNAL_REASON");
        ClientPaymentEntity p = pf.getPayment();
        p.setId(200L);
        p.setBankAccountSnapshot(SNAPSHOT_IBAN);
        return p;
    }

    private static ClientProformaLineEntity line() {
        ClientProformaLineEntity l = new ClientProformaLineEntity();
        l.setLineNo(1);
        l.setDescription("Tiles");
        l.setQuantity(new BigDecimal("2"));
        l.setUnitName("m2");
        l.setNet(new BigDecimal("100.00"));
        l.setGross(new BigDecimal("123.00"));
        return l;
    }

    private static boolean visible(ClientPaymentEntity p, ClientPaymentVisibility v) {
        return ClientPaymentAssembler.isVisible(p, v);
    }

    @Test
    void roleKindRule() {
        ClientPaymentEntity w = works(ScheduleStatus.PUBLISHED, TrancheStatus.PENDING);
        ClientPaymentEntity m = materials(ProformaStatus.ISSUED);
        assertThat(visible(w, ClientPaymentVisibility.forRole("BUYER"))).isFalse();
        assertThat(visible(m, ClientPaymentVisibility.forRole("BUYER"))).isTrue();
        assertThat(visible(w, ClientPaymentVisibility.forRole("FOREMAN"))).isTrue();
        assertThat(visible(m, ClientPaymentVisibility.forRole("FOREMAN"))).isFalse();
        assertThat(visible(w, ClientPaymentVisibility.forRole("WORKER"))).isFalse();
        for (String r : List.of("ADMIN", "MANAGER", "FINANCIER")) {
            assertThat(visible(w, ClientPaymentVisibility.forRole(r))).isTrue();
            assertThat(visible(m, ClientPaymentVisibility.forRole(r))).isTrue();
        }
    }

    @Test
    void clientSeesOnlyPublishedWorksAndNonDraftMaterials() {
        ClientPaymentVisibility c = ClientPaymentVisibility.CLIENT;
        assertThat(visible(works(ScheduleStatus.PUBLISHED, TrancheStatus.PENDING), c)).isTrue();
        assertThat(visible(works(ScheduleStatus.DRAFT, TrancheStatus.PENDING), c)).isFalse();
        assertThat(visible(materials(ProformaStatus.DRAFT), c)).isFalse();
        assertThat(visible(materials(ProformaStatus.CANCELLED), c)).isFalse();
        assertThat(visible(materials(ProformaStatus.ISSUED), c)).isTrue();
        assertThat(visible(materials(ProformaStatus.PAID), c)).isTrue();
    }

    @Test
    void staffWorksDtoHasWorksBlockAndMappedStatus() {
        ClientPaymentView v = ClientPaymentAssembler.build(works(ScheduleStatus.PUBLISHED, TrancheStatus.PENDING),
                ClientPaymentVisibility.ALL, TODAY, List.of(), COMPANY_IBAN);
        ClientPaymentDto dto = (ClientPaymentDto) v;
        assertThat(dto.status()).isEqualTo(DisplayStatus.PLANNED);
        assertThat(dto.storedStatus()).isEqualTo("PENDING");
        assertThat(dto.proforma()).isNull();
        assertThat(dto.works().scheduleId()).isEqualTo(3L);
        assertThat(dto.works().seqNo()).isEqualTo(2);
        assertThat(dto.remainingGross()).isEqualByComparingTo("750.00");
        assertThat(dto.effectiveBankAccount()).isEqualTo(COMPANY_IBAN);
        assertThat(dto.note()).isEqualTo("INTERNAL_NOTE");
    }

    @Test
    void staffMaterialsDtoExposesSnapshotLinesAndOverdueDisplay() {
        ClientPaymentDto dto = (ClientPaymentDto) ClientPaymentAssembler.build(materials(ProformaStatus.ISSUED),
                ClientPaymentVisibility.MATERIALS_ONLY, TODAY, List.of(line()), COMPANY_IBAN);
        assertThat(dto.status()).isEqualTo(DisplayStatus.OVERDUE);
        assertThat(dto.overdue()).isTrue();
        assertThat(dto.works()).isNull();
        assertThat(dto.proforma().bankAccountSnapshot()).isEqualTo(SNAPSHOT_IBAN);
        assertThat(dto.proforma().lines()).hasSize(1);
        assertThat(dto.proforma().number()).isEqualTo("PF/1");
    }

    @Test
    void clientDtoPrefersSnapshotAndCarriesNoInternalData() {
        ClientPaymentClientDto dto = (ClientPaymentClientDto) ClientPaymentAssembler.build(
                materials(ProformaStatus.ISSUED), ClientPaymentVisibility.CLIENT, TODAY, List.of(line()),
                COMPANY_IBAN);
        assertThat(dto.payeeBankAccount()).isEqualTo(SNAPSHOT_IBAN);
        assertThat(dto.lines()).hasSize(1);
        assertThat(dto.toString()).doesNotContain("INTERNAL_REASON");

        ClientPaymentClientDto w = (ClientPaymentClientDto) ClientPaymentAssembler.build(
                works(ScheduleStatus.PUBLISHED, TrancheStatus.DUE), ClientPaymentVisibility.CLIENT, TODAY,
                List.of(), COMPANY_IBAN);
        assertThat(w.payeeBankAccount()).isEqualTo(COMPANY_IBAN);
        assertThat(w.status()).isEqualTo(DisplayStatus.DUE);
        assertThat(w.toString()).doesNotContain("INTERNAL_NOTE");
    }

    @Test
    void clientDtoTreeHasOnlyPayeeBankAccountAsBankData() {
        assertThat(ClientDtoLeakGuard.violations(ClientPaymentClientDto.class)).isEmpty();
        assertThat(ClientPaymentClientDto.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .filteredOn(n -> n.contains("account") || n.contains("iban") || n.contains("note")
                        || n.contains("snapshot") || n.contains("stored"))
                .containsExactly("payeebankaccount");
    }
}
