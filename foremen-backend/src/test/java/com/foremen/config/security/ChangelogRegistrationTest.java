package com.foremen.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural verification that the Liquibase master changelog registers the
 * {@code 017-seed-users-resource.xml} changeset last in sequence.
 *
 * <p>Reads {@code database_files/changelog.xml} from the module directory, scans
 * the ordered {@code <include file="..."/>} entries, and asserts the final one
 * references the USERS-resource seed changeset (Requirement 11.5).
 */
@DisplayName("changelog.xml include registration")
class ChangelogRegistrationTest {

    private static final Pattern INCLUDE_FILE =
            Pattern.compile("<include\\s+file=\"([^\"]+)\"\\s*/>");

    @Test
    @DisplayName("registers 017-seed-users-resource.xml last in the include sequence")
    void registers017SeedUsersResourceLast() throws IOException {
        List<String> includes = readIncludeFiles();

        assertThat(includes)
                .as("changelog.xml must declare at least one <include> entry")
                .isNotEmpty();

        String last = includes.get(includes.size() - 1);
        assertThat(last)
                .as("last <include> in changelog.xml must reference 017-seed-users-resource.xml")
                .endsWith("017-seed-users-resource.xml");

        assertThat(includes)
                .as("017-seed-users-resource.xml must be registered exactly once")
                .filteredOn(f -> f.endsWith("017-seed-users-resource.xml"))
                .hasSize(1);
    }

    private List<String> readIncludeFiles() throws IOException {
        File changelog = locateChangelog();
        String content = Files.readString(changelog.toPath(), StandardCharsets.UTF_8);

        List<String> includes = new ArrayList<>();
        Matcher matcher = INCLUDE_FILE.matcher(content);
        while (matcher.find()) {
            includes.add(matcher.group(1));
        }
        return includes;
    }

    private File locateChangelog() {
        // Gradle runs tests with the module directory as the working directory.
        File direct = new File("database_files/changelog.xml");
        if (direct.isFile()) {
            return direct;
        }
        // Fall back to walking up from the working directory in case tests run
        // from a different current directory (e.g. the repository root).
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "foremen-backend/database_files/changelog.xml");
            if (candidate.isFile()) {
                return candidate;
            }
            candidate = new File(dir, "database_files/changelog.xml");
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(
                "Could not locate database_files/changelog.xml relative to "
                        + new File("").getAbsolutePath());
    }
}
