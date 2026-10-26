package com.foremen.controller;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.model.companyschedule.CompanyScheduleQuery;
import com.foremen.controller.model.companyschedule.CompanyScheduleView;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.CrossProjectScheduleService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-01 (Requirements 7, 9, 11; design §"Controller endpoint") — the single,
 * <strong>read-only</strong> REST endpoint behind the cross-project staffing projection (the
 * company-wide staffing Gantt), exposed as {@code GET /api/company-schedule}. The class carries the
 * ABAC resource via a class-level {@link PermissionResource @PermissionResource("COMPANY_SCHEDULE")};
 * the single handler carries a method-level {@link PermissionOperation @PermissionOperation("READ")}.
 * The {@code PermissionResolver} combines the two into the {@code (COMPANY_SCHEDULE, READ)} pair
 * enforced at runtime by the {@code PermissionInterceptor} (ADMIN bypass included) and validated at
 * startup by {@code PermissionAnnotationValidator} — a controller that carried
 * {@code @PermissionResource} but left its in-scope handler without a matching
 * {@code @PermissionOperation} would fail startup (entity-creation-rules step 3). The
 * {@code COMPANY_SCHEDULE} resource and its ADMIN / MANAGER READ grants are seeded by changeset 187.
 *
 * <p><strong>Query surface (Requirements 7.2–7.5, 11.3, 11.4).</strong> The complete, deliberate set
 * of query parameters is exactly {@code roles} / {@code projectIds} / {@code memberIds} / {@code from}
 * / {@code to}, all optional. This is a Gantt (rows = people, X = dates), not a paginated / server
 * sorted DataTable, so there is <em>no</em> {@code page} / {@code size} / {@code sort} / {@code query}
 * parameter; the projection's ordering is the fixed deterministic order of the service / projector
 * (Requirement 11.4). Because these parameters are precisely the dimensions a persisted Gantt view
 * needs to be replayed, the saved view is fully re-playable through this read contract
 * (Requirement 11.1).
 *
 * <p><strong>Date_Window guard (Requirement 7.8).</strong> A {@code from > to} window is rejected with
 * HTTP 400 {@code error.company.schedule.window.invalid} <em>before any read</em>, so a malformed
 * window changes no data. All other scope / filter semantics are delegated to the
 * {@link CrossProjectScheduleService}, which reads under a read-only transaction (Requirement 7.9).
 *
 * <p>There is no CREATE / UPDATE / DELETE handler; {@code COMPANY_SCHEDULE} is a read-only resource
 * (the seed grants READ only).
 *
 * <p>Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.8, 9.4, 11.1, 11.3, 11.4
 */
@RestController
@RequestMapping("/api/company-schedule")
@RequiredArgsConstructor
@PermissionResource("COMPANY_SCHEDULE")
public class CrossProjectScheduleController {

    private static final String READ = "READ";

    private final CrossProjectScheduleService crossProjectScheduleService;

    /**
     * Reads the cross-project staffing projection for the authenticated caller within the supplied
     * filters (Requirement 7.1). The filters — Role_Filter ({@code roles}), Project_Filter
     * ({@code projectIds}), Member_Filter ({@code memberIds}), and the inclusive Date_Window
     * ({@code from} / {@code to}) — are all optional; an omitted dimension applies no restriction (see
     * {@link CompanyScheduleQuery}). A {@code from > to} window is rejected with HTTP 400
     * {@code error.company.schedule.window.invalid} before any read (Requirement 7.8). Scope
     * resolution (exact-authority ADMIN bypass, else the caller's accessible projects) and all
     * aggregation are performed by the service. {@code COMPANY_SCHEDULE} READ.
     */
    @GetMapping
    @PermissionOperation(READ)
    public ResponseEntity<CompanyScheduleView> getProjection(
            @RequestParam(required = false) List<String> roles,
            @RequestParam(required = false) List<Long> projectIds,
            @RequestParam(required = false) List<Long> memberIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            // Requirement 7.8: reject an inverted Date_Window before any read; changes no data.
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.company.schedule.window.invalid");
        }
        CompanyScheduleQuery query = new CompanyScheduleQuery(roles, projectIds, memberIds, from, to);
        return ResponseEntity.ok(crossProjectScheduleService.getProjection(query));
    }
}
