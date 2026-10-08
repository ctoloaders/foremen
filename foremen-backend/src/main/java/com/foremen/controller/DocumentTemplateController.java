package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.document.DocumentTemplateDto;
import com.foremen.service.document.DocumentTemplateService;
import com.foremen.service.document.TemplateSaveInput;
import com.foremen.service.document.TestMergeResultDto;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 3a.1, 3a.6, 8.7, 13.1; design §Backend controllers): the ADMIN-only,
 * GLOBAL (not project-scoped) template-library surface over {@code DocumentTemplate} — CRUD, the
 * {@code .docx} import, body edits (versioned saves), activate/deactivate per (type, locale), and
 * the test-merge preview — layered on the {@code DOCUMENT_TEMPLATES} ABAC resource (seeded by
 * changeset 147).
 *
 * <h2>ABAC guarding (entity-creation-rules step 3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("DOCUMENT_TEMPLATES")}; every
 * handler carries a method-level {@link RequiresPermission @RequiresPermission} mapping to standard
 * CRUD (mirroring {@link NotificationController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup (R8.7, R3a.6):
 * <ul>
 *   <li>{@code GET /}, {@code GET /{id}}, {@code GET /versions} — list / read / version-history,
 *       READ.</li>
 *   <li>{@code POST /}, {@code POST /import} — create a template / import a {@code .docx} version,
 *       CREATE.</li>
 *   <li>{@code PUT /}, {@code POST /{id}/activate}, {@code POST /{id}/deactivate} — versioned body
 *       save / activate / deactivate, UPDATE.</li>
 *   <li>{@code GET /{id}/test-merge} — the merge preview (rendered body + unresolved placeholders),
 *       READ.</li>
 * </ul>
 * There is deliberately <b>no DELETE endpoint</b>: a template is retired by deactivation (R2.5,
 * R3a.1).
 */
@RestController
@RequestMapping("/api/document-templates")
@RequiredArgsConstructor
@PermissionResource("DOCUMENT_TEMPLATES")
public class DocumentTemplateController {

    private static final String RESOURCE = "DOCUMENT_TEMPLATES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";

    private final DocumentTemplateService service;

    /** Lists every template version across the library (R3a.1). {@code DOCUMENT_TEMPLATES} READ. */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<DocumentTemplateDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    /** Reads one template version by id (R3a.1). {@code DOCUMENT_TEMPLATES} READ. */
    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<DocumentTemplateDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    /**
     * The version history for one (type, locale), newest first (R3a.5). {@code DOCUMENT_TEMPLATES}
     * READ.
     */
    @GetMapping("/versions")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<DocumentTemplateDto>> listByTypeAndLocale(
            @RequestParam("documentTypeId") Long documentTypeId,
            @RequestParam("locale") String locale) {
        return ResponseEntity.ok(service.listByTypeAndLocale(documentTypeId, locale));
    }

    /**
     * Creates the first template for a (type, locale) (R3a.1). {@code DOCUMENT_TEMPLATES} CREATE.
     */
    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<DocumentTemplateDto> create(@RequestBody TemplateSaveInput input) {
        return ResponseEntity.ok(service.create(input));
    }

    /**
     * Imports a {@code .docx} as a new, active template version (R3a.1). {@code DOCUMENT_TEMPLATES}
     * CREATE.
     */
    @PostMapping("/import")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<DocumentTemplateDto> importDocx(
            @RequestParam("documentTypeId") Long documentTypeId,
            @RequestParam("name") String name,
            @RequestParam("locale") String locale,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(service.importDocx(documentTypeId, name, locale, file));
    }

    /**
     * Saves a new version of a template body — the editor save (R3a.5). {@code DOCUMENT_TEMPLATES}
     * UPDATE.
     */
    @PutMapping
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<DocumentTemplateDto> save(@RequestBody TemplateSaveInput input) {
        return ResponseEntity.ok(service.save(input));
    }

    /**
     * Activates a template version (one-active-per-(type, locale), R3.2). {@code DOCUMENT_TEMPLATES}
     * UPDATE.
     */
    @PostMapping("/{id}/activate")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<DocumentTemplateDto> activate(@PathVariable Long id) {
        return ResponseEntity.ok(service.activate(id));
    }

    /** Deactivates a template version — the retire path (R2.5, R3a.1). {@code DOCUMENT_TEMPLATES} UPDATE. */
    @PostMapping("/{id}/deactivate")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<DocumentTemplateDto> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(service.deactivate(id));
    }

    /**
     * The test-merge preview: rendered body + the deterministic unresolved-placeholder list (R3a.4).
     * {@code DOCUMENT_TEMPLATES} READ.
     */
    @GetMapping("/{id}/test-merge")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<TestMergeResultDto> testMerge(@PathVariable Long id) {
        return ResponseEntity.ok(service.testMerge(id));
    }
}
