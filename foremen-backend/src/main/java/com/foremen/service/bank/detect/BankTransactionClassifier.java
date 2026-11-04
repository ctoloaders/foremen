package com.foremen.service.bank.detect;

import java.util.Optional;

/**
 * FOR-07-04 (Requirement 15.1) — the AI classifier seam used by {@code DetectionService}.
 *
 * <p>Two implementations exist: the Gemini one (enabled by {@code foremen.bank-ledger.ai.enabled})
 * and a disabled No-Op fallback. Implementations return the <em>validated</em> answer (see
 * {@link ClassifierAnswerValidator}); the result is always stored as an {@code AI} suggestion and
 * never auto-applied (R15.5).
 */
public interface BankTransactionClassifier {

    /**
     * Classify one transaction.
     *
     * @param input the prompt data (R15.2 fields only)
     * @return the validated answer, or empty when the classifier is disabled, the call failed or
     *         the response could not be parsed
     */
    Optional<ClassifierAnswer> classify(ClassificationInput input);

    /** Whether real AI calls are made; {@code false} for the disabled fallback (R15.7). */
    default boolean isEnabled() {
        return true;
    }
}
