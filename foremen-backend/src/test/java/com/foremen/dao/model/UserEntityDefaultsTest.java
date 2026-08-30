package com.foremen.dao.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example tests for {@link UserEntity} / {@link UserStatus} defaults.
 *
 * <ul>
 *   <li>A freshly constructed {@link UserEntity} defaults its status to {@code INVITED}
 *       (Requirement 1.2).</li>
 *   <li>{@link UserStatus} declares exactly the three expected constants in order:
 *       {@code INVITED}, {@code ACTIVE}, {@code DEACTIVATED} (Requirement 1.3).</li>
 * </ul>
 */
@DisplayName("UserEntity / UserStatus defaults")
class UserEntityDefaultsTest {

    // --- Requirement 1.2: new UserEntity defaults to INVITED ---

    @Test
    @DisplayName("a new UserEntity has status INVITED by default")
    void newUserEntityDefaultsToInvited() {
        UserEntity user = new UserEntity();

        assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);
    }

    // --- Requirement 1.3: UserStatus values are exactly the three expected constants ---

    @Test
    @DisplayName("UserStatus.values() equals the three expected constants in order")
    void userStatusValuesAreTheThreeExpectedConstants() {
        assertThat(UserStatus.values())
                .containsExactly(UserStatus.INVITED, UserStatus.ACTIVE, UserStatus.DEACTIVATED);
    }
}
