package com.foremen.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.RequiresPermission;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.ClientProformaServiceModel;
import com.foremen.service.procurement.proforma.ClientProformaService;
import com.foremen.service.procurement.proforma.ProformaCancelService;
import com.foremen.service.procurement.proforma.ProformaIssueService;
import com.foremen.service.procurement.proforma.ProformaPdfService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * FOR-11-03 staff surface of client proformas (R3, R4, R5, R6.5, R1.6).
 *
 * <p>ABAC: no class-level {@code @PermissionResource}; every handler has an explicit
 * {@link RequiresPermission} on {@code CLIENT_PROFORMAS} (READ for reads and the PDF, CREATE for the
 * draft, UPDATE for draft edits, issue and cancel, DELETE for deleting a draft). A CLIENT holds READ, so
 * it reaches the READ handlers; each handler re-checks the role and answers 403 (R6.5). Project
 * confinement is enforced inside {@link ClientProformaService}.
 */
@RestController
@RequestMapping("/api/client-proformas")
@RequiredArgsConstructor
public class ClientProformaController {

    private static final String RESOURCE = "CLIENT_PROFORMAS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String ROLE_CLIENT = "ROLE_CLIENT";

    private final ClientProformaService service;
    private final ProformaIssueService issueService;
    private final ProformaCancelService cancelService;
    private final ProformaPdfService pdfService;
    private final ClientProformaDao dao;

    // ------------------------------------------------------------------ reads

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "List client proformas (staff)")
    public ResponseEntity<Page<ClientProformaServiceModel>> find(
            Pageable pageable, @RequestParam(name = "query", required = false) String query) {
        rejectClient();
        return ResponseEntity.ok(service.find(pageable, query));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "One client proforma with its lines (staff)")
    public ResponseEntity<ClientProformaServiceExtendedModel> findById(@PathVariable Long id) {
        rejectClient();
        return ResponseEntity.ok(service.findById(id));
    }

    @GetMapping("/{id}/pdf")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    @Operation(summary = "Stored PDF of an issued proforma (staff)")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        rejectClient();
        service.assertProjectAccess(id);
        ClientProformaEntity proforma = dao.findById(id).orElseThrow(() -> new ForemenApiException(
                HttpStatus.NOT_FOUND, "error.entity.not.found", id));
        byte[] bytes = pdfService.read(proforma);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + ProformaPdfService.fileName(proforma.getNumber()) + "\"")
                .body(bytes);
    }

    // ------------------------------------------------------------------ draft

    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    @Operation(summary = "Create a draft proforma from accepted positions")
    public ResponseEntity<ClientProformaServiceExtendedModel> create(
            @Valid @RequestBody CreateRequest request) {
        rejectClient();
        ClientProformaServiceExtendedModel model = new ClientProformaServiceExtendedModel();
        model.setProjectId(request.projectId());
        model.setPositionIds(request.positionIds());
        model.setDueDate(request.dueDate());
        model.setNote(request.note());
        return ResponseEntity.ok(service.create(model));
    }

    @PutMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Update a draft: due date, note and the set of positions")
    public ResponseEntity<ClientProformaServiceExtendedModel> update(
            @PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        rejectClient();
        ClientProformaServiceExtendedModel model = new ClientProformaServiceExtendedModel();
        model.setPositionIds(request.positionIds());
        model.setDueDate(request.dueDate());
        model.setNote(request.note());
        return ResponseEntity.ok(service.update(id, model));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = DELETE)
    @Operation(summary = "Delete a draft proforma")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        rejectClient();
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ issue and cancel

    @PostMapping("/{id}/issue")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Issue a draft: number, frozen prices, PDF, positions to IN_PROFORMA")
    public ResponseEntity<ClientProformaServiceExtendedModel> issue(
            @PathVariable Long id, @Valid @RequestBody(required = false) IssueRequest request) {
        rejectClient();
        return ResponseEntity.ok(issueService.issue(id, request == null ? null : request.language()));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(resource = RESOURCE, operation = UPDATE)
    @Operation(summary = "Cancel a draft or an unpaid issued proforma")
    public ResponseEntity<ClientProformaServiceExtendedModel> cancel(
            @PathVariable Long id, @Valid @RequestBody(required = false) CancelRequest request) {
        rejectClient();
        return ResponseEntity.ok(cancelService.cancel(id, request == null ? null : request.reason()));
    }

    // ------------------------------------------------------------------ requests

    public record CreateRequest(@NotNull Long projectId, @NotEmpty @Size(max = 200) List<Long> positionIds,
                                LocalDate dueDate, @Size(max = 1000) String note) {
    }

    public record UpdateRequest(@Size(max = 200) List<Long> positionIds, LocalDate dueDate,
                                @Size(max = 1000) String note) {
    }

    public record IssueRequest(@Size(max = 2) String language) {
    }

    public record CancelRequest(@Size(max = 500) String reason) {
    }

    // ------------------------------------------------------------------ helpers

    /** R6.5: the staff endpoints refuse a CLIENT even though it holds READ on the resource. */
    private static void rejectClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ROLE_CLIENT.equals(ga.getAuthority())) {
                throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
            }
        }
    }
}
