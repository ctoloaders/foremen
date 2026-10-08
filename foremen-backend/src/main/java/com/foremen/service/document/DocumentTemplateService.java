package com.foremen.service.document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentTemplateDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.docx.DocxBodyExtractor;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeMode;
import com.foremen.service.signing.merge.MergeResult;
import com.foremen.service.signing.merge.TemplateMergeEngine;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 2.5, 3.1, 3.2, 3a.1, 3a.4, 3a.5; design §Components
 * {@code DocumentTemplateService (admin, versioned)}): the ADMIN-only, GLOBAL (not project-scoped)
 * CRUD surface over {@link DocumentTemplateEntity} — the versioned template library the merge
 * generation reads from.
 *
 * <h2>Responsibilities (task 10.1)</h2>
 * <ul>
 *   <li><b>CRUD</b> — {@link #list()} / {@link #listByTypeAndLocale(Long, String)} / {@link #get(Long)}
 *       / {@link #create(TemplateSaveInput)} / {@link #save(TemplateSaveInput)} over the template
 *       library (R3a.1).</li>
 *   <li><b>{@code .docx} import</b> — {@link #importDocx(Long, String, String, MultipartFile)} extracts
 *       a {@code .docx}'s plain-text body (preserving {@code {Token}} placeholders) through the
 *       {@link DocxBodyExtractor} OOXML seam and lands it as a new active template version — the seed
 *       path from {@code docs/templates/} (R3a.1).</li>
 *   <li><b>activate / deactivate</b> — {@link #activate(Long)} / {@link #deactivate(Long)} honouring the
 *       one-active-per-({@code documentType}, {@code locale}) partial-unique constraint: activating a
 *       version first deactivates the currently-active one for the same (type, locale) (R3.2).</li>
 *   <li><b>versioned saves</b> — {@link #save(TemplateSaveInput)} bumps {@code version} and retains the
 *       prior versions (never overwrites), making the new version active (R3a.5).</li>
 *   <li><b>test-merge preview</b> — {@link #testMerge(Long)} runs the {@link TemplateMergeEngine} over a
 *       sample {@link MergeContext}, returning the rendered body + the deterministic set of unresolved
 *       placeholders (R3.4, R3a.4).</li>
 * </ul>
 *
 * <p><b>Deactivate, never delete (R2.5 / R3a.1).</b> No delete path exists on this service: a template
 * is retired by {@link #deactivate(Long)} so existing {@code SignableDocument}s and the version history
 * stay valid and auditable.
 *
 * <p><b>Body storage seam.</b> The merge engine's contract is a plain {@code String} body of
 * {@code {Token}} placeholders ({@link TemplateMergeEngine}); the template's {@code storageUri} doubles
 * as that body text (consistent with {@code SignableDocumentService}'s template read), so a saved /
 * imported body is directly renderable by generation and by {@link #testMerge(Long)}. The {@code .docx}
 * toolkit lives entirely behind {@link DocxBodyExtractor}.
 */
@Service
@RequiredArgsConstructor
public class DocumentTemplateService {

    /** 404 when the referenced template / type cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** 400 when a create / save / import supplies no (type, locale, name, body). */
    static final String TEMPLATE_INVALID_MESSAGE = "error.document.template.invalid";

    /** 401 when the acting user cannot be resolved for the uploader provenance. */
    static final String UNAUTHORIZED_MESSAGE = "error.auth.unauthorized";

    private final DocumentTemplateDao templateDao;
    private final SignableDocumentTypeDao documentTypeDao;
    private final UserDao userDao;
    private final DocxBodyExtractor docxBodyExtractor;
    private final TemplateMergeEngine mergeEngine;

    // --- Read (R3a.1) ---

    /**
     * Lists every template version (active and inactive) across the library — the admin template-list
     * read (R3a.1).
     *
     * @return the templates as read DTOs
     */
    @Transactional(readOnly = true)
    public List<DocumentTemplateDto> list() {
        List<DocumentTemplateDto> dtos = new ArrayList<>();
        for (DocumentTemplateEntity template : templateDao.findAll()) {
            dtos.add(toDto(template));
        }
        return dtos;
    }

    /**
     * The version history for one ({@code documentType}, {@code locale}) pair, newest version first —
     * backs the version-history view (R3a.5).
     *
     * @param documentTypeId the owning type id
     * @param locale         the template locale
     * @return the versions, newest first
     */
    @Transactional(readOnly = true)
    public List<DocumentTemplateDto> listByTypeAndLocale(Long documentTypeId, String locale) {
        List<DocumentTemplateDto> dtos = new ArrayList<>();
        for (DocumentTemplateEntity template
                : templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(documentTypeId, locale)) {
            dtos.add(toDto(template));
        }
        return dtos;
    }

    /**
     * Loads one template version by id (R3a.1).
     *
     * @param id the template id
     * @return the template read DTO
     * @throws ForemenApiException 404 when the template is missing
     */
    @Transactional(readOnly = true)
    public DocumentTemplateDto get(Long id) {
        return toDto(requireTemplate(id));
    }

    // --- Create / versioned save (R3.1, R3a.5) ---

    /**
     * Creates the first template for a ({@code documentType}, {@code locale}) pair: version 1, active.
     * If an active template already exists for the pair it is deactivated first so the one-active
     * invariant holds (R3.2).
     *
     * @param in the create payload (type id + name + locale + body, all required)
     * @return the created template read DTO
     * @throws ForemenApiException 400 on an invalid payload; 404 when the type is missing; 401 when the
     *                             uploader cannot be resolved
     */
    @Transactional
    public DocumentTemplateDto create(TemplateSaveInput in) {
        return toDto(persistNewVersion(in, nextVersion(in)));
    }

    /**
     * Saves a new version of a template for a ({@code documentType}, {@code locale}) pair: bumps the
     * version to {@code max(existing) + 1}, retains every prior version (never overwrites), and makes
     * the new version the active one (deactivating the previously-active version for the same (type,
     * locale)) (R3a.5, R3.2).
     *
     * @param in the save payload (type id + name + locale + body, all required)
     * @return the saved (new, active) template version read DTO
     * @throws ForemenApiException 400 on an invalid payload; 404 when the type is missing; 401 when the
     *                             uploader cannot be resolved
     */
    @Transactional
    public DocumentTemplateDto save(TemplateSaveInput in) {
        return toDto(persistNewVersion(in, nextVersion(in)));
    }

    /**
     * Imports an existing {@code .docx} (e.g. seeded from {@code docs/templates/}) as a new, active
     * template version for the given (type, locale): the {@code .docx} plain-text body (with its
     * {@code {Token}} placeholders preserved) is extracted through the {@link DocxBodyExtractor} OOXML
     * seam and persisted as the new version's body (R3a.1).
     *
     * @param documentTypeId the bound document type id (required)
     * @param name           the template name (required)
     * @param locale         {@code PL} / {@code RU} / {@code BILINGUAL} (required)
     * @param docx           the uploaded {@code .docx} template (required)
     * @return the imported (new, active) template version read DTO
     * @throws ForemenApiException 400 on an invalid payload / {@code .docx}; 404 when the type is
     *                             missing; 401 when the uploader cannot be resolved
     */
    @Transactional
    public DocumentTemplateDto importDocx(Long documentTypeId, String name, String locale, MultipartFile docx) {
        String body = docxBodyExtractor.extractBody(docx);
        return toDto(persistNewVersion(
                new TemplateSaveInput(documentTypeId, name, locale, body),
                nextVersion(documentTypeId, locale)));
    }

    // --- Activate / deactivate (R3.2, R2.5) ---

    /**
     * Activates a template version, enforcing one-active-per-({@code documentType}, {@code locale}):
     * the currently-active version for the same (type, locale) — if any and if different — is
     * deactivated first, then the target version is activated (R3.2). Activating the already-active
     * version is idempotent.
     *
     * @param id the template version id to activate
     * @return the activated template read DTO
     * @throws ForemenApiException 404 when the template is missing
     */
    @Transactional
    public DocumentTemplateDto activate(Long id) {
        DocumentTemplateEntity target = requireTemplate(id);
        deactivateCurrentActive(target.getDocumentType().getId(), target.getLocale(), target.getId());
        target.setActive(true);
        return toDto(templateDao.save(target));
    }

    /**
     * Deactivates a template version — the retire path (R2.5 / R3a.1): the template is NEVER deleted,
     * so existing documents and the version history stay valid and auditable. Deactivating an already
     * inactive version is idempotent.
     *
     * @param id the template version id to deactivate
     * @return the deactivated template read DTO
     * @throws ForemenApiException 404 when the template is missing
     */
    @Transactional
    public DocumentTemplateDto deactivate(Long id) {
        DocumentTemplateEntity target = requireTemplate(id);
        target.setActive(false);
        return toDto(templateDao.save(target));
    }

    // --- Test-merge preview (R3.4, R3a.4) ---

    /**
     * Runs a <b>test-merge preview</b> of a template against a sample {@link MergeContext}: the engine
     * substitutes every resolvable {@code {Token}} and reports the deterministic set of placeholders it
     * could not resolve from the sample context, in {@link MergeMode#MARK_BLANK} (so the preview always
     * renders, leaving a visible blank for each unresolved token rather than failing) (R3.4 option (a),
     * R3a.4).
     *
     * <p>The sample context carries only a transient document bound to the template's type, so every
     * domain-backed token (client, schedule, totals, company, property, representative) resolves to
     * {@code null} and is reported unresolved — exactly the "which tokens still need data" signal the
     * admin preview is for.
     *
     * @param id the template version id to preview
     * @return the rendered preview body + the unresolved placeholder list
     * @throws ForemenApiException 404 when the template is missing
     */
    @Transactional(readOnly = true)
    public TestMergeResultDto testMerge(Long id) {
        DocumentTemplateEntity template = requireTemplate(id);
        MergeResult result = mergeEngine.render(templateBody(template), sampleContext(template), MergeMode.MARK_BLANK);
        return new TestMergeResultDto(null, result.body(), new ArrayList<>(result.unresolvedPlaceholders()));
    }

    // --- internals ---

    /**
     * Persists {@code in} as a new template version with the given {@code version} number, active,
     * stamping the uploader + upload timestamp and deactivating the previously-active version for the
     * same (type, locale) so the one-active invariant holds (R3.2, R3a.5).
     */
    private DocumentTemplateEntity persistNewVersion(TemplateSaveInput in, int version) {
        validate(in);
        SignableDocumentTypeEntity type = requireType(in.documentTypeId());

        deactivateCurrentActive(type.getId(), in.locale(), null);

        DocumentTemplateEntity template = new DocumentTemplateEntity();
        template.setDocumentType(type);
        template.setName(in.name());
        template.setLocale(in.locale());
        // The storageUri doubles as the body text the merge engine renders (see class javadoc).
        template.setStorageUri(in.body() == null ? "" : in.body());
        template.setVersion(version);
        template.setActive(true);
        template.setUploadedBy(requireCurrentUser());

        return templateDao.save(template);
    }

    /** Deactivates the currently-active version for a (type, locale), skipping {@code keepId} if set. */
    private void deactivateCurrentActive(Long documentTypeId, String locale, Long keepId) {
        templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(documentTypeId, locale)
                .filter(active -> keepId == null || !active.getId().equals(keepId))
                .ifPresent(active -> {
                    active.setActive(false);
                    templateDao.save(active);
                });
    }

    /** The next version number for a (type, locale): {@code max(existing) + 1}, or 1 when none exist. */
    private int nextVersion(TemplateSaveInput in) {
        return nextVersion(in.documentTypeId(), in.locale());
    }

    private int nextVersion(Long documentTypeId, String locale) {
        if (documentTypeId == null) {
            return 1;
        }
        return templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(documentTypeId, locale).stream()
                .map(DocumentTemplateEntity::getVersion)
                .filter(v -> v != null)
                .max(Integer::compareTo)
                .map(max -> max + 1)
                .orElse(1);
    }

    /** The template body the merge engine renders — the stored {@code storageUri} body text. */
    private static String templateBody(DocumentTemplateEntity template) {
        String body = template.getStorageUri();
        return body == null ? "" : body;
    }

    /**
     * A sample {@link MergeContext} for the test-merge preview: a transient document bound to the
     * template's type with no project/client/offer/company data, so every domain-backed token is
     * reported unresolved (R3a.4).
     */
    private static MergeContext sampleContext(DocumentTemplateEntity template) {
        SignableDocumentEntity sample = new SignableDocumentEntity();
        sample.setDocumentType(template.getDocumentType());
        return MergeContext.builder().document(sample).build();
    }

    private void validate(TemplateSaveInput in) {
        if (in == null
                || in.documentTypeId() == null
                || isBlank(in.name())
                || isBlank(in.locale())
                || in.body() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TEMPLATE_INVALID_MESSAGE);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private DocumentTemplateEntity requireTemplate(Long id) {
        if (id == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "templateId", id);
        }
        return templateDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "templateId", id));
    }

    private SignableDocumentTypeEntity requireType(Long typeId) {
        if (typeId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentTypeId", typeId);
        }
        return documentTypeDao.findById(typeId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "documentTypeId", typeId));
    }

    /** The authenticated caller as a managed {@link UserEntity} for the {@code uploadedBy} provenance. */
    private UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
        }
        return userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE));
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Maps a template entity onto its client-reachable read DTO. */
    private static DocumentTemplateDto toDto(DocumentTemplateEntity template) {
        SignableDocumentTypeEntity type = template.getDocumentType();
        Long typeId = type != null ? type.getId() : null;
        String typeCode = type != null ? type.getCode() : null;
        Long uploadedById = template.getUploadedBy() != null ? template.getUploadedBy().getId() : null;
        LocalDateTime uploadedAt = template.getCreatedDate();
        return new DocumentTemplateDto(
                template.getId(),
                typeId,
                typeCode,
                template.getName(),
                template.getLocale(),
                template.getStorageUri(),
                template.getVersion(),
                template.isActive(),
                uploadedById,
                uploadedAt);
    }
}
