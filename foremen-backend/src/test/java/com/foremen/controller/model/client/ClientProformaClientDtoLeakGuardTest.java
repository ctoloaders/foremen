package com.foremen.controller.model.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.procurement.proforma.ClientProformaClientAssembler;
import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-03 task 3.5 (R6.3, R6.4): reflection leak guard over the client proforma DTOs and a sentinel
 * JSON test proving purchase-side and internal values never reach a client response.
 *
 * <p>Validates: Requirements 6.3, 6.4
 */
class ClientProformaClientDtoLeakGuardTest {

    private static final BigDecimal SENTINEL_PURCHASE = new BigDecimal("987654.32");
    private static final String SENTINEL_NOTE = "SENTINEL_INTERNAL_NOTE_xyz";
    private static final String SENTINEL_REASON = "SENTINEL_CANCEL_REASON_xyz";

    /** R6.4 Internal_Field list (lower-case prefixes / names). */
    private static final List<String> INTERNAL_PREFIXES = List.of("purchase", "seller", "internal", "margin",
            "deliverycost", "orderby", "needby", "request", "externalorder");

    @Test
    void clientDtoTreeHasNoInternalFieldNames() {
        assertThat(ClientDtoLeakGuard.violations(ClientProformaClientDto.class)).isEmpty();
        for (Class<?> type : List.of(ClientProformaClientDto.class, ClientProformaLineClientDto.class)) {
            for (RecordComponent c : type.getRecordComponents()) {
                String name = c.getName().toLowerCase(Locale.ROOT);
                assertThat(INTERNAL_PREFIXES).as(type.getSimpleName() + "." + c.getName())
                        .noneMatch(name::startsWith);
                assertThat(name).as(type.getSimpleName() + "." + c.getName()).isNotEqualTo("positionstatus");
            }
        }
    }

    /** FOR-11-03 15.1 (R23.3, R6.3): the staff-only payments list and bank data never reach the client DTO. */
    @Test
    void clientDtoCarriesNoPaymentsOrBankData() {
        for (Class<?> type : List.of(ClientProformaClientDto.class, ClientProformaLineClientDto.class)) {
            assertThat(type.getRecordComponents()).extracting(c -> c.getName().toLowerCase(Locale.ROOT))
                    .noneMatch(n -> n.contains("payments") || n.contains("iban") || n.contains("counterparty")
                            || n.contains("account") || n.contains("ledger") || n.contains("linkid"));
        }
    }

    @Test
    void lineDtoCarriesNoPositionIdentifier() {
        assertThat(ClientProformaLineClientDto.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("position"))
                .doesNotContain("id");
    }

    @Test
    void sentinelPurchaseAndInternalValuesNeverAppearInClientJson() throws Exception {
        PurchasePositionEntity position = new PurchasePositionEntity();
        position.setPlannedPurchasePrice(SENTINEL_PURCHASE);
        position.setActualPurchasePrice(SENTINEL_PURCHASE);
        position.setDeliveryCostShare(SENTINEL_PURCHASE);
        position.setNotes(SENTINEL_NOTE);

        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setId(5L);
        proforma.setNumber("PF/2026/000123");
        proforma.setStatus(ProformaStatus.PARTIALLY_PAID);
        proforma.setIssueDate(LocalDate.of(2026, 1, 10));
        proforma.setDueDate(LocalDate.of(2026, 1, 17));
        proforma.setTotalNet(new BigDecimal("100.00"));
        proforma.setTotalVat(new BigDecimal("8.00"));
        proforma.setTotalGross(new BigDecimal("108.00"));
        proforma.setPaidGross(new BigDecimal("40.00"));
        proforma.setNote(SENTINEL_NOTE);
        proforma.setCancelReason(SENTINEL_REASON);
        proforma.setPdfObjectKey("client-proformas/SENTINEL_KEY.pdf");

        ClientProformaLineEntity line = new ClientProformaLineEntity();
        line.setPosition(position);
        line.setLineNo(1);
        line.setDescription("Tile A");
        line.setSku("SKU-1");
        line.setQuantity(new BigDecimal("2.000"));
        line.setUnitName("szt");
        line.setUnitSaleNet(new BigDecimal("50.00"));
        line.setVatRatePercent(new BigDecimal("8.00"));
        line.setNet(new BigDecimal("100.00"));
        line.setVat(new BigDecimal("8.00"));
        line.setGross(new BigDecimal("108.00"));

        ClientProformaClientDto dto = new ClientProformaClientAssembler()
                .assemble(proforma, List.of(line), LocalDate.of(2026, 1, 20));

        assertThat(dto.overdue()).isTrue();
        assertThat(dto.remainingGross()).isEqualByComparingTo("68.00");
        assertThat(dto.paymentTitle()).isEqualTo("Proforma PF/2026/000123");
        assertThat(dto.lines()).hasSize(1);

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(dto);
        assertThat(json).doesNotContain("987654").doesNotContain(SENTINEL_NOTE)
                .doesNotContain(SENTINEL_REASON).doesNotContain("SENTINEL_KEY");
        String lower = json.toLowerCase(Locale.ROOT);
        for (String token : ClientDtoLeakGuard.DENY_TOKENS) {
            assertThat(lower).doesNotContain("\"" + token);
        }
        assertThat(lower).doesNotContain("\"positionstatus").doesNotContain("\"externalorder")
                .doesNotContain("\"pdfobjectkey").doesNotContain("\"note\"");
    }

    @Test
    void cancelledProformaHasNothingRemaining() {
        ClientProformaEntity proforma = new ClientProformaEntity();
        proforma.setStatus(ProformaStatus.CANCELLED);
        proforma.setTotalGross(new BigDecimal("108.00"));
        proforma.setPaidGross(BigDecimal.ZERO);
        ClientProformaClientDto dto = new ClientProformaClientAssembler()
                .assemble(proforma, List.of(), LocalDate.of(2026, 1, 20));
        assertThat(dto.remainingGross()).isEqualByComparingTo("0");
        assertThat(dto.overdue()).isFalse();
    }
}
