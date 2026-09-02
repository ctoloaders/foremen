package com.foremen.testsupport;

import com.foremen.config.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only demonstrative controller that exercises the {@code PermissionInterceptor} end to end.
 *
 * <p>It lives under {@code src/test} in the {@code com.foremen.testsupport} package (deliberately
 * NOT in the production {@code com.foremen.controller} package) so that it is component-scanned by
 * full-context {@code @SpringBootTest} integration tests (task 8.2) but never ships as part of the
 * production application. This proves runtime {@code @RequiresPermission} enforcement without
 * migrating any existing production controller off {@code permitAll()} (that migration is deferred
 * to FOR-03-08).
 *
 * <p>The single {@code GET /demo} endpoint requires {@code (PROJECTS, READ)}; the interceptor
 * allows the request only when the current role holds that permission (or is ADMIN), otherwise it
 * yields HTTP 403 {@code error.access.denied}. The endpoint body executes only when access is
 * granted, demonstrating the before-controller enforcement guarantee.
 *
 * <p>Requirements: 11.1, 11.4
 */
@RestController
public class DemoPermissionController {

    /** Body returned when the interceptor allows the request through to the controller. */
    public static final String OK_BODY = "demo-ok";

    /**
     * Demonstrative protected endpoint requiring the {@code (PROJECTS, READ)} permission.
     *
     * @return a fixed success body, returned only when enforcement allows the request
     */
    @GetMapping("/demo")
    @RequiresPermission(resource = "PROJECTS", operation = "READ")
    public String demo() {
        return OK_BODY;
    }
}
