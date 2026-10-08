package com.foremen.service.document;

import java.util.List;

/**
 * FOR-05-08 (Requirements 3.6, 1.5, 13.1): the payload for
 * {@code PUT /api/signable-documents/{id}/form-fields} — setting/filling the document's form-field
 * values. Permitted only while the document is {@code PENDING_SIGNATURES}; a CLIENT caller is limited
 * server-side to their own fields (R8.4). Form-field values are the only textual content a signer may
 * add after the body is frozen (R1.5).
 *
 * @param values the form-field values to set, keyed by field key
 */
public record FormFieldValuesInput(List<FormFieldValue> values) {

    /**
     * One form-field value entry.
     *
     * @param key   the target form-field key (e.g. {@code pesel}, {@code idDocNumber})
     * @param value the value to store, or {@code null} to clear
     */
    public record FormFieldValue(String key, String value) {
    }
}
