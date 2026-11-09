package com.foremen.service.dashboard.dataset;

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;

/** Resolves dot-paths against the JPA metamodel (to-one associations only). */
final class JpaPaths {

    private JpaPaths() {
    }

    /** @return {@code null} when the path resolves, otherwise a human-readable reason. */
    static String check(Metamodel metamodel, Class<?> root, String path) {
        if (path == null || path.isBlank()) {
            return "empty path";
        }
        ManagedType<?> current;
        try {
            current = metamodel.managedType(root);
        } catch (IllegalArgumentException e) {
            return "root " + root.getName() + " is not a managed type";
        }
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            Attribute<?, ?> attr;
            try {
                attr = current.getAttribute(segments[i]);
            } catch (IllegalArgumentException e) {
                return "attribute '" + segments[i] + "' not found on " + current.getJavaType().getSimpleName();
            }
            if (attr.isCollection()) {
                return "attribute '" + segments[i] + "' is a collection";
            }
            if (i == segments.length - 1) {
                return null;
            }
            if (!(attr instanceof SingularAttribute<?, ?> singular)
                    || !(singular.getType() instanceof ManagedType<?> next)) {
                return "attribute '" + segments[i] + "' is not an association";
            }
            current = next;
        }
        return null;
    }
}
