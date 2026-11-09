package com.foremen.service.dashboard.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side whitelist of internal route templates a widget link may target (FOR-10 Requirement 10.2).
 * Kept in sync with the frontend {@code route-permissions.ts} by a contract test.
 */
public final class RouteWhitelist {

    public static final Set<String> ROUTES = Set.of(
            "/projects/{projectId}",
            "/projects/{projectId}/{tab}",
            "/company/payroll",
            "/company/bank-transactions",
            "/company/procurement",
            "/company/staffing-gantt",
            "/users");

    /** Known project workspace tab slugs (keys of the frontend {@code WORKSPACE_TABS}). */
    public static final Set<String> PROJECT_TABS = Set.of(
            "rooms", "estimate", "pricing", "team", "scheduleDesign", "documentSigning", "materials",
            "margins", "workReporting", "planActual", "procurement", "purchases", "materialsMatrix",
            "marginMatrix", "clientPayments", "amendments", "clientCommunication");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private RouteWhitelist() {
    }

    /** True when the route is exactly one of the whitelisted templates. */
    public static boolean isAllowed(String route) {
        return route != null && ROUTES.contains(route);
    }

    /** Placeholder names of a template, in order of appearance. */
    public static List<String> placeholders(String route) {
        List<String> names = new ArrayList<>();
        if (route == null) {
            return names;
        }
        Matcher m = PLACEHOLDER.matcher(route);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
