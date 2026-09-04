package com.foremen.dao.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OtpTokenEntity field defaults.
 * Validates: Requirements 1.4, 1.5
 */
class OtpTokenEntityTest {

    @Test
    void usedDefaultsToFalse() {
        assertThat(new OtpTokenEntity().isUsed()).isFalse();
    }

    @Test
    void attemptsDefaultsToZero() {
        assertThat(new OtpTokenEntity().getAttempts()).isZero();
    }
}
