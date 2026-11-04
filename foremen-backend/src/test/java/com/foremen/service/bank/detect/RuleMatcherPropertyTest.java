package com.foremen.service.bank.detect;

import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;
import com.foremen.dao.model.LinkKind;
import com.foremen.service.bank.detect.MatchInputs.CounterpartyRule;
import com.foremen.service.bank.detect.MatchInputs.DocumentCandidate;
import com.foremen.service.bank.detect.MatchInputs.OwnAccount;
import com.foremen.service.bank.detect.MatchInputs.PayoutCandidate;
import com.foremen.service.bank.detect.MatchInputs.ScheduleCandidate;
import com.foremen.service.bank.detect.RuleMatcher.MatchResult;
import com.foremen.service.bank.detect.TxnFacts.Direction;
import com.foremen.service.bank.ingest.IbanNormalizer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties 5 and 6 (FOR-07-04) for the pure {@link RuleMatcher}.
 *
 * <p>Inputs are drawn from small pools (amounts, dates, names, IBANs, keys, project ids) so that
 * signals collide often and every scoring path, tie and threshold edge is exercised.
 *
 * <p><b>Validates: Requirements 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 28.2</b>
 */
class RuleMatcherPropertyTest {

    private static final LocalDate BASE = LocalDate.of(2026, 3, 10);
    private static final List<String> AMOUNTS = List.of("100.00", "250.50", "999.99", "1000.00", "3000.00");
    private static final List<String> NAMES = List.of("Jan Kowalski", "KOWALSKI JAN", "Anna Nowak",
            "Castorama Polska Sp. z o.o.", "ORLEN", "Foremen");
    private static final List<String> NIPS = List.of("5260207427", "1234563218");
    private static final List<String> KEYS = List.of("IBAN:A", "NAME:ORLEN", "NAME:JAN KOWALSKI");
    private static final List<String> IBANS = List.of(iban("102010260000042270201111"),
            iban("114020040000350230599137"), iban("249000050000400030900682"));
    private static final List<String> TITLES = List.of("przelew", "FM-1 rata", "fm-2", "FV/2026/0042",
            "NIP 526-020-74-27", "zaliczka FM-3 FV 2026 0042");

    private static String iban(String bban) {
        return "PL" + IbanNormalizer.checkDigits("PL", bban) + bban;
    }

    /** One generated matcher run: the line, the candidates and the thresholds. */
    record Scenario(TxnFacts txn, MatchInputs inputs, MatchThresholds thresholds) {
    }

    // ------------------------------------------------------------------ generators

