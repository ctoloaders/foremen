package com.foremen.service.document;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.exception.ForemenApiException;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 2.1, 2.2, 2.4, 2.5; design §Data Models {@code SignableDocumentTypeEntity}):
 * the ADMIN-only, GLOBAL (not project-scoped) CRUD surface over {@link SignableDocumentTypeEntity} —
 * the type catalog the signing lifecycle, merge generation and activation hand-off bind to by
 * {@code code}.
 *
 * <h2>Responsibilities (task 11.3)</h2>
 * <ul>
 *   <li><b>CRUD</b> — {@link #list()} / {@link #get(Long)} / {@link #create(SignableDocumentTypeInput)}
 *       / {@link #update(Long, SignableDocumentTypeInput)} over the catalog (R2.1, R2.4).</li>
 *   <li><b>activate / deactivate</b> — {@link #activate(Long)} / {@link #deactivate(Long)} flip the
 *       {@code active} flag; a type is <b>deactivated, never deleted</b> (R2.5).</li>
 * </ul>
 *
 * <p><b>Deactivate, never delete (R2.5).</b> No delete path exists: a type is retired by
 * {@link #deactivate(Long)} so existing documents bound to it stay valid.
 */
@Service
@RequiredArgsConstructor
public class SignableDocumentTypeService {

    /** 404 when the referenced type cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 400 when a create / update supplies no (code, namePL). */
    static final String TYPE_INVALID_MESSAGE = "error.document.type.invalid";

    /** 409 when a create would duplicate an existing type {@code code}. */
    static final String TYPE_DUPLICATE_MESSAGE = "error.document.type.duplicate";

    private final SignableDocumentTypeDao typeDao;

    /**
     * Lists every type (active and inactive) ordered by {@code code} — the admin catalog read (R2.3).
     *
     * @return the types as read DTOs
     */
    @Transactional(readOnly = true)
    public List<SignableDocumentTypeDto> list() {
        List<SignableDocumentTypeDto> dtos = new ArrayList<>();
        for (SignableDocumentTypeEntity type : typeDao.findAll()) {
            dtos.add(toDto(type));
        }
        return dtos;
    }

    /**
     * Loads one type by id (R2.1).
     *
     * @param id the type id
     * @return the type read DTO
     * @throws ForemenApiException 404 when the type is missing
     */
    @Transactional(readOnly = true)
    public SignableDocumentTypeDto get(Long id) {
        return toDto(requireType(id));
    }

    /**
     * Creates a new, active type (R2.1, R2.4). Rejects a blank payload and a duplicate {@code code}.
     *
     * @param in the create payload (code + namePL required)
     * @return the created type read DTO
     * @throws ForemenApiException 400 on an invalid payload; 409 on a duplicate code
     */
    @Transactional
    public SignableDocumentTypeDto create(SignableDocumentTypeInput in) {
        validate(in);
        if (typeDao.findByCode(in.code().trim()).isPresent()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TYPE_DUPLICATE_MESSAGE, "code", in.code());
        }
        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        type.setCode(in.code().trim());
        type.setNamePL(in.namePL());
        type.setNameRU(in.nameRU());
        type.setActive(true);
        type.setDefaultSignatureLevel(in.defaultSignatureLevel());
        return toDto(typeDao.save(type));
    }

    /**
     * Updates an existing type's display names and default level (R2.4). The {@code code} and the
     * {@code active} flag are not mutated here (activate / deactivate own the flag; the code is a
     * stable key).
     *
     * @param id the type id
     * @param in the update payload (namePL required)
     * @return the updated type read DTO
     * @throws ForemenApiException 400 on an invalid payload; 404 when the type is missing
     */
    @Transactional
    public SignableDocumentTypeDto update(Long id, SignableDocumentTypeInput in) {
        validate(in);
        SignableDocumentTypeEntity type = requireType(id);
        type.setNamePL(in.namePL());
        type.setNameRU(in.nameRU());
        type.setDefaultSignatureLevel(in.defaultSignatureLevel());
        return toDto(typeDao.save(type));
    }

    /**
     * Activates a type (idempotent) (R2.5).
     *
     * @param id the type id
     * @return the activated type read DTO
     * @throws ForemenApiException 404 when the type is missing
     */
    @Transactional
    public SignableDocumentTypeDto activate(Long id) {
        SignableDocumentTypeEntity type = requireType(id);
        type.setActive(true);
        return toDto(typeDao.save(type));
    }

    /**
     * Deactivates a type — the retire path (R2.5): the type is NEVER deleted, so existing documents
     * bound to it stay valid. Deactivating an already-inactive type is idempotent.
     *
     * @param id the type id
     * @return the deactivated type read DTO
     * @throws ForemenApiException 404 when the type is missing
     */
    @Transactional
    public SignableDocumentTypeDto deactivate(Long id) {
        SignableDocumentTypeEntity type = requireType(id);
        type.setActive(false);
        return toDto(typeDao.save(type));
    }

    // --- internals ---

    private void validate(SignableDocumentTypeInput in) {
        if (in == null || isBlank(in.code()) || isBlank(in.namePL())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TYPE_INVALID_MESSAGE);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SignableDocumentTypeEntity requireType(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentTypeId", id);
        }
        return typeDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentTypeId", id));
    }

    private static SignableDocumentTypeDto toDto(SignableDocumentTypeEntity type) {
        return new SignableDocumentTypeDto(
                type.getId(),
                type.getCode(),
                type.getNamePL(),
                type.getNameRU(),
                type.isActive(),
                type.getDefaultSignatureLevel());
    }
}
