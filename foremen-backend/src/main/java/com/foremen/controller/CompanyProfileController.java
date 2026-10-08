package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.document.CompanyProfileDto;
import com.foremen.service.document.CompanyProfileInput;
import com.foremen.service.document.CompanyProfileService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 3.3, 8.7, 13.1; design §Backend controllers, decision 7): the ADMIN-only,
 * GLOBAL (not project-scoped) requisites surface over the single {@code CompanyProfile} row — the
 * executor ({@code Wykonawca}) requisites read by {@code CompanyRequisitesResolver} during a merge —
 * layered on the {@code COMPANY_PROFILE} ABAC resource (seeded by changeset 148).
 *
 * <h2>ABAC guarding (entity-creation-rules step 3)</h2>
 * The class carries {@link PermissionResource @PermissionResource("COMPANY_PROFILE")}; every handler
 * carries a method-level {@link RequiresPermission @RequiresPermission} mapping to standard CRUD
 * (mirroring {@link NotificationController}), so the controller is <b>fully annotated</b> and
 * {@code PermissionAnnotationValidator} classifies it COMPLETE at startup (R8.7):
 * <ul>
 *   <li>{@code GET /} — read the single profile (empty-valued when none saved), READ.</li>
 *   <li>{@code PUT /} — upsert the single profile row, UPDATE.</li>
 * </ul>
 * There is deliberately no separate CREATE / DELETE endpoint: the profile is a single row the
 * {@code PUT} upserts.
 */
@RestController
@RequestMapping("/api/company-profile")
@RequiredArgsConstructor
@PermissionResource("COMPANY_PROFILE")
public class CompanyProfileController {

    private static final String RESOURCE = "COMPANY_PROFILE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";

    private final CompanyProfileService service;

    /** Reads the single company profile (R3.3). {@code COMPANY_PROFILE} READ. */
    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<CompanyProfileDto> get() {
        return ResponseEntity.ok(service.get());
    }

    /** Upserts the single company profile row (R3.3). {@code COMPANY_PROFILE} UPDATE. */
    @PutMapping
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    public ResponseEntity<CompanyProfileDto> save(@RequestBody CompanyProfileInput input) {
        return ResponseEntity.ok(service.save(input));
    }
}
