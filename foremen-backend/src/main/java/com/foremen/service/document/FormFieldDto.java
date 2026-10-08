package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 3.6, 1.5): the read model of one {@code DocumentFormField} — a declared
 * fill-in blank on a document (e.g. PESEL, ID-document number). Served inside
 * {@link SignableDocumentDto#formFields}.
 *
 * <p>Form fields are part of the frozen artifact and are the only textual content a signer may add
 * after the freeze; {@link #value} is editable only while the document is {@code PENDING_SIGNATURES}
 * (R1.5). {@link #ownerRole} narrows which signer role may fill it (the CLIENT own-fields constraint,
 * R8.4).
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> client-reachable; carries NO cost/estimate/margin field.
 *
 * @param id        the form-field id
 * @param key       the field key (e.g. {@code pesel}, {@code idDocNumber})
 * @param label     the display label (localized on the frontend), or {@code null}
 * @param ownerRole which signer role may fill it, or {@code null}
 * @param value     the filled value, or {@code null}
 */
public record FormFieldDto(
        Long id,
        String key,
        String label,
        String ownerRole,
        String value) {
}
