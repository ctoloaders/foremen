package com.foremen.controller.model.client;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** FOR-11-02 (design 4): test-only reflection walk over the client DTO tree. */
public final class ClientDtoLeakGuard {

    public static final List<String> DENY_TOKENS = List.of("purchase", "internal", "margin", "delivery",
            "seller", "needby", "orderby", "planneddelivery", "audit", "request");

    private ClientDtoLeakGuard() {
    }

    /** @return human-readable violations; empty when the tree is clean */
    public static List<String> violations(Class<?> root) {
        List<String> out = new ArrayList<>();
        walk(root, new LinkedHashSet<>(), out);
        return out;
    }

    private static void walk(Class<?> type, Set<Class<?>> seen, List<String> out) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        for (RecordComponent c : type.getRecordComponents()) {
            String lower = c.getName().toLowerCase(Locale.ROOT);
            for (String token : DENY_TOKENS) {
                if (lower.contains(token)) {
                    out.add(type.getSimpleName() + "." + c.getName() + " contains '" + token + "'");
                }
            }
            for (Class<?> t : classesOf(c.getGenericType())) {
                if (isStaff(t)) {
                    out.add(type.getSimpleName() + "." + c.getName() + " uses staff type " + t.getName());
                }
                walk(t, seen, out);
            }
        }
    }

    private static boolean isStaff(Class<?> t) {
        return t.getSimpleName().startsWith("Staff") || t.getName().toLowerCase(Locale.ROOT).contains(".staff");
    }

    private static List<Class<?>> classesOf(Type type) {
        List<Class<?>> out = new ArrayList<>();
        if (type instanceof Class<?> c) {
            out.add(c.isArray() ? c.getComponentType() : c);
        } else if (type instanceof ParameterizedType p) {
            out.addAll(classesOf(p.getRawType()));
            for (Type a : p.getActualTypeArguments()) {
                out.addAll(classesOf(a));
            }
        } else if (type instanceof GenericArrayType g) {
            out.addAll(classesOf(g.getGenericComponentType()));
        } else if (type instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) {
                out.addAll(classesOf(b));
            }
        }
        return out;
    }
}
