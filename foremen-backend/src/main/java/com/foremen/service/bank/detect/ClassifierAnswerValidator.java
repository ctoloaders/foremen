package com.foremen.service.bank.detect;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * FOR-07-04 (Requirement 15.4, design Property 5) — pure validation of a parsed
 * {@link ClassifierAnswer} against the {@link ClassificationInput} that produced it.
 *
 * <ul>
 *   <li>{@code kind} must be allowed for the direction (EXPENSE: WORKER_PAYOUT, PROCUREMENT,
 *       OTHER_EXPENSE, INTERNAL_TRANSFER; INCOME: CLIENT_PAYMENT, OTHER_INCOME,
 *       INTERNAL_TRANSFER);</li>
 *   <li>{@code candidateRef} must be one of the supplied candidates;</li>
 *   <li>{@code categoryCode} must be one of the supplied (active) categories;</li>
 *   <li>{@code projectId} must be one of the supplied (active) projects.</li>
 * </ul>
 * Any failing field is dropped ({@code null}) and the confidence is capped at {@link #CAP}. The
 * confidence is always clamped to {@code [0,1]} and the rationale trimmed to 200 characters.
 */
public final class ClassifierAnswerValidator {

    /** Confidence cap applied when any field was dropped. */
    public static final double CAP = 0.3;

    /** Maximum rationale length (R15.3). */
    public static final int MAX_RATIONALE = 200;

    public static final Set<String> EXPENSE_KINDS =
            Set.of("WORKER_PAYOUT", "PROCUREMENT", "OTHER_EXPENSE", "INTERNAL_TRANSFER");
    public static final Set<String> INCOME_KINDS =
            Set.of("CLIENT_PAYMENT", "OTHER_INCOME", "INTERNAL_TRANSFER");

    private ClassifierAnswerValidator() {
    }

    /** Kinds allowed for a direction; empty for an unknown direction. */
    public static Set<String> allowedKinds(String direction) {
        if (ClassificationInput.EXPENSE.equalsIgnoreCase(direction)) {
            return EXPENSE_KINDS;
        }
        if (ClassificationInput.INCOME.equalsIgnoreCase(direction)) {
            return INCOME_KINDS;
        }
        return Set.of();
    }

    /**
     * Validate {@code answer} against {@code input}. Returns {@code null} only when
     * {@code answer} is {@code null}.
     */
    public static ClassifierAnswer validate(ClassifierAnswer answer, ClassificationInput input) {
        if (answer == null) {
            return null;
        }
        Objects.requireNonNull(input, "input");
        boolean dropped = false;

        String kind = null;
        if (answer.kind() != null) {
            String k = answer.kind().strip().toUpperCase(Locale.ROOT);
            if (allowedKinds(input.direction()).contains(k)) {
                kind = k;
            } else {
                dropped = true;
            }
        } else {
            dropped = true;
        }

        String candidateRef = null;
        if (answer.candidateRef() != null) {
            String ref = answer.candidateRef().strip();
            boolean known = input.candidates().stream()
                    .anyMatch(c -> c.candidateRef() != null && c.candidateRef().equals(ref));
            if (known) {
                candidateRef = ref;
            } else {
                dropped = true;
            }
        }

        String categoryCode = null;
        if (answer.categoryCode() != null) {
            String code = answer.categoryCode().strip();
            categoryCode = input.categories().stream()
                    .map(ClassificationInput.Category::code)
                    .filter(c -> c != null && c.equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
            if (categoryCode == null) {
                dropped = true;
            }
        }

        Long projectId = null;
        if (answer.projectId() != null) {
            boolean known = input.projects().stream()
                    .anyMatch(p -> answer.projectId().equals(p.id()));
            if (known) {
                projectId = answer.projectId();
            } else {
                dropped = true;
            }
        }

        double confidence = clamp01(answer.confidence());
        if (dropped) {
            confidence = Math.min(confidence, CAP);
        }

        return new ClassifierAnswer(kind, candidateRef, categoryCode, projectId, confidence,
                trimRationale(answer.rationale()));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }

    private static String trimRationale(String rationale) {
        if (rationale == null) {
            return null;
        }
        String r = rationale.strip();
        if (r.isEmpty()) {
            return null;
        }
        if (r.length() <= MAX_RATIONALE) {
            return r;
        }
        int end = MAX_RATIONALE;
        if (Character.isHighSurrogate(r.charAt(end - 1))) {
            end--;
        }
        return r.substring(0, end);
    }
}
