package com.foremen.service.bank.detect;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FOR-07-04 (Requirement 15.1) — matches when {@code foremen.bank-ledger.ai.enabled=true}; wires
 * {@link GeminiBankTransactionClassifier}. {@link BankAiNotConfiguredCondition} is its negation, so
 * exactly one {@link BankTransactionClassifier} bean exists in any configuration.
 */
public class BankAiConfiguredCondition implements Condition {

    static final String PROPERTY = "foremen.bank-ledger.ai.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty(PROPERTY, Boolean.class, Boolean.FALSE);
    }
}
