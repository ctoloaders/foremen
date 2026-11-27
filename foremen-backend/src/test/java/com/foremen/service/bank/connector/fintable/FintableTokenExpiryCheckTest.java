package com.foremen.service.bank.connector.fintable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import com.foremen.dao.BankFeedProviderStateDao;
import com.foremen.dao.model.BankFeedProviderStateEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.service.bank.notify.FintableTokenExpiringEvent;

/**
 * FOR-07-04a task 2.5 — {@link FintableTokenExpiryCheck}: thresholds 30 / 7 / 0, once per level,
 * reset on a new {@code token-expires-on}, blank date, disabled provider (Requirements 12.3–12.5).
 */
class FintableTokenExpiryCheckTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 6, 30);

    private final AtomicReference<BankFeedProviderStateEntity> stored = new AtomicReference<>();
    private final List<Object> events = new ArrayList<>();
    private BankFeedProviderStateDao dao;
    private ApplicationEventPublisher publisher;
    private PlatformTransactionManager tm;

    @BeforeEach
    void setUp() {
        dao = mock(BankFeedProviderStateDao.class);
        when(dao.findById(FeedProvider.FINTABLE)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(dao.save(any(BankFeedProviderStateEntity.class))).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        publisher = events::add;
        tm = mock(PlatformTransactionManager.class);
    }

    private FintableTokenExpiryCheck check(boolean enabled, LocalDate expiresOn, Integer warnDays) {
        FintableProperties p = new FintableProperties(enabled, null, false, "t", expiresOn, null,
                null, null, null, null, null, null, null, null, warnDays);
        return new FintableTokenExpiryCheck(p, dao, publisher, tm);
    }

    private List<Integer> levels() {
        return events.stream().map(e -> ((FintableTokenExpiringEvent) e).level()).toList();
    }

    @Test
    @DisplayName("outside the warning window nothing is sent")
    void outsideWindow() {
        check(true, EXPIRY, null).run(EXPIRY.minusDays(31));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("30 / 7 / 0 thresholds fire in order, each once")
    void thresholdsOncePerLevel() {
        FintableTokenExpiryCheck c = check(true, EXPIRY, null);
        c.run(EXPIRY.minusDays(30));
        c.run(EXPIRY.minusDays(29));
        c.run(EXPIRY.minusDays(8));
        assertThat(levels()).containsExactly(30);
        c.run(EXPIRY.minusDays(7));
        c.run(EXPIRY.minusDays(1));
        assertThat(levels()).containsExactly(30, 7);
        c.run(EXPIRY);
        c.run(EXPIRY.plusDays(3));
        assertThat(levels()).containsExactly(30, 7, 0);
        assertThat(stored.get().getTokenNotifiedLevel()).isZero();
        assertThat(((FintableTokenExpiringEvent) events.get(0)).expiresOn()).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("a lower level is sent when higher ones were skipped; a higher one is not re-sent")
    void skippedLevels() {
        FintableTokenExpiryCheck c = check(true, EXPIRY, null);
        c.run(EXPIRY.minusDays(3));
        c.run(EXPIRY.minusDays(20));
        assertThat(levels()).containsExactly(7);
    }

    @Test
    @DisplayName("custom token-warn-days widens the first window")
    void customWarnDays() {
        check(true, EXPIRY, 45).run(EXPIRY.minusDays(40));
        assertThat(levels()).containsExactly(30);
    }

    @Test
    @DisplayName("a new token-expires-on resets the markers")
    void resetOnNewDate() {
        check(true, EXPIRY, null).run(EXPIRY);
        assertThat(levels()).containsExactly(0);

        LocalDate next = EXPIRY.plusYears(1);
        FintableTokenExpiryCheck renewed = check(true, next, null);
        renewed.run(next.minusDays(10));
        assertThat(stored.get().getTokenExpiresOn()).isEqualTo(next);
        assertThat(levels()).containsExactly(0, 30);
    }

    @Test
    @DisplayName("blank expiry date does nothing")
    void blankDate() {
        check(true, null, null).run(EXPIRY);
        assertThat(events).isEmpty();
        assertThat(stored.get()).isNull();
    }

    @Test
    @DisplayName("disabled provider does nothing")
    void disabledProvider() {
        check(false, EXPIRY, null).run(EXPIRY);
        assertThat(events).isEmpty();
        assertThat(stored.get()).isNull();
    }

    @Test
    @DisplayName("levelFor maps days left to thresholds")
    void levelFor() {
        assertThat(FintableTokenExpiryCheck.levelFor(-5, 30)).isZero();
        assertThat(FintableTokenExpiryCheck.levelFor(0, 30)).isZero();
        assertThat(FintableTokenExpiryCheck.levelFor(1, 30)).isEqualTo(7);
        assertThat(FintableTokenExpiryCheck.levelFor(7, 30)).isEqualTo(7);
        assertThat(FintableTokenExpiryCheck.levelFor(8, 30)).isEqualTo(30);
        assertThat(FintableTokenExpiryCheck.levelFor(30, 30)).isEqualTo(30);
        assertThat(FintableTokenExpiryCheck.levelFor(31, 30)).isEqualTo(-1);
    }
}
