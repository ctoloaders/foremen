package com.foremen.controller.bank;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.dao.model.SyncRunStatus;
import com.foremen.service.bank.sync.BankSyncRunDto;
import com.foremen.service.bank.sync.BankSyncRunQueryService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 5.1, 19.6, 21.1, 21.3) — read-only sync history.
 *
 * <ul>
 *   <li>{@code GET /api/bank/sync-runs?connectionId&status&from&to&page&size&sort} (READ) — pageable,
 *       newest first by default; {@code status} may repeat; {@code from > to} → {@code 422
 *       error.bank.syncRun.periodInvalid};</li>
 *   <li>{@code GET /api/bank/sync-runs/{id}} (READ) — run detail with the per-account breakdown.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bank/sync-runs")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
public class BankSyncRunController {

    private final BankSyncRunQueryService queryService;

    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<Page<BankSyncRunDto>> list(
            @RequestParam(required = false) Long connectionId,
            @RequestParam(required = false) List<SyncRunStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(queryService.list(connectionId, status, from, to, pageable));
    }

    @GetMapping("/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<BankSyncRunDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.get(id));
    }
}
