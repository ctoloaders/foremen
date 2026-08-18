package com.foremen.naming;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Hibernate's CamelCaseToUnderscoresNamingStrategy.
 * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5
 */
class NamingStrategyPropertyTest {

    private final CamelCaseToUnderscoresNamingStrategy strategy = new CamelCaseToUnderscoresNamingStrategy();

    private String applyStrategy(String camelCaseName) {
        Identifier identifier = Identifier.toIdentifier(camelCaseName);
        return strategy.toPhysicalColumnName(identifier, null).getText();
    }

    // --- Known field mappings (example-based property) ---

    /**
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5
     * Verifies exact mappings for BaseEntity fields.
     */
    @Property(tries = 1)
    void knownBaseEntityFieldMappings() {
        assertThat(applyStrategy("createdDate")).isEqualTo("created_date");
        assertThat(applyStrategy("createdBy")).isEqualTo("created_by");
        assertThat(applyStrategy("updatedDate")).isEqualTo("updated_date");
        assertThat(applyStrategy("updatedBy")).isEqualTo("updated_by");
        assertThat(applyStrategy("id")).isEqualTo("id");
    }

    // --- General camelCase property ---

    /**
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5
     * For randomly generated camelCase identifiers, the result should be all lowercase.
     */
    @Property(tries = 100)
    void resultIsAllLowercase(@ForAll("camelCaseIdentifiers") String camelCase) {
        String result = applyStrategy(camelCase);
        assertThat(result).isEqualTo(result.toLowerCase());
    }

    /**
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5
     * For randomly generated camelCase identifiers, the result should not contain uppercase letters.
     */
    @Property(tries = 100)
    void resultContainsNoUppercase(@ForAll("camelCaseIdentifiers") String camelCase) {
        String result = applyStrategy(camelCase);
        assertThat(result).doesNotMatch(".*[A-Z].*");
    }

    /**
     * Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5
     * Each uppercase letter in the input should produce an underscore + lowercase letter in output.
     */
    @Property(tries = 100)
    void uppercaseLettersProduceUnderscorePlusLowercase(@ForAll("camelCaseIdentifiers") String camelCase) {
        String result = applyStrategy(camelCase);

        // Count uppercase letters in input
        long uppercaseCount = camelCase.chars()
                .filter(Character::isUpperCase)
                .count();

        // Count underscores in result (each uppercase boundary adds one underscore)
        long underscoreCount = result.chars()
                .filter(ch -> ch == '_')
                .count();

        assertThat(underscoreCount).isEqualTo(uppercaseCount);
    }

    // --- Custom camelCase identifier generator ---

    @Provide
    Arbitrary<String> camelCaseIdentifiers() {
        // Start with a lowercase segment (1-5 lowercase letters)
        Arbitrary<String> firstSegment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(5);

        // Subsequent segments start with one uppercase letter followed by 1-4 lowercase letters
        Arbitrary<String> upperSegment = Arbitraries.of(
                        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
                        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
                        'U', 'V', 'W', 'X', 'Y', 'Z')
                .flatMap(upper -> Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(4)
                        .map(lower -> upper + lower));

        // Combine: first segment + 0 to 3 upper-starting segments
        return firstSegment.flatMap(first ->
                Arbitraries.integers().between(0, 3).flatMap(segmentCount ->
                        upperSegment.list().ofSize(segmentCount).map(segments -> {
                            StringBuilder sb = new StringBuilder(first);
                            for (String seg : segments) {
                                sb.append(seg);
                            }
                            return sb.toString();
                        })
                )
        );
    }
}
