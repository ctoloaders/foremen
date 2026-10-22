package com.foremen.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal springdoc OpenAPI metadata for the Foremen REST API (FOR-08-ai-assistant, Req 2.1, 2.5).
 *
 * <p>springdoc ({@code springdoc-openapi-starter-webmvc-ui}) already emits {@code /v3/api-docs} and
 * the Swagger UI. This configuration only attaches a stable title/info block and declares the
 * {@code bearer-jwt} HTTP security scheme so the generated document describes the real auth model.
 * It adds <b>no</b> endpoints and changes <b>no</b> behaviour.
 *
 * <h2>Exposure stance (D3, Req 2.5)</h2>
 * {@code /v3/api-docs} and the Swagger UI are <b>not</b> on the public allowlist in
 * {@link com.foremen.config.security.SecurityConfig} (only {@code /api/auth/**},
 * {@code /api/signatures/callback}, and {@code /api/payments/webhook/**} are permit-listed), so they
 * fall under the {@code anyRequest().authenticated()} catch-all and sit <b>behind authentication</b>.
 * The curated bot toolset is captured by an authenticated ADMIN request — the endpoint is
 * deliberately <b>not</b> permit-listed, so only the generated API <i>description</i> is ever
 * reachable, never Foremen data.
 *
 * <h2>Capture procedure</h2>
 * The repeatable procedure for (re)capturing and curating the document lives in
 * {@code .kiro/specs/FOR-08-ai-estimate/openapi/README.md} (mirrored into {@code bot-configuration.md}).
 * In short: boot the Docker stack, authenticate as ADMIN, {@code GET /v3/api-docs} and save the
 * response, then curate it down to the bot tool operations.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Foremen API",
                version = "v1",
                description = "REST API of the Foremen admin panel. The curated subset of these "
                        + "operations is imported into the loaders-bot toolset (see FOR-08-ai-assistant)."
        )
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Foremen access token (the user's own JWT). Every tool call runs with the "
                + "signed-in user's token and is subject to Foremen's ABAC matrix and project scope."
)
public class OpenApiConfig {
}
