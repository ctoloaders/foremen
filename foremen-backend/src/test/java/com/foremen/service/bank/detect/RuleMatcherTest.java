package com.foremen.service.bank.detect;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.LinkKind;
import com.foremen.service.bank.detect.MatchCandidate.TargetType;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;
import com.foremen.service.bank.detect.RuleMatcher.MatchResult;
import com.foremen.service.bank.detect.TxnFacts.Direction;
import com.foremen.service.bank.ingest.IbanNormalizer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example tests per kind for {@link RuleMatcher} (FOR-07-04, Requirements 14.2–14.7, 28.2).
 */
class RuleMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final String BBAN = "102010260000042270201111";
    private static final String OWN_IBAN = "PL" + IbanNormalizer.checkDigits("PL", BBAN) + BBAN;

    private final RuleMatcher matcher = new RuleMatcher();

    private static TxnFacts txn(Direction dir, String amount, String title, String name, String iban, String key) {
        return new TxnFacts(dir, new BigDecimal(amount), DAY, title, name, iban, key);
    }

    private static MatchInputs inputs(List<OwnAccount> own, List<CounterpartyRule> rules, List<ScheduleCandidate> s,
                                      List<PayoutCandidate> p, List<DocumentCandidate> d) {
        return new MatchInputs(own, rules, s, p, d);
    }

    private static PayoutCandidate payout(long id, String amount, LocalDate date, PayoutStatus status,
                                          PaymentChannel channel, boolean fullyLinked) {
        return new PayoutCandidate(id, 7L, "Jan Kowalski", new BigDecimal(amount), date, status, channel, fullyLinked);
    }

    private static DocumentCandidate doc(long id, String total, DocumentPaymentStatus status) {
        return new DocumentCandidate(id, total == null ? null : new BigDecimal(total), "FV/2026/0042",
                "Castorama Polska", "5260207427", DAY.minusDays(5), DAY.minusDays(4), status);
    }

    @Nested
    class InternalTransfer {

        @Test
        void counterpartyIbanOfOwnAccountIsAutoAppliedAtFullConfidence() {
            String spaced = OWN_IBAN.substring(0, 4) + " " + OWN_IBAN.substring(4).toLowerCase();
            for (Direction dir : Direction.values()) {
                MatchResult r = matcher.match(txn(dir, "-500.00", "przelew", "Foremen", spaced, null),
                        inputs(List.of(new OwnAccount(3L, OWN_IBAN)), null, null, null, null));

                assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
                MatchCandidate c = r.auto().orElseThrow();
                assertThat(c.kind()).isEqualTo(LinkKind.INTERNAL_TRANSFER);
                assertThat(c.targetRef().type()).isEqualTo(TargetType.OWN_ACCOUNT);
                assertThat(c.targetRef().id()).isEqualTo(3L);
                assertThat(c.score()).isEqualByComparingTo("1.00");
            }
        }

        @Test
        void otherIbanIsNotAnInternalTransfer() {
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "500.00", null, null, "PL00123", null),
                    inputs(List.of(new OwnAccount(3L, OWN_IBAN)), null, null, null, null));
            assertThat(r.decision()).isEqualTo(Decision.NONE);
            assertThat(r.ranked()).isEmpty();
        }
    }

    @Nested
    class ClientPayment {

        private final ScheduleCandidate published = new ScheduleCandidate(11L, 42L, true, "Anna Nowak", "1234563218",
                List.of(new BigDecimal("3000.00")));

        @Test
        void projectReferenceOnIncomeIsAutoAppliedAt095() {
            MatchResult r = matcher.match(txn(Direction.INCOME, "123.00", "Zaliczka fm-42 remont", "X", null, null),
                    inputs(null, null, List.of(published), null, null));

            assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
            MatchCandidate c = r.auto().orElseThrow();
            assertThat(c.kind()).isEqualTo(LinkKind.CLIENT_PAYMENT);
            assertThat(c.targetRef().id()).isEqualTo(11L);
            assertThat(c.targetRef().projectId()).isEqualTo(42L);
            assertThat(c.score()).isEqualByComparingTo("0.95");
            assertThat(c.reasons()).first().isEqualTo(RuleMatcher.R_PROJECT_REFERENCE);
        }

        @Test
        void referenceToUnpublishedScheduleOrOnExpenseIsIgnored() {
            ScheduleCandidate draft = new ScheduleCandidate(11L, 42L, false, "Anna Nowak", null, List.of());
            assertThat(matcher.match(txn(Direction.INCOME, "1.00", "FM-42", null, null, null),
                    inputs(null, null, List.of(draft), null, null)).ranked()).isEmpty();
            assertThat(matcher.match(txn(Direction.EXPENSE, "1.00", "FM-42", null, null, null),
                    inputs(null, null, List.of(published), null, null)).ranked()).isEmpty();
        }

        @Test
        void referenceParsingIgnoresEmbeddedTokens() {
            assertThat(RuleMatcher.projectReferences("FM-42 and fm-7, XFM-9, FM-123abc")).containsExactly(7L, 42L);
            assertThat(RuleMatcher.projectReferences(null)).isEmpty();
        }

        @Test
        void clientNameAndTrancheAmountWithoutReferenceAreSuggested() {
            MatchResult r = matcher.match(txn(Direction.INCOME, "3000", "rata", "NOWAK ANNA", null, null),
                    inputs(null, null, List.of(published), null, null));

            assertThat(r.decision()).isEqualTo(Decision.SUGGEST);
            assertThat(r.selected()).singleElement().satisfies(c -> {
                assertThat(c.score()).isEqualByComparingTo("0.80");
                assertThat(c.reasons()).containsExactly(RuleMatcher.R_CLIENT_MATCH, RuleMatcher.R_TRANCHE_AMOUNT);
            });
        }

        @Test
        void clientNipAndRuleHitAddUp() {
            CounterpartyRule rule = new CounterpartyRule("NAME:ACME", LinkKind.CLIENT_PAYMENT, null, null, null,
                    42L, null, null, null, true);
            MatchResult r = matcher.match(txn(Direction.INCOME, "3000.00", "NIP 123-456-32-18", "ACME", null, "NAME:ACME"),
                    inputs(null, List.of(rule), List.of(published), null, null));
            assertThat(r.ranked()).singleElement()
                    .satisfies(c -> assertThat(c.score()).isEqualByComparingTo("1.00"));
            assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
        }
    }

    @Nested
    class WorkerPayout {

        @Test
        void plannedBankTransferPayoutWithAmountDateNameIsAutoApplied() {
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "-2500.00", "wyplata", "KOWALSKI JAN", null, null),
                    inputs(null, null, null,
                            List.of(payout(5L, "2500", DAY.plusDays(7), PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false)),
                            null));

            assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
            MatchCandidate c = r.auto().orElseThrow();
            assertThat(c.kind()).isEqualTo(LinkKind.WORKER_PAYOUT);
            assertThat(c.score()).isEqualByComparingTo("0.90");
            assertThat(c.reasons()).containsExactly(RuleMatcher.R_AMOUNT, RuleMatcher.R_DATE, RuleMatcher.R_NAME);
        }

        @Test
        void cashPayoutsAndFullyLinkedPaidPayoutsAreNeverCandidates() {
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "2500.00", null, "Jan Kowalski", null, null),
                    inputs(null, null, null, List.of(
                            payout(5L, "2500", DAY, PayoutStatus.PLANNED, PaymentChannel.CASH, false),
                            payout(6L, "2500", DAY, PayoutStatus.PAID, PaymentChannel.BANK_TRANSFER, true)), null));
            assertThat(r.ranked()).isEmpty();
            assertThat(r.decision()).isEqualTo(Decision.NONE);
        }

        @Test
        void paidNotLinkedPayoutIsPenalisedAndRuleHitReplacesNameSignal() {
            CounterpartyRule rule = new CounterpartyRule("IBAN:X", LinkKind.WORKER_PAYOUT, 7L, null, null, null, null,
                    null, null, true);
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "2500.00", null, "Jan Kowalski", null, "IBAN:X"),
                    inputs(null, List.of(rule), null,
                            List.of(payout(6L, "2500", DAY, PayoutStatus.PAID, PaymentChannel.BANK_TRANSFER, false)), null));
            MatchCandidate c = r.ranked().get(0);
            // 0.5 amount + 0.2 date + 0.3 rule − 0.1 paid
            assertThat(c.score()).isEqualByComparingTo("0.90");
            assertThat(c.reasons()).contains(RuleMatcher.R_RULE_HIT, RuleMatcher.R_ALREADY_PAID)
                    .doesNotContain(RuleMatcher.R_NAME);
        }

        @Test
        void disabledRuleIsIgnoredAndIncomeLineNeverMatchesPayouts() {
            CounterpartyRule rule = new CounterpartyRule("IBAN:X", LinkKind.WORKER_PAYOUT, 7L, null, null, null, null,
                    null, null, false);
            PayoutCandidate p = payout(6L, "2500", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false);
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "2500.00", null, "Other", null, "IBAN:X"),
                    inputs(null, List.of(rule), null, List.of(p), null));
            assertThat(r.ranked().get(0).score()).isEqualByComparingTo("0.70");
            assertThat(matcher.match(txn(Direction.INCOME, "2500.00", null, "Jan Kowalski", null, null),
                    inputs(null, null, null, List.of(p), null)).ranked()).isEmpty();
        }

        @Test
        void twoEqualCandidatesFailTheMarginAndAreSuggestedInDeterministicOrder() {
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "2500.00", null, "Jan Kowalski", null, null),
                    inputs(null, null, null, List.of(
                            payout(9L, "2500", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false),
                            payout(4L, "2500", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false)), null));
            assertThat(r.decision()).isEqualTo(Decision.SUGGEST);
            assertThat(r.selected()).extracting(c -> c.targetRef().id()).containsExactly(4L, 9L);
        }
    }

    @Nested
    class Procurement {

        @Test
        void amountInvoiceNumberAndDateAreAutoApplied() {
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "-1230.45", "Platnosc za FV 2026 0042", "Jan", null, null),
                    inputs(null, null, null, null, List.of(doc(21L, "1230.44", DocumentPaymentStatus.UNMATCHED))));

            assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
            MatchCandidate c = r.auto().orElseThrow();
            assertThat(c.kind()).isEqualTo(LinkKind.PROCUREMENT);
            assertThat(c.targetRef().type()).isEqualTo(TargetType.PROCUREMENT_DOCUMENT);
            assertThat(c.score()).isEqualByComparingTo("1.00");
            assertThat(c.reasons()).containsExactly(RuleMatcher.R_AMOUNT, RuleMatcher.R_INVOICE_NUMBER, RuleMatcher.R_DATE);
        }

        @Test
        void paidDocumentsAndIncomeLinesAreNeverCandidates() {
            TxnFacts expense = txn(Direction.EXPENSE, "1230.44", "FV/2026/0042", "Castorama", null, null);
            assertThat(RuleMatcher.scoreProcurement(expense, doc(21L, "1230.44", DocumentPaymentStatus.PAID), List.of()))
                    .isEmpty();
            TxnFacts income = txn(Direction.INCOME, "1230.44", "FV/2026/0042", "Castorama", null, null);
            assertThat(RuleMatcher.scoreProcurement(income, doc(21L, "1230.44", DocumentPaymentStatus.UNMATCHED), List.of()))
                    .isEmpty();
        }

        @Test
        void singleDocumentScorerUsesSupplierNameNipAndDateWindow() {
            TxnFacts t = txn(Direction.EXPENSE, "99.00", "zakupy", "CASTORAMA POLSKA SP. Z O.O.", null, null);
            MatchCandidate c = RuleMatcher.scoreProcurement(t, doc(21L, null, DocumentPaymentStatus.PARTIALLY_PAID), null)
                    .orElseThrow();
            assertThat(c.score()).isEqualByComparingTo("0.30");
            assertThat(c.reasons()).containsExactly(RuleMatcher.R_SUPPLIER, RuleMatcher.R_DATE);

            DocumentCandidate old = new DocumentCandidate(22L, null, null, null, "5260207427",
                    DAY.minusDays(61), DAY.plusDays(31), DocumentPaymentStatus.UNMATCHED);
            TxnFacts nip = txn(Direction.EXPENSE, "99.00", "NIP: 526-020-74-27", "X", null, null);
            assertThat(RuleMatcher.scoreProcurement(nip, old, null).orElseThrow().reasons())
                    .containsExactly(RuleMatcher.R_SUPPLIER);
        }

        @Test
        void reverseMatchingDecisionUsesTheSameRule() {
            MatchThresholds t = MatchThresholds.DEFAULTS;
            assertThat(RuleMatcher.decision(List.of(new BigDecimal("1.00"), new BigDecimal("0.80")), t))
                    .isEqualTo(Decision.AUTO_APPLY);
            assertThat(RuleMatcher.decision(List.of(new BigDecimal("1.00"), new BigDecimal("0.81")), t))
                    .isEqualTo(Decision.SUGGEST);
            assertThat(RuleMatcher.decision(List.of(new BigDecimal("0.49")), t)).isEqualTo(Decision.NONE);
            assertThat(RuleMatcher.decision(List.of(), t)).isEqualTo(Decision.NONE);
        }
    }

    @Nested
    class RuleKinds {

        @Test
        void otherExpenseRuleIsAutoAppliedOnExpenseOnly() {
            CounterpartyRule rule = new CounterpartyRule("NAME:ORLEN", LinkKind.OTHER_EXPENSE, null, null, null, 42L,
                    null, 8L, null, true);
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "200.00", "paliwo", "ORLEN", null, "NAME:ORLEN"),
                    inputs(null, List.of(rule), null, null, null));
            assertThat(r.decision()).isEqualTo(Decision.AUTO_APPLY);
            MatchCandidate c = r.auto().orElseThrow();
            assertThat(c.kind()).isEqualTo(LinkKind.OTHER_EXPENSE);
            assertThat(c.targetRef().type()).isEqualTo(TargetType.UNPLANNED_CATEGORY);
            assertThat(c.targetRef().id()).isEqualTo(8L);
            assertThat(c.targetRef().projectId()).isEqualTo(42L);
            assertThat(c.score()).isEqualByComparingTo("0.90");

            MatchResult income = matcher.match(txn(Direction.INCOME, "200.00", null, "ORLEN", null, "NAME:ORLEN"),
                    inputs(null, List.of(rule), null, null, null));
            assertThat(income.ranked()).isEmpty();
        }

        @Test
        void suggestionsAreCappedAtMaxAndBelowThresholdIsNone() {
            List<PayoutCandidate> many = List.of(
                    payout(1L, "10", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false),
                    payout(2L, "10", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false),
                    payout(3L, "10", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false),
                    payout(4L, "10", DAY, PayoutStatus.PLANNED, PaymentChannel.BANK_TRANSFER, false));
            MatchResult r = matcher.match(txn(Direction.EXPENSE, "10.00", null, null, null, null),
                    inputs(null, null, null, many, null));
            assertThat(r.ranked()).hasSize(4);
            assertThat(r.decision()).isEqualTo(Decision.SUGGEST);
            assertThat(r.selected()).extracting(c -> c.targetRef().id()).containsExactly(1L, 2L, 3L);

            MatchResult weak = matcher.match(txn(Direction.EXPENSE, "11.00", null, null, null, null),
                    inputs(null, null, null, many, null));
            assertThat(weak.ranked()).allSatisfy(c -> assertThat(c.score()).isEqualByComparingTo("0.20"));
            assertThat(weak.decision()).isEqualTo(Decision.NONE);
            assertThat(weak.selected()).isEmpty();
        }
    }
}