    private static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.of(AMOUNTS).map(BigDecimal::new);
    }

    private static Arbitrary<LocalDate> dates() {
        return Arbitraries.integers().between(-70, 40).map(BASE::plusDays);
    }

    private static <T> Arbitrary<T> orNull(Arbitrary<T> a) {
        return a.injectNull(0.2);
    }

    private static Arbitrary<Long> ids() {
        return Arbitraries.longs().between(1, 5);
    }

    private static Arbitrary<TxnFacts> txns() {
        return Combinators.combine(Arbitraries.of(Direction.class), amounts(),
                        Arbitraries.integers().between(-3, 3).map(BASE::plusDays),
                        orNull(Arbitraries.of(TITLES)), orNull(Arbitraries.of(NAMES)),
                        orNull(Arbitraries.of(IBANS)), orNull(Arbitraries.of(KEYS)))
                .as(TxnFacts::new);
    }

    private static Arbitrary<OwnAccount> ownAccounts() {
        return Combinators.combine(ids(), Arbitraries.of(IBANS)).as(OwnAccount::new);
    }

    private static Arbitrary<CounterpartyRule> rules() {
        return Combinators.combine(Arbitraries.of(KEYS), Arbitraries.of(LinkKind.class), orNull(ids()),
                        orNull(Arbitraries.of(NAMES)), orNull(Arbitraries.of(NIPS)), orNull(ids()), orNull(ids()),
                        orNull(ids()))
                .flatAs((key, kind, worker, sName, sNip, project, schedule, category) ->
                        Combinators.combine(orNull(ids()), Arbitraries.of(true, false))
                                .as((counter, enabled) -> new CounterpartyRule(key, kind, worker, sName, sNip,
                                        project, schedule, category, counter, enabled)));
    }

    private static Arbitrary<ScheduleCandidate> schedules() {
        return Combinators.combine(ids(), ids(), Arbitraries.of(true, false), orNull(Arbitraries.of(NAMES)),
                        orNull(Arbitraries.of(NIPS)), amounts().list().ofMaxSize(2))
                .as(ScheduleCandidate::new);
    }

    private static Arbitrary<PayoutCandidate> payouts() {
        return Combinators.combine(ids(), ids(), orNull(Arbitraries.of(NAMES)), orNull(amounts()),
                        orNull(dates()), orNull(Arbitraries.of(PayoutStatus.class)),
                        Arbitraries.of(PaymentChannel.class), Arbitraries.of(true, false))
                .as(PayoutCandidate::new);
    }

    private static Arbitrary<DocumentCandidate> documents() {
        return Combinators.combine(ids(), orNull(amounts()), orNull(Arbitraries.of("FV/2026/0042", "12", "A-77")),
                        orNull(Arbitraries.of(NAMES)), orNull(Arbitraries.of(NIPS)), orNull(dates()), orNull(dates()),
                        orNull(Arbitraries.of(DocumentPaymentStatus.class)))
                .as(DocumentCandidate::new);
    }

    private static Arbitrary<MatchThresholds> thresholds() {
        Arbitrary<Integer> pct = Arbitraries.integers().between(0, 100);
        return Combinators.combine(pct, pct, Arbitraries.integers().between(0, 50), Arbitraries.integers().between(0, 4))
                .as((auto, suggest, margin, max) -> new MatchThresholds(BigDecimal.valueOf(auto, 2),
                        BigDecimal.valueOf(suggest, 2), BigDecimal.valueOf(margin, 2), max));
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<MatchInputs> inputs = Combinators.combine(ownAccounts().list().ofMaxSize(2),
                        rules().list().ofMaxSize(3), schedules().list().ofMaxSize(3),
                        payouts().list().ofMaxSize(4), documents().list().ofMaxSize(4))
                .as(MatchInputs::new);
        Arbitrary<MatchThresholds> t = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, Arbitraries.just(MatchThresholds.DEFAULTS)),
                net.jqwik.api.Tuple.of(1, thresholds()));
        return Combinators.combine(txns(), inputs, t).as(Scenario::new);
    }

    // ------------------------------------------------------------------ Property 5

    /**
     * Property 5: direction safety — no candidate (ranked, auto-applied or suggested) carries a
     * kind that contradicts the line direction.
     */
    @Property(tries = 500)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 5: Direction safety")
    void noCandidateKindContradictsTheLineDirection(@ForAll("scenarios") Scenario s) {
        MatchResult r = new RuleMatcher(s.thresholds()).match(s.txn(), s.inputs());

        Set<String> allowed = ClassifierAnswerValidator.allowedKinds(s.txn().direction().name());
        assertThat(r.ranked()).allSatisfy(c -> assertThat(allowed).contains(c.kind().name()));
        assertThat(r.selected()).allSatisfy(c -> assertThat(allowed).contains(c.kind().name()));
        if (s.txn().direction() == Direction.INCOME) {
            assertThat(r.ranked()).noneMatch(c -> c.kind() == LinkKind.WORKER_PAYOUT
                    || c.kind() == LinkKind.PROCUREMENT || c.kind() == LinkKind.OTHER_EXPENSE);
        } else {
            assertThat(r.ranked()).noneMatch(c -> c.kind() == LinkKind.CLIENT_PAYMENT || c.kind() == LinkKind.OTHER_INCOME);
        }
    }

    // ------------------------------------------------------------------ Property 6

    /**
     * Property 6: matcher determinism — the output is a pure function of the inputs (repeat runs and
     * any reordering of the candidate lists give an equal result), and {@code AUTO_APPLY} happens
     * exactly when top ≥ auto-threshold and (top − runner-up) ≥ margin; otherwise suggestions are
     * the top ≤ max candidates ≥ suggest-threshold.
     */
    @Property(tries = 500)
    @Tag("Feature: FOR-07-04-bank-ledger, Property 6: Matcher determinism")
    void outputIsPureAndAutoOnlyWhenThresholdAndMarginHold(@ForAll("scenarios") Scenario s,
                                                           @ForAll long seed) {
        RuleMatcher matcher = new RuleMatcher(s.thresholds());
        MatchResult first = matcher.match(s.txn(), s.inputs());

        assertThat(matcher.match(s.txn(), s.inputs())).isEqualTo(first);
        assertThat(matcher.match(s.txn(), shuffled(s.inputs(), new Random(seed)))).isEqualTo(first);

        List<MatchCandidate> ranked = first.ranked();
        List<MatchCandidate> sorted = new ArrayList<>(ranked);
        sorted.sort(MatchCandidate.RANKING);
        assertThat(ranked).isEqualTo(sorted);
        assertThat(ranked).allSatisfy(c -> {
            assertThat(c.score()).isGreaterThan(BigDecimal.ZERO);
            assertThat(c.score()).isLessThanOrEqualTo(BigDecimal.ONE);
        });

        MatchThresholds t = s.thresholds();
        boolean autoHolds = !ranked.isEmpty()
                && ranked.get(0).score().compareTo(t.autoThreshold()) >= 0
                && ranked.get(0).score().subtract(ranked.size() > 1 ? ranked.get(1).score() : BigDecimal.ZERO)
                        .compareTo(t.margin()) >= 0;
        assertThat(first.decision() == Decision.AUTO_APPLY).isEqualTo(autoHolds);

        switch (first.decision()) {
            case AUTO_APPLY -> assertThat(first.selected()).containsExactly(ranked.get(0));
            case SUGGEST -> {
                assertThat(first.selected()).isNotEmpty().hasSizeLessThanOrEqualTo(t.maxSuggestions());
                assertThat(first.selected()).allSatisfy(
                        c -> assertThat(c.score()).isGreaterThanOrEqualTo(t.suggestThreshold()));
                assertThat(ranked).startsWith(first.selected().toArray(MatchCandidate[]::new));
            }
            case NONE -> {
                assertThat(first.selected()).isEmpty();
                assertThat(ranked.isEmpty() || t.maxSuggestions() == 0
                        || ranked.get(0).score().compareTo(t.suggestThreshold()) < 0).isTrue();
            }
        }
    }

    private static MatchInputs shuffled(MatchInputs in, Random rnd) {
        return new MatchInputs(shuffle(in.ownAccounts(), rnd), shuffle(in.rules(), rnd),
                shuffle(in.schedules(), rnd), shuffle(in.payouts(), rnd), shuffle(in.documents(), rnd));
    }

    private static <T> List<T> shuffle(List<T> list, Random rnd) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy, rnd);
        return copy;
    }
}
