package com.foremen.dao.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example tests for {@link InviteTokenEntity} defaults and packaging.
 *
 * <ul>
 *   <li>A freshly constructed {@link InviteTokenEntity} defaults {@code used} to
 *       {@code false} before it is explicitly set (Requirement 1.5).</li>
 *   <li>{@link InviteTokenEntity} resides in the {@code com.foremen.dao.model}
 *       package (Requirement 1.7).</li>
 * </ul>
 */
@DisplayName("InviteTokenEntity defaults")
class InviteTokenEntityDefaultsTest {

    // --- Requirement 1.5: new InviteTokenEntity defaults used to false ---

    @Test
    @DisplayName("a new InviteTokenEntity has used == false by default")
    void newInviteTokenEntityDefaultsUsedToFalse() {
        assertThat(new InviteTokenEntity().isUsed()).isFalse();
    }

    // --- Requirement 1.7: InviteTokenEntity resides in com.foremen.dao.model ---

    @Test
    @DisplayName("InviteTokenEntity resides in package com.foremen.dao.model")
    void inviteTokenEntityResidesInDaoModelPackage() {
        assertThat(InviteTokenEntity.class.getPackageName()).isEqualTo("com.foremen.dao.model");
    }
}
