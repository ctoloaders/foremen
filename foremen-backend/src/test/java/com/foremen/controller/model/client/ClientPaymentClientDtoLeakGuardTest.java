package com.foremen.controller.model.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foremen.controller.model.ClientPaymentLineSummaryDto;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.PaymentTrancheEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.EffectiveBankAccountResolver;
import com.foremen.service.clientpayment.ClientPaymentAssembler;
import com.foremen.service.clientpayment.ClientPaymentVisibility;
import com.foremen.service.payment.ScheduleStatus;
import com.foremen.service.payment.TrancheStatus;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-06 task 2.8 (R1.6, R3.3, design 4 "Leak guard"): replaces
 * {@code ClientProformaClientDtoLeakGuardTest.clientDtoCarriesNoPaymentsOrBankData} for the unified client DTO.
 * The only bank or payment-internal datum a client may receive is {@code payeeBankAccount}; every other bank,
 * ledger, note, stored-status and id component is rejected by reflection, and sentinel values never reach the
 * client JSON.
 *
 * <p>Validates: Requirements 1.6, 3.3
 */
class ClientPaymentClientDtoLeakGuardTest {

    private static final String SENTINEL_NOTE = "SENTINEL_NOTE_xyz";
    private static final String SENTINEL_REASON = "SENTINEL_CANCEL_REASON_xyz";
    private static final String SENTINEL_PDF_KEY = "SENTINEL_PDF_KEY_xyz";
    private static final String SENTINEL_PURCHASE = "987654.32";
    private static final String SNAPSHOT_IBAN = "DE89370400440532013000";
    private static final String COMPANY_IBAN = "PL61109010140000071219812874";
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 10);

    /** Component names (lower-case) that mention bank or payment-internal data; only the allowed one may match. */
    private static boolean bankOrInternal(String lower) {
        return lower.contains("account") || lower.contains("iban") || lower.contains("snapshot")
                || lower.contains("bank") || lower.contains("counterparty") || lower.contains("ledger")
                || lower.contains("linkid") || lower.contains("transaction") || lower.contains("allocation")
                || lower.contains("note") || lower.contains("stored") || lower.contains("reason")
                || lower.contains("pdf") || lower.contains("project") || lower.contains("schedule")
                || lower.contains("tranche") || lower.contains("proformaid") || lower.contains("audit")
                || lower.contains("createdby") || lower.contains("issuedby");
    }

    @Test
    void clientDtoTreeHasNoInternalFieldNames() {
        assertThat(ClientDtoLeakGuard.violations(ClientPaymentClientDto.class)).isEmpty();
    }

    @Test
    void onlyPayeeBankAccountIsBankOrInternalData() {
        for (Class<?> type : List.of(ClientPaymentClientDto.class, ClientPaymentLineSummaryDto.class)) {
            assertThat(type.getRecordComponents()).extracting(RecordComponent::getName)
                    .map(n -> n.toLowerCase(Locale.ROOT))
                    .filteredOn(ClientPaymentClientDtoLeakGuardTest::bankOrInternal)
                    .as(type.getSimpleName())
                    .isSubsetOf("payeebankaccount");
        }
        assertThat(ClientPaymentClientDto.class.getRecordComponents()).extracting(RecordComponent::getName)
                .contains("payeeBankAccount");
    }

    @Test
    void lineDtoCarriesNoPositionIdentifier() {
        assertThat(ClientPaymentLineSummaryDto.class.getRecordComponents()).extracting(RecordComponent::getName)
                .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("position"))
                .doesNotContain("id");
    }

    @Test
    void sentinelValuesNeverAppearInMaterialsClientJson() throws Exception {
        String json = json(assemble(materials(), List.of(line())));
        assertNoSentinels(json);
        // the issue snapshot wins over the effective account, and it is the only bank datum
        assertThat(json).contains(SNAPSHOT_IBAN).doesNotContain(COMPANY_IBAN);
    }

    @Test
    void sentinelValuesNeverAppearInWorksClientJson() throws Exception {
        String json = json(assemble(works(), List.of()));
        assertNoSentinels(json);
        assertThat(json).contains(COMPANY_IBAN);
    }

    private static void assertNoSentinels(String json) {
        assertThat(json).doesNotContain(SENTINEL_NOTE).doesNotContain(SENTINEL_REASON)
                .doesNotContain(SENTINEL_PDF_KEY).doesNotContain("987654");
        String lower = json.toLowerCase(Locale.ROOT);
        for (String token : ClientDtoLeakGuard.DENY_TOKENS) {
            assertThat(lower).doesNotContain("\"" + token);
        }
        assertThat(lower).doesNotContain("\"note\"").doesNotContain("\"storedstatus\"")
                .doesNotContain("\"projectid\"").doesNotContain("\"bankaccountsnapshot\"")
                .doesNotContain("\"effectivebankaccount\"").doesNotContain("\"cancelreason\"")
                .doesNotContain("\"pdfobjectkey\"").doesNotContain("\"positionid\"");
    }

    private static Object assemble(ClientPaymentEntity payment, List<ClientProformaLineEntity> lines) {
        EffectiveBankAccountResolver resolver = mock(EffectiveBankAccountResolver.class);
        when(resolver.forProject(any(ProjectEntity.class))).thenReturn(COMPANY_IBAN);
        return new ClientPaymentAssembler(resolver)
                .assemble(payment, ClientPaymentVisibility.CLIENT, TODAY, lines).orElseThrow();
    }

    private static String json(Object dto) throws Exception {
        return new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(dto);
    }

    private static ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setId(7L);
        return p;
    }

    private static ClientPaymentEntity materials() {
        ClientProformaEntity pf = new ClientProformaEntity();
        pf.setId(21L);
        pf.setProject(project());
        pf.setStatus(ProformaStatus.ISSUED);
        pf.setNumber("PF/2026/000123");
        pf.setIssueDate(TODAY.minusDays(3));
        pf.setDueDate(TODAY.minusDays(1));
        pf.setTotalGross(new BigDecimal("500.00"));
        pf.setPaidGross(new BigDecimal("100.00"));
        pf.setNote(SENTINEL_NOTE);
        pf.setCancelReason(SENTINEL_REASON);
        pf.setPdfObjectKey(SENTINEL_PDF_KEY);
        ClientPaymentEntity p = pf.getPayment();
        p.setId(200L);
        p.setBankAccountSnapshot(SNAPSHOT_IBAN);
        return p;
    }

    private static ClientPaymentEntity works() {
        PaymentScheduleEntity s = new PaymentScheduleEntity();
        s.setId(3L);
        s.setProject(project());
        s.setStatus(ScheduleStatus.PUBLISHED);
        PaymentTrancheEntity t = new PaymentTrancheEntity();
        t.setId(11L);
        t.setSeqNo(1);
        t.setSchedule(s);
        t.setStatus(TrancheStatus.DUE);
        t.setDueDate(TODAY.plusDays(5));
        t.setAmountGross(new BigDecimal("1000.00"));
        t.setPaidGross(BigDecimal.ZERO);
        ClientPaymentEntity p = t.getPayment();
        p.setId(100L);
        p.setNote(SENTINEL_NOTE);
        return p;
    }

    private static ClientProformaLineEntity line() {
        PurchasePositionEntity position = new PurchasePositionEntity();
        position.setPlannedPurchasePrice(new BigDecimal(SENTINEL_PURCHASE));
        position.setActualPurchasePrice(new BigDecimal(SENTINEL_PURCHASE));
        position.setNotes(SENTINEL_NOTE);
        ClientProformaLineEntity l = new ClientProformaLineEntity();
        l.setPosition(position);
        l.setLineNo(1);
        l.setDescription("Tiles");
        l.setSku("SKU-1");
        l.setQuantity(new BigDecimal("2"));
        l.setUnitName("m2");
        l.setNet(new BigDecimal("100.00"));
        l.setGross(new BigDecimal("123.00"));
        return l;
    }
}
