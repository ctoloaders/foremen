package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.document.SignableDocumentTypeDto;
import com.foremen.service.document.SignableDocumentTypeInput;
import com.foremen.service.document.SignableDocumentTypeService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 2.4, 2.5, 8.7, 13.1; design §Backend controllers): the ADMIN-only, GLOBAL
 * (not project-scoped) CRUD + activate/deactivate surface over the {@code SignableDocumentType}
 * catalog, layered on the {@code SIGNABLE_DOCUMENT_TYPES} ABAC resource (seeded by changeset 146).
 *
 * <h2>ABAC guarding (entity-creation-rules step 3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("SIGNABLE_DOCUMENT_TYPES")}; every
 * handler carries a method-level {@link RequiresPermission @RequiresPermission} mapping to standard
 * CRUD (mirroring {@link NotificationController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup (R8.7, R2.4):
 * <ul>
 *   <li>{@code GET /}, {@code GET /{id}} — list / read the catalog, READ.</li>
 *   <li>{@code POST /} — create a type, CREATE.</li>
 *   <li>{@code PUT /{id}} — update a type's names / default level, UPDATE.</li>
 *   <li>{@code POST /{id}/activate}, {@code POST /{id}/deactivate} — flip the active flag, UPDATE
 *       (a type is deactivated, never deleted, R2.5).</li>
 * </ul>
 * There is deliberately <b>no DELETE endpoint</b>: a type is retired by deactivation (R2.5).
 */
@RestController
@RequestMapping("/api/signable-document-types")
@RequiredArgsConstructor
@PermissionResource("SIGNABLE_DOCUMENT_TYPES")
public class SignableDocumentTypeController {

    private static final String RESOURCE = "SIGNABLE_DOCUMENT_TYPES";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";

    private final SignableDocumentTypeService service;

    /** Lists the type catalog (R2.3). {@code SIGNABLE_DOCUMENT_TYPES} READ. */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<List<SignableDocumentTypeDto>> list() {
        return ResponseEntity.ok(service.list());
    }

    /** Reads one type by id (R2.1). {@code SIGNABLE_DOCUMENT_TYPES} READ. */
    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<SignableDocumentTypeDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id));
    }

    /** Creates a new type (R2.4). {@code SIGNABLE_DOCUMENT_TYPES} CREATE. */
    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<SignableDocumentTypeDto> create(@RequestBody SignableDocumentTypeInput input) {
        return ResponseEntity.ok(service.create(input));
    }

    /** Updates a type's display names / default level (R2.4). {@code SIGNABLE_DOCUMENT_TYPES} UPDATE. */
    @PutMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentTypeDto> update(
            @PathVariable Long id,
            @RequestBody SignableDocumentTypeInput input) {
        return ResponseEntity.ok(service.update(id, input));
    }

    /** Activates a type (R2.5). {@code SIGNABLE_DOCUMENT_TYPES} UPDATE. */
    @PostMapping("/{id}/activate")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentTypeDto> activate(@PathVariable Long id) {
        return ResponseEntity.ok(service.activate(id));
    }

    /** Deactivates a type — the retire path (R2.5). {@code SIGNABLE_DOCUMENT_TYPES} UPDATE. */
    @PostMapping("/{id}/deactivate")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<SignableDocumentTypeDto> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(service.deactivate(id));
    }
}
