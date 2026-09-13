package com.foremen.qa.support;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;

/**
 * Readiness gate for the live Docker stack (Requirements 2.2, 2.4).
 *
 * <p>Before any scenario runs, {@link #stackIsReady()} polls two endpoints until both are
 * available or the configured readiness timeout elapses:
 * <ul>
 *   <li>backend actuator health at {@code {api}/actuator/health} reporting status {@code UP},</li>
 *   <li>the frontend root at {@code {frontend}/} returning a {@code 2xx} status.</li>
 * </ul>
 *
 * <p>The suite does not start the stack; it assumes {@code docker compose up} was run separately.
 * If the stack is not ready within {@link TestConfig#readinessTimeoutMs()}, this fails fast with a
 * clear, actionable message rather than letting scenarios time out one by one.
 *
 * <p>The gate runs at most once per JVM run: once the stack is confirmed ready it is not re-checked.
 */
public final class WaitFor {

    private static final long POLL_INTERVAL_MS = 1_000L;

    private static volatile boolean ready = false;

    private WaitFor() {
    }

    /**
     * Blocks until the backend and frontend are both ready, or throws with a clear message on
     * timeout. Idempotent: after the first successful check it returns immediately.
     */
    public static void stackIsReady() {
        if (ready) {
            return;
        }
        synchronized (WaitFor.class) {
            if (ready) {
                return;
            }
            String apiUrl = TestConfig.apiUrl();
            String frontendUrl = TestConfig.frontendUrl();
            int timeoutMs = TestConfig.readinessTimeoutMs();

            try (Playwright playwright = Playwright.create()) {
                APIRequestContext request = playwright.request().newContext();
                try {
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    String lastBackendProblem = "no attempt made";
                    String lastFrontendProblem = "no attempt made";

                    while (System.currentTimeMillis() < deadline) {
                        lastBackendProblem = checkBackendHealth(request, apiUrl);
                        lastFrontendProblem = checkFrontend(request, frontendUrl);
                        if (lastBackendProblem == null && lastFrontendProblem == null) {
                            ready = true;
                            return;
                        }
                        sleep();
                    }

                    throw new IllegalStateException(buildFailureMessage(
                            apiUrl, frontendUrl, timeoutMs, lastBackendProblem, lastFrontendProblem));
                } finally {
                    request.dispose();
                }
            }
        }
    }

    /**
     * @return {@code null} when the backend is up and serving, otherwise a short problem
     *     description.
     *     <p>Two ready signals are accepted, because the Foremen backend secures every path under
     *     Spring Security's {@code anyRequest().authenticated()} catch-all (see
     *     {@code SecurityConfig}) — actuator endpoints are NOT whitelisted in the {@code docker}
     *     profile:
     *     <ul>
     *       <li>{@code GET /actuator/health} returns {@code 2xx} with {@code "status":"UP"} — the
     *           classic open-actuator case; or</li>
     *       <li>the backend answers HTTP with a {@code 401} (an authentication challenge). A 401
     *           proves the Spring context booted and the security filter chain is serving requests;
     *           an un-started backend yields a connection error, not a 401.</li>
     *     </ul>
     *     A connection error (backend not up yet) is reported as a problem so the gate keeps
     *     polling.
     */
    private static String checkBackendHealth(APIRequestContext request, String apiUrl) {
        String url = apiUrl + "/actuator/health";
        try {
            APIResponse response = request.get(url);
            if (response.ok()) {
                String body = response.text();
                if (body != null && body.contains("\"status\":\"UP\"")) {
                    return null;
                }
                return "GET " + url + " -> body did not report status UP: " + truncate(body);
            }
            // The backend is booted and serving but secures the endpoint (Spring Security
            // catch-all). A 401 is a positive readiness signal here; other statuses are not.
            if (response.status() == 401) {
                return null;
            }
            return "GET " + url + " -> HTTP " + response.status();
        } catch (RuntimeException e) {
            return "GET " + url + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** @return {@code null} when the frontend root responds 2xx, otherwise a problem description. */
    private static String checkFrontend(APIRequestContext request, String frontendUrl) {
        String url = frontendUrl + "/";
        try {
            APIResponse response = request.get(url);
            if (response.ok()) {
                return null;
            }
            return "GET " + url + " -> HTTP " + response.status();
        } catch (RuntimeException e) {
            return "GET " + url + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String buildFailureMessage(String apiUrl, String frontendUrl, int timeoutMs,
                                              String backendProblem, String frontendProblem) {
        StringBuilder sb = new StringBuilder();
        sb.append("Foremen stack is not ready after ").append(timeoutMs).append(" ms. ")
                .append("The QA smoke suite runs against a live stack started separately with ")
                .append("`docker compose up` (see the module README). ")
                .append("Bring the stack up and retry.\n");
        if (backendProblem != null) {
            sb.append("  Backend (").append(apiUrl).append("): ").append(backendProblem).append('\n');
        } else {
            sb.append("  Backend (").append(apiUrl).append("): ready\n");
        }
        if (frontendProblem != null) {
            sb.append("  Frontend (").append(frontendUrl).append("): ").append(frontendProblem);
        } else {
            sb.append("  Frontend (").append(frontendUrl).append("): ready");
        }
        return sb.toString();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the stack to be ready", e);
        }
    }
}
