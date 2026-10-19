package com.foremen.service.payment.provider;

import com.foremen.service.payment.dto.InitiatePayment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FOR-06-07 (Requirements 7.2, 7.5, 14.1; design §Components {@code Provider ports + No-Op stubs} /
 * key decision 7): the <b>default</b> {@link OnlinePaymentProvider}, active when no przelewy24
 * credentials are configured. It succeeds-without-effect, so the whole payment subsystem runs
 * unchanged until FOR-12 supplies credentials — exactly the FOR-05-08 disabled-provider pattern.
 *
 * <p>It is selected by configuration — active when {@code foremen.payments.online.provider=none}
 * and also when the property is <b>absent</b> ({@code matchIfMissing = true}), so a plain default
 * configuration runs with the No-Op provider. A concrete live provider replaces it later by setting
 * {@code foremen.payments.online.provider} to another value (and contributing its own
 * {@code @ConditionalOnProperty} bean), pluggable via FOR-12 / config without redesign.
 *
 * <p>Behaviour (Requirements 7.2, 7.5):
 *
 * <ul>
 *   <li>{@link #initiate} returns the {@linkplain OnlineInitiateResult#disabled() disabled} result —
 *       no external call, no throw, nothing marked {@code CONFIRMED}.</li>
 *   <li>{@link #verifySignature} returns {@code false} — no live webhook is accepted.</li>
 *   <li>{@link #parse} is never reached under the No-Op provider (a webhook is rejected by the
 *       unverified-signature check first); it throws {@link IllegalStateException} to make the
 *       disabled state explicit if ever invoked.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name = "foremen.payments.online.provider",
        havingValue = "none",
        matchIfMissing = true)
public class NoOpOnlinePaymentProvider implements OnlinePaymentProvider {

    @Override
    public OnlineInitiateResult initiate(InitiatePayment req) {
        // Disabled: no external call, no throw, nothing CONFIRMED (Requirement 7.2).
        return OnlineInitiateResult.disabled();
    }

    @Override
    public boolean verifySignature(RawWebhook raw) {
        // No credentials -> no live webhook is accepted (Requirement 7.5).
        return false;
    }

    @Override
    public OnlineWebhookEvent parse(RawWebhook raw) {
        // Unreachable under the No-Op provider: verifySignature() rejects every webhook first, so
        // the webhook endpoint never parses. Fail loudly if the contract is ever violated.
        throw new IllegalStateException(
                "NoOpOnlinePaymentProvider.parse must not be called: no online payment provider is"
                        + " configured");
    }
}
