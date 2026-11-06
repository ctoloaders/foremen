package com.foremen.service.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;

/**
 * Shared factory for {@link Client} (Gemini) instances and Google credential resolution, extracted
 * from {@code GoogleOcrService} so OCR and the rooms autoimport build clients identically.
 *
 * <p>A non-blank {@code apiKey} selects the Gemini Developer API backend; otherwise the Vertex
 * backend is used with {@code project}/{@code location} and credentials resolved from an explicit
 * path/location or Application Default Credentials.
 */
public final class GeminiClientFactory {

    private GeminiClientFactory() {
    }

    /**
     * Build a Gemini client.
     *
     * @throws UncheckedIOException when explicit credentials cannot be loaded
     */
    public static Client build(String apiKey, String project, String location,
            String credentialsPath, String credentialsLocation) {
        Client.Builder builder = Client.builder();
        if (apiKey != null && !apiKey.isBlank()) {
            // Gemini Developer API backend, keyed.
            builder.apiKey(apiKey.strip());
        } else {
            // Gemini Enterprise Agent Platform (Vertex) backend via ADC + project/location.
            builder.vertexAI(true);
            if (project != null && !project.isBlank()) {
                builder.project(project.strip());
            }
            if (location != null && !location.isBlank()) {
                builder.location(location.strip());
            }
            GoogleCredentials credentials = resolveCredentials(credentialsPath, credentialsLocation);
            if (credentials != null) {
                builder.credentials(credentials);
            }
        }
        return builder.build();
    }

    /**
     * Resolve {@link GoogleCredentials} from an explicit {@code path}/{@code location}, falling back
     * to Application Default Credentials (returns {@code null}) when neither is set or the
     * {@code application-default} sentinel is used.
     *
     * @throws UncheckedIOException when the credentials resource cannot be read
     */
    public static GoogleCredentials resolveCredentials(String path, String location) {
        String effective = path != null && !path.isBlank() ? path : location;
        if (effective == null || effective.isBlank()
                || "application-default".equalsIgnoreCase(effective.strip())) {
            return null; // fall back to Application Default Credentials
        }
        Resource resource = new DefaultResourceLoader().getResource(effective.strip());
        try (InputStream in = resource.getInputStream()) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to load Google credentials from '" + effective + "': " + e.getMessage(), e);
        }
    }
}
