package com.foremen.service.dashboard.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 2.4.5: every resource code seeded by the Liquibase changesets is either required by a
 * registered dataset or listed (with a justification) in {@link DatasetCoverage}.
 *
 * <p>Liquibase is disabled in the integration profile, so the seeded codes are read from the
 * changeset files ({@code <insert tableName="resources">} blocks).
 *
 * <p><b>Validates: Requirements 6.2, 6.2a, 6.2e</b>
 */
class DatasetCoverageTest extends AbstractDatasetIntegrationTest {

    private static final Pattern INSERT = Pattern.compile(
            "<insert\\s+tableName=\"resources\"\\s*>(.*?)</insert>", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("name=\"code\"\\s+value=\"([^\"]+)\"");

    @Autowired
    private DatasetRegistry registry;

    @Test
    @DisplayName("every seeded resource is covered by a dataset or explicitly excluded")
    void everyResourceCoveredOrExcluded() throws IOException {
        Set<String> seeded = seededResourceCodes();
        assertThat(seeded).as("seeded resources parsed from changesets").hasSizeGreaterThan(40);

        Set<String> covered = registry.all().stream()
                .flatMap(d -> d.requiredResources().stream()).collect(Collectors.toSet());
        Set<String> excluded = DatasetCoverage.excludedCodes();

        Set<String> gaps = new TreeSet<>(seeded);
        gaps.removeAll(covered);
        gaps.removeAll(excluded);
        assertThat(gaps).as("resources with neither dataset nor exclusion").isEmpty();
    }

    @Test
    @DisplayName("exclusions are real, not covered by a dataset, and justified")
    void exclusionsAreConsistent() throws IOException {
        Set<String> seeded = seededResourceCodes();
        Set<String> covered = registry.all().stream()
                .flatMap(d -> d.requiredResources().stream()).collect(Collectors.toSet());
        DatasetCoverage.EXCLUDED.forEach((code, why) -> {
            assertThat(seeded).as("excluded code %s must be a seeded resource", code).contains(code);
            assertThat(covered).as("excluded code %s must not have a dataset", code).doesNotContain(code);
            assertThat(why).as("justification of %s", code).isNotBlank();
        });
    }

    @Test
    @DisplayName("every dataset requires only seeded resources")
    void datasetsRequireSeededResources() throws IOException {
        Set<String> seeded = seededResourceCodes();
        registry.all().forEach(d -> assertThat(seeded)
                .as("resources of dataset %s", d.code()).containsAll(d.requiredResources()));
    }

    static Set<String> seededResourceCodes() throws IOException {
        Path dir = Stream.of(Path.of("database_files/changesets"), Path.of("foremen-backend/database_files/changesets"))
                .filter(Files::isDirectory).findFirst().orElseThrow();
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                Matcher insert = INSERT.matcher(Files.readString(f));
                while (insert.find()) {
                    Matcher code = CODE.matcher(insert.group(1));
                    if (code.find()) {
                        codes.add(code.group(1));
                    }
                }
            }
        }
        return codes;
    }
}
