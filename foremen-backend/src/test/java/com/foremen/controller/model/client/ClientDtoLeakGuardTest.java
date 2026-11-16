package com.foremen.controller.model.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FOR-11-02 4.2 (R3.3-3.5, 4.1-4.4, 4.6, 4.8): leak guard and assembler behaviour. */
class ClientDtoLeakGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 10);
    private final ClientViewAssembler assembler = new ClientViewAssembler(cdn());

    private static com.foremen.service.image.ImageStorage cdn() {
        com.foremen.service.image.ImageStorage storage =
                org.mockito.Mockito.mock(com.foremen.service.image.ImageStorage.class);
        org.mockito.Mockito.when(storage.toCdnUrl(org.mockito.ArgumentMatchers.any())).thenAnswer(
                i -> i.getArgument(0) == null ? null : "https://cdn.test/" + i.getArgument(0));
        return storage;
    }

    private record Inner(String sellerName) { }

    private record BadRoot(BigDecimal purchasePrice, List<Inner> inner) { }

    private record NestedRoot(List<Inner> items) { }

    @Test
    void clientDtoTreeHasNoPurchaseSideNamesOrStaffTypes() {
        assertThat(ClientDtoLeakGuard.violations(ClientApprovalPositionDto.class)).isEmpty();
    }

    @Test
    void guardDetectsDenyListedComponent() {
        assertThat(ClientDtoLeakGuard.violations(BadRoot.class)).hasSize(2);
    }

    @Test
    void guardWalksGenericsAndNestedRecords() {
        assertThat(ClientDtoLeakGuard.violations(NestedRoot.class)).anyMatch(v -> v.contains("sellerName"));
    }

    @Test
    void assembledJsonHasNoDenyListedKeysAndMapsFields() throws Exception {
        ClientPositionSource p = new ClientPositionSource(1L, 2, PositionStatus.PROPOSED, SourcingPath.AGREED,
                new BigDecimal("3"), "шт", "szt", new BigDecimal("10.00"), new BigDecimal("23"),
                null, 5L, "M1", "S1", "Прод", "Prod", "http://l", "ph");
        ClientRoundSource open = new ClientRoundSource(10L, 1L, 1, ApprovalRoundKind.PROPOSAL,
                ApprovalRoundStatus.OPEN, TODAY.minusDays(1), "note", null, null, null, null, false, null);
        ClientRoundSource old = new ClientRoundSource(9L, 1L, 0, ApprovalRoundKind.PROPOSAL,
                ApprovalRoundStatus.REJECTED, null, null, null, "no", null, DecidedSide.CLIENT, false, null);
        ClientRoundSource sug = new ClientRoundSource(11L, 1L, 2, ApprovalRoundKind.CLIENT_SUGGESTION,
                ApprovalRoundStatus.OPEN, null, null, "http://s", null, null, null, false, null);
        ClientOptionSource opt = new ClientOptionSource(100L, 10L, 0, new BigDecimal("10.00"), 5L, "M1", "S1",
                "Прод", "Prod", null, null);
        ClientApprovalPositionDto dto = assembler.assemble(p, List.of(old, open, sug),
                Map.of(10L, List.of(opt)), Locale.forLanguageTag("pl"), TODAY);

        assertThat(dto.openRound().roundId()).isEqualTo(10L);
        assertThat(dto.openSuggestions()).hasSize(1);
        assertThat(dto.history()).hasSize(1);
        assertThat(dto.overdue()).isTrue();
        assertThat(dto.clientStatus()).isEqualTo(ClientStatus.AWAITING_DECISION);
        assertThat(dto.unitName()).isEqualTo("szt");
        assertThat(dto.item().producerName()).isEqualTo("Prod");
        assertThat(dto.item().photo()).isEqualTo("https://cdn.test/ph");
        assertThat(dto.money().lineGross()).isEqualByComparingTo("36.90");
        assertThat(dto.allowedActions()).contains(ClientAction.ACCEPT, ClientAction.REJECT);

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(dto)
                .toLowerCase(Locale.ROOT);
        for (String token : ClientDtoLeakGuard.DENY_TOKENS) {
            assertThat(json).doesNotContain("\"" + token);
        }
    }

    @Test
    void priceHiddenForPackagePathAndPackageChoiceOptionsHaveNoMoney() {
        ClientPositionSource p = new ClientPositionSource(1L, null, PositionStatus.PROPOSED, SourcingPath.PACKAGE,
                BigDecimal.ONE, "шт", "szt", new BigDecimal("10"), BigDecimal.ZERO, null, 5L, "M", null,
                null, null, null, null);
        ClientRoundSource open = new ClientRoundSource(10L, 1L, 1, ApprovalRoundKind.PACKAGE_CHOICE,
                ApprovalRoundStatus.OPEN, null, null, null, null, null, null, false, null);
        ClientOptionSource opt = new ClientOptionSource(100L, 10L, 0, new BigDecimal("10"), 5L, "M", null,
                null, null, null, null);
        ClientApprovalPositionDto dto = assembler.assemble(p, List.of(open), Map.of(10L, List.of(opt)),
                Locale.forLanguageTag("ru"), TODAY);
        assertThat(dto.money()).isNull();
        assertThat(dto.openRound().options().get(0).money()).isNull();
    }
}
