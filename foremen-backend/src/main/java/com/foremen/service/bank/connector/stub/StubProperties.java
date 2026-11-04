package com.foremen.service.bank.connector.stub;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FOR-07-04 (Requirement 4.4): configuration of the dev/test {@code STUB} bank-feed connector.
 *
 * <p>Bound from {@code foremen.bank-feed.stub.*}. The connector beans exist only when
 * {@code enabled = true} (default false; never in production).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "foremen.bank-feed.stub")
public class StubProperties {

    /** Master switch ({@code FOREMEN_BANK_STUB_ENABLED}); the stub beans exist only when true. */
    private boolean enabled = false;

    /** Posted transactions returned per {@code fetchTransactions} page (cursor pagination). */
    private int pageSize = 100;

    /** Consent lifetime granted on (re)authorization when the seed does not set {@code consentExpiresAt}. */
    private int consentDays = 90;
}
