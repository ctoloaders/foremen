package com.foremen.service.model;

/**
 * FOR-05-04 (Requirement 1.1): normalisation of the catalog {@code aiInstructions} text. The value is
 * trimmed and a blank value becomes {@code null}.
 */
public final class AiInstructions {

    /** Maximum length of the trimmed text (Requirement 1.1). */
    public static final int MAX_LENGTH = 8000;

    private AiInstructions() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
