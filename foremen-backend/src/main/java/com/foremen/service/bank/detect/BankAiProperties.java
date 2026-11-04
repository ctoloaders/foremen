package com.foremen.service.bank.detect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * FOR-07-04 (Requirement 15.1, 15.6, 24.6, design §"AI classifier") — the
 * {@code foremen.bank-ledger.ai} configuration block for the bank-transaction AI classifier.
 *
 * <p>Every value comes from configuration / environment (R24.6); the binding is lenient so the
 * application starts with the whole block absent (the classifier is then disabled).
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default {@code false}); selects
 *       {@link GeminiBankTransactionClassifier} vs {@link DisabledBankTransactionClassifier}.</li>
 *   <li>{@code model} — the Gemini model id.</li>
 *   <li>{@code project} / {@code location} — Vertex backend (ADC or {@code credentials-location}).</li>
 *   <li>{@code api-key} — optional Gemini Developer API key; when set, the keyed API is used
 *       instead of Vertex.</li>
 *   <li>{@code credentials-location} — optional service-account JSON resource
 *       ({@code file:} / {@code classpath:}); blank or {@code application-default} ⇒ ADC.</li>
 *   <li>{@code max-per-run} — AI calls per sync run (R15.6, default {@value #DEFAULT_MAX_PER_RUN}).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "foremen.bank-ledger.ai")
public record BankAiProperties(
        boolean enabled,
        String model,
        String project,
        String location,
        String apiKey,
        String credentialsLocation,
        Integer maxPerRun) {

    public static final int DEFAULT_MAX_PER_RUN = 200;

    @ConstructorBinding
    public BankAiProperties {
        if (maxPerRun == null || maxPerRun < 0) {
            maxPerRun = DEFAULT_MAX_PER_RUN;
        }
    }
}
