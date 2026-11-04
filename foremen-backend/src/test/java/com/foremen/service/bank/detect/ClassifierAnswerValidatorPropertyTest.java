package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-07-04 task 2.5 — Property 5 (direction safety) for AI answers and the R15.4 cap.
 *
 * <p>For any answer and direction, the validated answer:
 * <ul>
 *   <li>has a kind that is either {@code null} or allowed for the direction;</li>
 *   <li>references only supplied candidates / categories / projects;</li>
 *   <li>has confidence in {@code [0,1]}, and ≤ 0.3 whenever any field was dropped.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 15.4</b>
 */
class ClassifierAnswerValidatorPropertyTest {

    private static final List<String> ALL_KINDS = List.of("WORKER_PAYOUT", "PROCUREMENT",
            "OTHER_EXPENSE", "INTERNAL_TRANSFER", "CLIENT_PAYMENT", "OTHER_INCOME", "BOGUS");

    private static ClassificationInput input(String direction) {
        return new ClassificationInput(direction, BigDecimal.TEN, "PLN", LocalDate.of(2026, 1, 1),
                "t", "n", "0000", "bank",
                List.of(new ClassificationInput.Candidate("c1", "PROCUREMENT", "l", BigDecimal.TEN,
                        null), new ClassificationInput.Candidate("c2", "WORKER_PAYOUT", "l",
                        BigDecimal.TEN, null)),
                List.of(new ClassificationInput.Category("FUEL", "p", "r")),
                List.of(new ClassificationInput.Project(1L, "p", "c"),
                        new ClassificationInput.Project(2L, "p", "c")));
    }

    @Provide
    Arbitrary<ClassifierAnswer> answers() {
        Arbitrary<String> kind = Arbitraries.of(ALL_KINDS).injectNull(0.1);
        Arbitrary<String> ref = Arbitraries.of("c1", "c2", "c3", "x").injectNull(0.3);
        Arbitrary<String> cat = Arbitraries.of("FUEL", "fuel", "OTHER").injectNull(0.3);
        Arbitrary<Long> project = Arbitraries.longs().between(0, 4).injectNull(0.3);
        Arbitrary<Double> conf = Arbitraries.doubles().between(-1.0, 2.0);
        return Combinators.combine(kind, ref, cat, project, conf)
                .as((k, r, c, p, d) -> new ClassifierAnswer(k, r, c, p, d, "r"));
    }

    @Property(tries = 300)
    void validatedAnswerIsDirectionSafeAndCapped(@ForAll("answers") ClassifierAnswer answer,
            @ForAll("directions") String direction) {
        ClassificationInput in = input(direction);
        ClassifierAnswer v = ClassifierAnswerValidator.validate(answer, in);

        if (v.kind() != null) {
            assertThat(ClassifierAnswerValidator.allowedKinds(direction)).contains(v.kind());
        }
        if (v.candidateRef() != null) {
            assertThat(List.of("c1", "c2")).contains(v.candidateRef());
        }
        if (v.categoryCode() != null) {
            assertThat(v.categoryCode()).isEqualTo("FUEL");
        }
        if (v.projectId() != null) {
            assertThat(List.of(1L, 2L)).contains(v.projectId());
        }
        assertThat(v.confidence()).isBetween(0.0, 1.0);

        boolean dropped = v.kind() == null
                || (answer.candidateRef() != null && v.candidateRef() == null)
                || (answer.categoryCode() != null && v.categoryCode() == null)
                || (answer.projectId() != null && v.projectId() == null);
        if (dropped) {
            assertThat(v.confidence()).isLessThanOrEqualTo(ClassifierAnswerValidator.CAP);
        }
    }

    @Property(tries = 100)
    void validationIsIdempotent(@ForAll("answers") ClassifierAnswer answer,
            @ForAll("directions") String direction) {
        ClassificationInput in = input(direction);
        ClassifierAnswer once = ClassifierAnswerValidator.validate(answer, in);
        ClassifierAnswer twice = ClassifierAnswerValidator.validate(once, in);
        assertThat(twice).isEqualTo(once);
    }

    @Provide
    Arbitrary<String> directions() {
        return Arbitraries.of("INCOME", "EXPENSE");
    }
}
