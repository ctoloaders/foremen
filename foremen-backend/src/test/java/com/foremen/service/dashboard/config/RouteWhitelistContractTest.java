package com.foremen.service.dashboard.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Contract test (FOR-10 task 8.1, Requirement 10.2): the backend {@link RouteWhitelist} must equal the
 * frontend-exported {@code foremen-frontend/src/config/route-whitelist.json}. Skipped when the
 * frontend file is not available (e.g. CI checkout without the frontend repo).
 */
class RouteWhitelistContractTest {

    private static final String FRONTEND_EXPORT = "foremen-frontend/src/config/route-whitelist.json";

    @Test
    void backendWhitelistMatchesFrontendExport() throws IOException {
        Path file = locateExport();
        assumeTrue(file != null, "frontend route-whitelist.json not found; skipping contract test");

        String[] routes = new ObjectMapper().readValue(Files.readString(file), String[].class);
        Set<String> frontend = new TreeSet<>(Set.of(routes));

        assertThat(routes).as("frontend export has no duplicates").hasSize(frontend.size());
        assertThat(new TreeSet<>(RouteWhitelist.ROUTES))
                .as("RouteWhitelist.ROUTES vs " + FRONTEND_EXPORT)
                .isEqualTo(frontend);
    }

    /** Walks up from the working directory (foremen-backend or repo root) looking for the export. */
    private static Path locateExport() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(FRONTEND_EXPORT);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
