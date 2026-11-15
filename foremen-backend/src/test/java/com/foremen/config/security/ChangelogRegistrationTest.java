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
 * {@code 017-seed-users-resource.xml} changeset exactly once and after the changesets it depends on.
 *
 * <p>Reads {@code database_files/changelog.xml} from the module directory and scans the ordered
 * {@code <include file="..."/>} entries (Requirement 11.5). Later changesets may follow it.
 */
@DisplayName("changelog.xml include registration")
class ChangelogRegistrationTest {

    private static final Pattern INCLUDE_FILE =
            Pattern.compile("<include\\s+file=\"([^\"]+)\"\\s*/>");

    @Test
    @DisplayName("registers 017-seed-users-resource.xml exactly once, after the changesets it depends on")
    void registers017SeedUsersResourceOnceAfterItsDependencies() throws IOException {
        List<String> includes = readIncludeFiles();
        assertThat(includes)
                .as("changelog.xml must declare at least one <include> entry")
                .isNotEmpty();
        assertThat(includes)
                .as("017-seed-users-resource.xml must be registered exactly once")
                .filteredOn(f -> f.endsWith("017-seed-users-resource.xml"))
                .hasSize(1);
        int seed = indexOf(includes, "017-seed-users-resource.xml");
        // Dependencies: the users table, the resources / operations / roles / role-resources tables and the
        // baseline permission seeds the USERS grants are inserted into.
        for (String dependency : List.of(
                "002-create-resources.xml", "003-create-operations.xml", "004-create-roles.xml",
                "005-create-role-resources.xml", "007-seed-permissions.xml", "008-create-users.xml",
                "015-seed-project-members-resource.xml")) {
            int dep = indexOf(includes, dependency);
            assertThat(dep)
                    .as("%s must be registered before 017-seed-users-resource.xml", dependency)
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(seed);
        }
    }

    private static int indexOf(List<String> includes, String suffix) {
        for (int i = 0; i < includes.size(); i++) {
            if (includes.get(i).endsWith(suffix)) {
                return i;
            }
        }
        return -1;
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
