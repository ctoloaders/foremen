package com.foremen.service.permission;

import java.util.Set;

/**
 * Immutable value holding one role's granted (resource, operation) pairs.
 * Each grant is stored as the string {@code "RESOURCE:OPERATION"}; membership is an exact string match.
 */
public record PermissionSet(Set<String> grants) {

    public static String key(String resource, String operation) {
        return resource + ":" + operation;
    }

    public boolean allows(String resource, String operation) {
        return grants.contains(key(resource, operation));
    }

    public static PermissionSet empty() {
        return new PermissionSet(Set.of());
    }
}
