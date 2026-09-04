package com.foremen.util;

import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.PermissionView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes a strong, order-independent {@code ETag} for a {@link CurrentUserResponse}
 * (Requirement 13 backend dependency).
 *
 * <p>The tag is derived from a canonical string built from the response's semantic fields:
 * <pre>
 * userId|name|email|roleCode|sortedPermissions
 * </pre>
 * where permissions are sorted by resource and, within each resource, operations are sorted.
 * The canonical string is SHA-256 hashed, hex-encoded, and wrapped in double quotes to form a
 * strong validator. Consequently the ETag changes exactly when identity/profile, role, the
 * role's permissions, or role membership change, and is stable otherwise — independently of JSON
 * key ordering or whitespace.
 */
public final class MeETag {

    private MeETag() {
    }

    /**
     * Computes the strong, quoted ETag for the given current-user representation.
     *
     * @param dto the current-user response (must not be {@code null})
     * @return a strong ETag of the form {@code "<hex-sha256>"}
     */
    public static String compute(CurrentUserResponse dto) {
        String canonical = canonical(dto);
        return "\"" + hexSha256(canonical) + "\"";
    }

    private static String canonical(CurrentUserResponse dto) {
        return nullSafe(dto.id()) + "|"
                + nullSafe(dto.name()) + "|"
                + nullSafe(dto.email()) + "|"
                + nullSafe(dto.roleCode()) + "|"
                + sortedPermissions(dto.permissions());
    }

    private static String sortedPermissions(Set<PermissionView> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        // Sort resources; within each resource, sort operations. Order-independent.
        List<String> resourceParts = new ArrayList<>(permissions.size());
        for (PermissionView permission : permissions) {
            String resource = nullSafe(permission.resource());
            Set<String> ops = permission.operations();
            TreeSet<String> sortedOps = new TreeSet<>();
            if (ops != null) {
                for (String op : ops) {
                    sortedOps.add(nullSafe(op));
                }
            }
            resourceParts.add(resource + ":" + String.join(",", sortedOps));
        }
        resourceParts.sort(String::compareTo);
        return String.join(";", resourceParts);
    }

    private static String hexSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every JVM; this should never happen.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }
}
