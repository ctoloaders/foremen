package com.foremen.service.document;

import com.foremen.dao.model.SignatureLevel;

/**
 * FOR-05-08 (Requirements 2.1, 2.2, 2.4): the create / update payload for a
 * {@code SignableDocumentType}.
 *
 * <p>{@link #code} and {@link #namePL} are required; {@link #nameRU} and {@link #defaultSignatureLevel}
 * are optional. The {@code active} flag is not part of this payload — a type is activated /
 * deactivated through the dedicated activate / deactivate endpoints (R2.5), never deleted.
 *
 * @param code                  the stable business key (required, unique)
 * @param namePL                the PL display name (required)
 * @param nameRU                the optional RU display name
 * @param defaultSignatureLevel the optional per-type default eIDAS level
 */
public record SignableDocumentTypeInput(
        String code,
        String namePL,
        String nameRU,
        SignatureLevel defaultSignatureLevel) {
}
