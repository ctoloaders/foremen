package com.foremen.service.bank.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.LinkKind;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.ExternalOrderCandidate;
import com.foremen.service.bank.detect.MatchInputs.ProformaCandidate;
import com.foremen.service.bank.detect.RuleMatcher.MatchResult;
import com.foremen.service.bank.detect.TxnFacts.Direction;
import com.foremen.service.bank.link.LinkRequest;

/**
 * FOR-11-03 (Requirements 10.1, 10.2, 10.7): proforma and external-order reason codes, the number regex,
 * direction rules and the AI exclusion.
 */
class RuleMatcherProcurementKindsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private final RuleMatcher matcher = new RuleMatcher();

    private static TxnFacts txn(Direction dir, String amount, String title, String name) {
        return new TxnFacts(dir, new BigDecimal(amount), DAY, title, name, null, null);
    }

    private static ProformaCandidate proforma(long id, String number, String remainder) {
        return new ProformaCandidate(id, 5L, number, new BigDecimal(remainder), "Anna Nowak", null);
    }

    private static MatchInputs withProformas(ProformaCandidate... p) {
        return new MatchInputs(null, null, null, null, null, List.of(p), null);
    }

    private static MatchInputs withOrders(ExternalOrderCandidate... o) {
        return new MatchInputs(null, null, null, null, null, null, List.of(o));
    }

    @Test
    void numberAndExactAmountAutoApply() {
        MatchResult r = matcher.match(txn(Direction.INCOME, "1230.00", "Proforma PF/2026/000123", "X"),
                withProformas(proforma(9, "PF/2026/000123", "1230.00")));
        assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
        MatchCandidate c = r.auto().orElseThrow();
        assertThat(c.kind()).isEqualTo(LinkKind.PROFORMA_PAYMENT);
        assertThat(c.targetRef().type()).isEqualTo(TargetType.CLIENT_PROFORMA);
        assertThat(c.targetRef().id()).isEqualTo(9L);
        assertThat(c.reasons()).containsExactly(RuleMatcher.R_PROFORMA_NUMBER, RuleMatcher.R_PROFORMA_AMOUNT);
        assertThat(LinkRequest.fromCandidate(c).clientProformaId()).isEqualTo(9L);
    }

    @Test
    void numberToleratesStrippedSlashesAndSpaces() {
        for (String title : List.of("PF2026000123", "pf 2026 000123", "zaplata PF-2026-000123 dzieki")) {
            MatchResult r = matcher.match(txn(Direction.INCOME, "500.00", title, "X"),
                    withProformas(proforma(9, "PF/2026/000123", "1230.00")));
            assertThat(r.decision()).as(title).isEqualTo(Decision.SUGGEST);
            assertThat(r.selected().get(0).reasons()).containsExactly(RuleMatcher.R_PROFORMA_NUMBER);
        }
    }

    @Test
    void numberHitScoresAtLeastAnInvoiceNumberHit() {
        MatchCandidate c = RuleMatcher.scoreProforma(txn(Direction.INCOME, "10.00", "PF/2026/000123", "X"),
                proforma(9, "PF/2026/000123", "1230.00")).orElseThrow();
        assertThat(c.score()).isGreaterThanOrEqualTo(new BigDecimal("0.40"));
    }

    @Test
    void anotherNumberOrLongerDigitsDoNotMatch() {
        assertThat(RuleMatcher.scoreProforma(txn(Direction.INCOME, "10.00", "PF/2026/000124", "Y"),
                proforma(9, "PF/2026/000123", "1230.00"))).isEmpty();
        assertThat(RuleMatcher.scoreProforma(txn(Direction.INCOME, "10.00", "PF/2026/0001234", "Y"),
                proforma(9, "PF/2026/000123", "1230.00"))).isEmpty();
    }

    @Test
    void proformaIsOnlyOfferedOnCreditLines() {
        MatchResult r = matcher.match(txn(Direction.EXPENSE, "1230.00", "PF/2026/000123", "Anna Nowak"),
                withProformas(proforma(9, "PF/2026/000123", "1230.00")));
        assertThat(r.ranked()).isEmpty();
    }

    @Test
    void clientNameAndAmountSuggestWithoutNumber() {
        MatchResult r = matcher.match(txn(Direction.INCOME, "1230.00", "zaliczka", "Anna Nowak"),
                withProformas(proforma(9, "PF/2026/000123", "1230.00")));
        assertThat(r.decision()).isEqualTo(Decision.SUGGEST);
        assertThat(r.selected().get(0).reasons())
                .containsExactly(RuleMatcher.R_PROFORMA_AMOUNT, RuleMatcher.R_PROFORMA_CLIENT);
    }

    @Test
    void externalOrderDocumentAndAmountAutoApplyOnDebitLine() {
        ExternalOrderCandidate o = new ExternalOrderCandidate(4, 5L, new BigDecimal("999.99"), "Castorama Polska",
                "5260207427", List.of("FV/2026/0042"), List.of(), List.of());
        MatchResult r = matcher.match(txn(Direction.EXPENSE, "-999.99", "Zaplata FV/2026/0042", "Inny"), withOrders(o));
        assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
        MatchCandidate c = r.auto().orElseThrow();
        assertThat(c.kind()).isEqualTo(LinkKind.EXTERNAL_ORDER_PAYMENT);
        assertThat(c.targetRef().type()).isEqualTo(TargetType.EXTERNAL_ORDER);
        assertThat(c.reasons()).containsExactly(RuleMatcher.R_ORDER_DOCUMENT, RuleMatcher.R_ORDER_AMOUNT);
        assertThat(LinkRequest.fromCandidate(c).externalOrderId()).isEqualTo(4L);
    }

    @Test
    void externalOrderSellerFromLinkedDocumentNipAndNotOnCreditLine() {
        ExternalOrderCandidate o = new ExternalOrderCandidate(4, 5L, new BigDecimal("999.99"), "Castorama Polska",
                null, List.of(), List.of("526-020-74-27"), List.of());
        MatchCandidate c = RuleMatcher.scoreExternalOrder(
                txn(Direction.EXPENSE, "100.00", "NIP 5260207427", "Inny"), o).orElseThrow();
        assertThat(c.reasons()).containsExactly(RuleMatcher.R_ORDER_SELLER);
        assertThat(RuleMatcher.scoreExternalOrder(txn(Direction.INCOME, "999.99", "FV", "Castorama Polska"), o))
                .isEmpty();
    }

    @Test
    void proformaAndOrderCandidatesNeverReachTheAi() {
        MatchCandidate pf = new MatchCandidate(LinkKind.PROFORMA_PAYMENT,
                new MatchCandidate.TargetRef(TargetType.CLIENT_PROFORMA, 9, 5L), new BigDecimal("0.60"),
                List.of(RuleMatcher.R_PROFORMA_NUMBER));
        MatchCandidate eo = new MatchCandidate(LinkKind.EXTERNAL_ORDER_PAYMENT,
                new MatchCandidate.TargetRef(TargetType.EXTERNAL_ORDER, 4, 5L), new BigDecimal("0.60"),
                List.of(RuleMatcher.R_ORDER_AMOUNT));
        assertThat(CandidatePool.empty().aiCandidates(List.of(pf, eo))).isEmpty();
    }

    @Test
    void poolNarrowsProformasToIncomeAndOrdersToExpense() {
        CandidatePool pool = new CandidatePool(null, null, null, null,
                List.of(proforma(9, "PF/2026/000123", "10.00")),
                List.of(new ExternalOrderCandidate(4, 5L, BigDecimal.TEN, "S", null, null, null, null)), null);
        MatchInputs income = pool.inputsFor(txn(Direction.INCOME, "10.00", "t", "n"), List.of(), null);
        MatchInputs expense = pool.inputsFor(txn(Direction.EXPENSE, "10.00", "t", "n"), List.of(), null);
        assertThat(income.proformas()).hasSize(1);
        assertThat(income.externalOrders()).isEmpty();
        assertThat(expense.externalOrders()).hasSize(1);
        assertThat(expense.proformas()).isEmpty();
    }
}
