package com.foremen.service.estimateai.chat;

import java.util.List;

/**
 * FOR-05-04 (Requirement 11.3) — the ABAC {@code (resource, operation)} pairs a tool needs. Read tools
 * need {@code OFFERS READ}; write tools need {@code ESTIMATE UPDATE} and {@code OFFERS UPDATE}.
 */
public record ToolPermission(List<Required> required) {

    public record Required(String resource, String operation) {
    }

    public static final String OFFERS = "OFFERS";
    public static final String ESTIMATE = "ESTIMATE";

    public static final ToolPermission READ = new ToolPermission(List.of(new Required(OFFERS, "READ")));
    public static final ToolPermission WRITE = new ToolPermission(List.of(
            new Required(ESTIMATE, "UPDATE"), new Required(OFFERS, "UPDATE")));

    public ToolPermission {
        required = List.copyOf(required);
    }

    public boolean isWrite() {
        return required.stream().anyMatch(r -> "UPDATE".equals(r.operation()));
    }
}
