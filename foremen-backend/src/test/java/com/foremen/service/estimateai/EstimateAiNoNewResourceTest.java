package com.foremen.service.estimateai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FOR-05-04 task 1.3 (Requirement 17.1; design B1/B8): the spec adds no managed entity and no ABAC
 * resource. None of its changesets writes to the resources, role_resources or role_resource_operations
 * tables, and no seed-resource changeset belongs to it.
 */
@DisplayName("FOR-05-04: no new ABAC resource seeded")
class EstimateAiNoNewResourceTest {

    private static final Path CHANGESETS = Path.of("database_files", "changesets");

    /** Changeset file suffixes owned by this spec (tasks 1.1 and 2.1). */
    private static final List<String> SPEC_CHANGESETS = List.of(
            "add-ai-instructions-to-work-catalog.xml",
            "extend-rooms-import-jobs-for-estimate.xml",
            "add-offers-ai-columns.xml",
            "create-offer-ai-chat-messages.xml",
            "add-estimate-line-room-qty-ai-meta.xml",
            "seed-ai-instructions-categories.xml");

    private static final Pattern PERMISSION_TABLES = Pattern.compile(
            "(?i)(tableName\\s*=\\s*\"(role_resource_operations|role_resources|resources)\""
                    + "|\\b(insert\\s+into|update|delete\\s+from)\\s+"
                    + "(role_resource_operations|role_resources|resources)\\b)");

    @Test
    @DisplayName("the spec's changesets never touch the permission tables")
    void specChangesetsDoNotSeedResources() throws IOException {
        List<Path> files;
        try (Stream<Path> all = Files.list(CHANGESETS)) {
            files = all.filter(p -> SPEC_CHANGESETS.stream()
                    .anyMatch(s -> p.getFileName().toString().endsWith(s))).toList();
        }
        assertThat(files.size()).as("spec changesets found: %s", files).isGreaterThanOrEqualTo(5);
        for (Path file : files) {
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(PERMISSION_TABLES.matcher(xml).find())
                    .as("%s must not write resources, role_resources or role_resource_operations", file)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no seed-resource changeset is dedicated to estimate AI or the AI chat")
    void noAiResourceSeedChangeset() throws IOException {
        try (Stream<Path> all = Files.list(CHANGESETS)) {
            List<String> names = all.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("seed") && n.contains("resource"))
                    .filter(n -> n.contains("estimate-ai") || n.contains("offer-ai") || n.contains("ai-chat")
                            || n.contains("rooms-import"))
                    .toList();
            assertThat(names).isEmpty();
        }
    }
}
