package com.foremen.service.payment.provider;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FOR-06-07 (Requirements 8.2, 14.1; design §Components {@code Provider ports + No-Op stubs} / key
 * decision 7): the <b>default</b> {@link BankFeedProvider}, active when no mass-collect bank-feed
 * credentials are configured. It yields no transactions and never throws, so the rest of the system
 * runs unchanged until FOR-12 supplies credentials — exactly the FOR-05-08 disabled-provider
 * pattern.
 *
 * <p>It is selected by configuration — active when {@code foremen.payments.bankfeed.provider=none}
 * and also when the property is <b>absent</b> ({@code matchIfMissing = true}), so a plain default
 * configuration runs with the No-Op provider. A concrete live provider replaces it later by setting
 * {@code foremen.payments.bankfeed.provider} to another value (and contributing its own
 * {@code @ConditionalOnProperty} bean), pluggable via FOR-12 / config without redesign.
 */
@Component
@ConditionalOnProperty(
        name = "foremen.payments.bankfeed.provider",
        havingValue = "none",
        matchIfMissing = true)
public class NoOpBankFeedProvider implements BankFeedProvider {

    @Override
    public List<IncomingBankTxn> poll() {
        // Disabled: the feed yields no transactions and never throws (Requirement 8.2).
        return List.of();
    }
}
