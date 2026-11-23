package com.foremen.qa.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/**
 * Small JSON path reader for Gherkin assertions: dot-separated names, each optionally followed by
 * selectors in square brackets, an index such as [2] or a match such as [id=266]. A path may start
 * with a selector to address a root array. A missing step yields JSON null.
 */
public final class JsonPath {

    private JsonPath() {
    }

    public static JsonElement read(JsonElement root, String path) {
        JsonElement current = root;
        for (String part : split(path)) {
            if (current == null || current.isJsonNull()) {
                return JsonNull.INSTANCE;
            }
            current = step(current, part);
        }
        return current == null ? JsonNull.INSTANCE : current;
    }

    private static List<String> split(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : path.toCharArray()) {
            depth += c == '[' ? 1 : c == ']' ? -1 : 0;
            if (c == '.' && depth == 0) {
                parts.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        parts.add(sb.toString());
        return parts;
    }

    private static JsonElement step(JsonElement current, String part) {
        int bracket = part.indexOf('[');
        String name = bracket < 0 ? part : part.substring(0, bracket);
        JsonElement value = current;
        if (!name.isEmpty()) {
            if (!current.isJsonObject() || !current.getAsJsonObject().has(name)) {
                return JsonNull.INSTANCE;
            }
            value = current.getAsJsonObject().get(name);
        }
        String rest = bracket < 0 ? "" : part.substring(bracket);
        while (!rest.isEmpty()) {
            int close = rest.indexOf(']');
            String selector = rest.substring(1, close);
            rest = rest.substring(close + 1);
            if (value == null || !value.isJsonArray()) {
                return JsonNull.INSTANCE;
            }
            value = select(value.getAsJsonArray(), selector);
        }
        return value == null ? JsonNull.INSTANCE : value;
    }

    private static JsonElement select(JsonArray array, String selector) {
        int eq = selector.indexOf('=');
        if (eq < 0) {
            int index = Integer.parseInt(selector.trim());
            return index < array.size() ? array.get(index) : null;
        }
        String key = selector.substring(0, eq).trim();
        String expected = selector.substring(eq + 1).trim();
        for (JsonElement item : array) {
            if (item.isJsonObject() && valueEquals(item.getAsJsonObject().get(key), expected)) {
                return item;
            }
        }
        return null;
    }

    /** Compare an element with expected text: null, booleans, numbers (by value) or strings. */
    public static boolean valueEquals(JsonElement actual, String expected) {
        if ("null".equals(expected)) {
            return actual == null || actual.isJsonNull();
        }
        if (actual == null || actual.isJsonNull()) {
            return false;
        }
        if (!actual.isJsonPrimitive()) {
            return actual.toString().equals(expected);
        }
        String text = actual.getAsString();
        if (text.equalsIgnoreCase(expected)) {
            return true;
        }
        try {
            return new BigDecimal(text).compareTo(new BigDecimal(expected)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String show(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "null";
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static boolean isEmpty(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return true;
        }
        if (e.isJsonArray()) {
            return e.getAsJsonArray().isEmpty();
        }
        if (e.isJsonObject()) {
            return e.getAsJsonObject().size() == 0;
        }
        return e.getAsString().isEmpty();
    }
}
