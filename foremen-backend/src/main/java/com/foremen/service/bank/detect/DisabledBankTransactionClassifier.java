package com.foremen.service.bank.detect;

import java.util.Optional;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/**
 * FOR-07-04 (Requirement 15.1, 15.7) — the No-Op classifier used when
 * {@code foremen.bank-ledger.ai.enabled} is not {@code true}. Never makes an AI call.
 */
@Service
@Conditional(BankAiNotConfiguredCondition.class)
public class DisabledBankTransactionClassifier implements BankTransactionClassifier {

    @Override
    public Optional<ClassifierAnswer> classify(ClassificationInput input) {
        return Optional.empty();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
