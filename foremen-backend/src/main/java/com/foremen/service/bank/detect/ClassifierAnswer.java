package com.foremen.service.bank.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.foremen.service.ocr.OcrHelpers;

/**
 * FOR-07-04 (Requirement 15.3) — the classifier answer
 * {@code {kind, candidateRef|null, categoryCode|null, projectId|null, confidence, rationale}}.
 *
 * <p>{@link #parse(String)} is tolerant (markdown fences + brace slicing, reusing
 * {@link OcrHelpers#extractJson(String)}); validation against the input is done by
 * {@link ClassifierAnswerValidator}.
 */
public record ClassifierAnswer(
        String kind,
        String candidateRef,
        String categoryCode,
        Long projectId,
        double confidence,
        String rationale) {

    /** Whether the answer carries a kind and can be stored as a suggestion. */
    public boolean usable() {
        return kind != null;
    }

    /**
     * Parse a raw model response. Returns {@code null} when no JSON object can be read.
     * Unreadable individual fields become {@code null} (confidence → {@code 0}).
     */
    public static ClassifierAnswer parse(String text) {
        JsonNode root = OcrHelpers.extractJson(text);
        if (root == null || !root.isObject()) {
            return null;
        }
        return new ClassifierAnswer(
                readText(root.get("kind")),
                readText(root.get("candidateRef")),
                readText(root.get("categoryCode")),
                readLong(root.get("projectId")),
                readDouble(root.get("confidence")),
                readText(root.get("rationale")));
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isNumber()) {
            String s = node.asText().strip();
            return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
        }
        return null;
    }

    private static Long readLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.valueOf(node.asText().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static double readDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0.0;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().strip());
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
