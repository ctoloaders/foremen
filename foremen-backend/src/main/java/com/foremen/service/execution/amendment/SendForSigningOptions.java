package com.foremen.service.execution.amendment;

import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;

/**
 * FOR-06-09 (Requirement 1.4; design §{@code sendForSigning}): the optional per-send overrides a
 * MANAGER/FINANCIER may choose in the Aneksy send-for-signing dialog. Both fields are optional — a
 * {@code null} {@code method} / {@code level} means "use the engine/document default" (the level
 * defaults to {@link SignatureLevel#AdES}, R1.4), so an empty body is a valid "send with defaults"
 * request.
 *
 * <p>The payload carries only the signing ceremony knobs the FOR-05-08 engine understands; it never
 * carries cost/estimate/margin data — the amendment body is generated server-side from the stored
 * lines + the signed baseline.
 *
 * @param method the signing method for the created signatures, or {@code null} for the default
 * @param level  the eIDAS level override, or {@code null} to default to {@link SignatureLevel#AdES}
 */
public record SendForSigningOptions(SignatureMethod method, SignatureLevel level) {

    /** The default signing method when the dialog supplies none ({@link SignatureMethod#ONLINE}). */
    public static final SignatureMethod DEFAULT_METHOD = SignatureMethod.ONLINE;

    /** The default eIDAS level when the dialog supplies none ({@link SignatureLevel#AdES}, R1.4). */
    public static final SignatureLevel DEFAULT_LEVEL = SignatureLevel.AdES;

    /** A "send with engine defaults" option (no method / level override). */
    public static SendForSigningOptions defaults() {
        return new SendForSigningOptions(null, null);
    }

    /** The effective method: the chosen override, else {@link #DEFAULT_METHOD}. */
    public SignatureMethod effectiveMethod() {
        return method != null ? method : DEFAULT_METHOD;
    }

    /** The effective level: the chosen override, else {@link #DEFAULT_LEVEL} (R1.4). */
    public SignatureLevel effectiveLevel() {
        return level != null ? level : DEFAULT_LEVEL;
    }
}
