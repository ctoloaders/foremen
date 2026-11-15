package com.foremen.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.client.ClientProformaClientDto;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.proforma.ClientProformaClientService;
import com.foremen.service.procurement.proforma.ClientProformaClientService.StoredPdf;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-03 client surface of proformas (R6.1, R6.2, R6.3, R1.6).
 *
 * <p>ABAC: no class-level {@code @PermissionResource}; every handler carries an explicit
 * {@link RequiresPermission} ({@code CLIENT_PROFORMAS} READ). Only a caller with {@code ROLE_CLIENT} who
 * is an ACTIVE CLIENT member of the proforma's project gets data; everyone else gets 404, so existence
 * of a proforma never leaks. Only {@link ClientProformaClientDto} is ever returned.
 */
@RestController
@RequestMapping("/api/client-proformas/client")
@RequiredArgsConstructor
public class ClientProformaClientController {

    private static final String RESOURCE = "CLIENT_PROFORMAS";
    private static final String READ = "READ";
    private static final String ROLE_CLIENT = "ROLE_CLIENT";

    private final ClientProformaClientService service;

    @GetMapping("/projects/{projectId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Client view of the issued proformas of a project")
    public ResponseEntity<List<ClientProformaClientDto>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(service.list(projectId, clientUserId()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Client view of one proforma")
    public ResponseEntity<ClientProformaClientDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(service.get(id, clientUserId()));
    }

    @GetMapping("/{id}/pdf")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Stored PDF of a proforma")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        StoredPdf pdf = service.pdf(id, clientUserId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.bytes());
    }

    /** Role re-check: a non-CLIENT caller is indistinguishable from a non-member (404). */
    private static Long clientUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ROLE_CLIENT.equals(ga.getAuthority())) {
                return userId;
            }
        }
        throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found");
    }
}
