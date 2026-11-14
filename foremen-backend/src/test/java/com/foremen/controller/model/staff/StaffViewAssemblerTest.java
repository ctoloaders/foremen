package com.foremen.controller.model.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.StaffAction;
import com.foremen.service.procurement.approval.StaffApprovalData;
import com.foremen.service.procurement.approval.StaffOptionSource;
import com.foremen.service.procurement.approval.StaffPositionSource;
import com.foremen.service.procurement.approval.StaffRoundSource;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** FOR-11-02 task 4.3 - {@link StaffViewAssembler}. Validates Requirements 3.6, 4.5, 4.7. */
class StaffViewAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 6, 15);
    private final StaffViewAssembler assembler = new StaffViewAssembler();

    private static StaffPositionSource position() {
        return new StaffPositionSource(1L, 2, PositionStatus.PROPOSED, SourcingPath.AGREED,
                new BigDecimal("3.000"), "шт", "szt", new BigDecimal("100.00"), new BigDecimal("60.00"),
                null, new BigDecimal("10.00"), LocalDate.of(2030, 7, 1), LocalDate.of(2030, 6, 20),
                LocalDate.of(2030, 6, 25), TODAY.minusDays(1), 7L, "REQ-7", 5L, "M1", "SKU1",
                "Про", "Pro", "http://x", "Продавец", "Sprzedawca");
    }

    private static StaffRoundSource round(long id, int no, ApprovalRoundKind kind, ApprovalRoundStatus st) {
        return new StaffRoundSource(id, 1L, no, kind, st, TODAY.minusDays(1), "note", null, null, null, 9L,
                null, false, null, null);
    }

    private static StaffOptionSource option(long roundId) {
        return new StaffOptionSource(50L + roundId, roundId, 0, new BigDecimal("100.00"),
                new BigDecimal("60.00"), 5L, "M1", "SKU1", "Про", "Pro", null, "Продавец", "Sprzedawca");
    }

    private StaffApprovalData data() {
        StaffRoundSource closed = round(10, 1, ApprovalRoundKind.PROPOSAL, ApprovalRoundStatus.SUPERSEDED);
        StaffRoundSource open = round(11, 2, ApprovalRoundKind.ANALOG, ApprovalRoundStatus.OPEN);
        StaffRoundSource sug = round(12, 3, ApprovalRoundKind.CLIENT_SUGGESTION, ApprovalRoundStatus.OPEN);
        return new StaffApprovalData(List.of(position()),
                Map.of(1L, List.of(closed, open, sug)),
                Map.of(10L, List.of(option(10)), 11L, List.of(option(11))));
    }

    @Test
    @DisplayName("non STAFF_FULL audience is rejected with 403 staff.forbidden")
    void rejectsNonStaff() {
        for (Audience a : new Audience[] {Audience.CLIENT, Audience.SITE, null}) {
            assertThatThrownBy(() -> assembler.assemble(data(), a, "MANAGER", TODAY, false))
                    .isInstanceOfSatisfying(ForemenApiException.class, e ->
                    {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getMessage()).isEqualTo("error.positionApproval.staff.forbidden");
                    });
        }
    }

    @Test
    @DisplayName("manager row carries internal money, margins, rounds split and write actions")
    void managerRow() {
        StaffApprovalPositionDto dto = assembler.assemble(data(), Audience.STAFF_FULL, "MANAGER", TODAY, true)
                .get(0);
        assertThat(dto.internalUnitPurchasePrice()).isEqualByComparingTo("60.00");
        assertThat(dto.marginPerUnit()).isEqualByComparingTo("40.00");
        assertThat(dto.marginPercent()).isEqualByComparingTo("40.00");
        assertThat(dto.lineMargin()).isEqualByComparingTo("110.00"); // 3 * 40 - 10 delivery
        assertThat(dto.sellerName()).isEqualTo("Sprzedawca");
        assertThat(dto.producerName()).isEqualTo("Pro");
        assertThat(dto.requestId()).isEqualTo(7L);
        assertThat(dto.statusCode()).isEqualTo("PROPOSED");
        assertThat(dto.overdue()).isTrue();
        assertThat(dto.openRound().roundId()).isEqualTo(11L);
        assertThat(dto.openRound().options().get(0).internalUnitPurchasePrice()).isEqualByComparingTo("60.00");
        assertThat(dto.openRound().options().get(0).lineMargin()).isEqualByComparingTo("120.00");
        assertThat(dto.openSuggestions()).extracting(StaffRoundDto::roundId).containsExactly(12L);
        assertThat(dto.history()).extracting(StaffRoundDto::roundId).containsExactly(10L);
        assertThat(dto.allowedActions()).contains(StaffAction.WITHDRAW, StaffAction.CHANGE_DEADLINE,
                StaffAction.DECIDE_ON_BEHALF, StaffAction.RESOLVE_SUGGESTION);
    }

    @Test
    @DisplayName("buyer sees the same money but no write actions; ru names are used for non-pl")
    void buyerRow() {
        StaffApprovalPositionDto dto = assembler.assemble(data(), Audience.STAFF_FULL, "BUYER", TODAY, false)
                .get(0);
        assertThat(dto.internalUnitPurchasePrice()).isEqualByComparingTo("60.00");
        assertThat(dto.allowedActions()).isEmpty();
        assertThat(dto.sellerName()).isEqualTo("Продавец");
    }

    @Test
    @DisplayName("missing purchase price makes margins null")
    void unavailableMargin() {
        StaffPositionSource p = position();
        StaffPositionSource noCost = new StaffPositionSource(p.positionId(), p.blockNo(), p.status(),
                p.sourcingPath(), p.quantity(), p.unitNameRu(), p.unitNamePl(), p.salePrice(), null, null, null,
                null, null, null, null, null, null, p.materialId(), p.model(), p.sku(), null, null, null, null,
                null);
        StaffApprovalPositionDto dto = assembler.assemble(
                new StaffApprovalData(List.of(noCost), Map.of(), Map.of()), Audience.STAFF_FULL, "ADMIN", TODAY,
                false).get(0);
        assertThat(dto.marginPerUnit()).isNull();
        assertThat(dto.marginPercent()).isNull();
        assertThat(dto.lineMargin()).isNull();
        assertThat(dto.openRound()).isNull();
        assertThat(dto.overdue()).isFalse();
    }
}
