package com.foremen.config.mail;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: FOR-03-02-user-invitation, Property 19: Invite TTL validation predicate
 *
 * For all integers outside the range 1 to 8760 inclusive, {@link InviteProperties#isTtlHoursInRange()}
 * SHALL return false; for all integers within that range it SHALL return true.
 *
 * Validates: Requirements 7.6
 */
@Tag("Feature: FOR-03-02-user-invitation, Property 19: Invite TTL validation predicate")
class InvitePropertiesPropertyTest {

    private static boolean predicate(Integer ttlHours) {
        return new InviteProperties(ttlHours).isTtlHoursInRange();
    }

    // Feature: FOR-03-02-user-invitation, Property 19: in-range TTL accepted
    // For all integers within [1, 8760], the predicate returns true.
    @Property(tries = 100)
    void acceptsTtlWithinRange(@ForAll @IntRange(min = 1, max = 8760) int ttlHours) {
        assertThat(predicate(ttlHours))
                .as("expected in-range TTL to be accepted: %d", ttlHours)
                .isTrue();
    }

    // Feature: FOR-03-02-user-invitation, Property 19: TTL below range rejected
    // For all integers <= 0, the predicate returns false.
    @Property(tries = 100)
    void rejectsTtlBelowRange(@ForAll @IntRange(min = Integer.MIN_VALUE + 1, max = 0) int ttlHours) {
        assertThat(predicate(ttlHours))
                .as("expected below-range TTL to be rejected: %d", ttlHours)
                .isFalse();
    }

    // Feature: FOR-03-02-user-invitation, Property 19: TTL above range rejected
    // For all integers >= 8761, the predicate returns false.
    @Property(tries = 100)
    void rejectsTtlAboveRange(@ForAll @IntRange(min = 8761, max = Integer.MAX_VALUE) int ttlHours) {
        assertThat(predicate(ttlHours))
                .as("expected above-range TTL to be rejected: %d", ttlHours)
                .isFalse();
    }
}
