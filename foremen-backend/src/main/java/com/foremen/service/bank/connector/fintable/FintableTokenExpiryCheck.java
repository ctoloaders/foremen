package com.foremen.service.bank.connector.fintable;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.BankFeedProviderStateDao;
import com.foremen.dao.model.BankFeedProviderStateEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.notify.FintableTokenExpiringEvent;

/**
 * FOR-07-04a (Requirements 12.3–12.5; design §Token expiry notification) — the daily Fintable token
 * expiry check, called from {@code BankConsentSweep}. Each level (30 / 7 / 0) is sent at most once per
 * {@code token-expires-on} value: the marker lives in {@code bank_feed_provider_state} and is claimed
 * in its own {@code REQUIRES_NEW} transaction before the event is published. A changed configured date
 * resets the marker.
 */
public class FintableTokenExpiryCheck {

    private static final Logger log = LoggerFactory.getLogger(FintableTokenExpiryCheck.class);

    private final FintableProperties properties;
    private final BankFeedProviderStateDao stateDao;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate claimTx;

    public FintableTokenExpiryCheck(FintableProperties properties,
                                    BankFeedProviderStateDao stateDao,
                                    ApplicationEventPublisher publisher,
                                    PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.stateDao = stateDao;
        this.publisher = publisher;
        this.claimTx = new TransactionTemplate(transactionManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One check for {@code today}; a no-op when Fintable is off or {@code token-expires-on} is blank. */
    public void run(LocalDate today) {
        LocalDate expiresOn = properties.tokenExpiresOn();
        if (!properties.enabled() || expiresOn == null) {
            return;
        }
        int level = levelFor(ChronoUnit.DAYS.between(today, expiresOn), properties.tokenWarnDays());
        Optional<Integer> claimed = claimTx.execute(status -> claim(expiresOn, level));
        if (claimed != null && claimed.isPresent()) {
            publisher.publishEvent(new FintableTokenExpiringEvent(claimed.get(), expiresOn));
        }
    }

    /** The threshold level for {@code days} left, or {@code -1} when outside the warning window. */
    static int levelFor(long days, int warnDays) {
        if (days <= 0) {
            return 0;
        }
        if (days <= 7) {
            return 7;
        }
        return days <= warnDays ? 30 : -1;
    }

    private Optional<Integer> claim(LocalDate expiresOn, int level) {
        BankFeedProviderStateEntity state = stateDao.findById(FeedProvider.FINTABLE).orElse(null);
        if (state == null) {
            state = new BankFeedProviderStateEntity();
            state.setProvider(FeedProvider.FINTABLE);
            state.setTokenExpiresOn(expiresOn);
        } else if (!expiresOn.equals(state.getTokenExpiresOn())) {
            state.setTokenExpiresOn(expiresOn);
            state.setTokenNotifiedLevel(null);
        }
        Integer notified = state.getTokenNotifiedLevel();
        boolean due = level >= 0 && (notified == null || level < notified);
        if (due) {
            state.setTokenNotifiedLevel(level);
        }
        stateDao.save(state);
        return due ? Optional.of(level) : Optional.empty();
    }

    /** Requirement 12.5: one WARN at start-up when Fintable is enabled but the expiry date is blank. */
    public void warnIfExpiryBlank() {
        if (properties.enabled() && properties.tokenExpiresOn() == null) {
            log.warn("foremen.bank-feed.fintable.token-expires-on is not set; "
                    + "the Fintable token expiry notification is disabled");
        }
    }
}
