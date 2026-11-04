package com.foremen.service.bank.detect;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-07-04 (Requirement 15.1, 15.7) — negation of {@link BankAiConfiguredCondition}; wires
 * {@link DisabledBankTransactionClassifier}.
 */
public class BankAiNotConfiguredCondition implements Condition {

    private final BankAiConfiguredCondition delegate = new BankAiConfiguredCondition();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !delegate.matches(context, metadata);
    }
}
