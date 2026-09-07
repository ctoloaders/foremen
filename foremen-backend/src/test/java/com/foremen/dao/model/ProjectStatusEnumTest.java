package com.foremen.dao.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ProjectStatus} enum.
 * Validates: Requirements 1.3
 */
class ProjectStatusEnumTest {

    @Test
    @DisplayName("values() returns exactly the five statuses in declaration order")
    void valuesAreExactlyTheFiveStatusesInDeclarationOrder() {
        assertThat(ProjectStatus.values()).containsExactly(
                ProjectStatus.DRAFT,
                ProjectStatus.ACTIVE,
                ProjectStatus.ON_HOLD,
                ProjectStatus.COMPLETED,
                ProjectStatus.CANCELLED
        );
    }

    @Test
    @DisplayName("enum defines no values beyond the five expected ones")
    void definesExactlyFiveValues() {
        assertThat(ProjectStatus.values()).hasSize(5);
    }
}
