package com.foremen.controller.bank;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.bank.dto.BankAccountDto;
import com.foremen.controller.bank.dto.BankAdminRequests;
import com.foremen.controller.bank.dto.BankConnectionDto;
import com.foremen.controller.bank.dto.ConnectionResponses;
import com.foremen.dao.model.BankAccountEntity;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connection.BankConnectionService.AuthorizationOutcome;
import com.foremen.service.bank.connection.BankConnectionService.CompletionOutcome;
import com.foremen.service.bank.connection.BankConnectionService.RefreshOutcome;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.secret.BankConnectionSecretService;
import com.foremen.service.bank.secret.BankConnectionSecretService.CredentialsStatus;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 1.3–1.8, 2.3, 5.1, 19.3, 19.4, 21.1, 21.3, 21.6) — bank connection lifecycle.
 *
 * <h2>Endpoints ({@code BANK_CONNECTIONS})</h2>
 * <ul>
 *   <li>{@code GET /api/bank/connections} (READ) — list, oldest first, with accounts count and
 *       credential status;</li>
 *   <li>{@code GET /api/bank/connections/{id}} (READ) — detail incl. bank accounts;</li>
 *   <li>{@code POST /api/bank/connections} (CREATE) → {@code 201 {connection, authorizationUrl}};</li>
 *   <li>{@code POST /{id}/authorize} (UPDATE) — continue a {@code PENDING_AUTH} authorization;</li>
 *   <li>{@code POST /{id}/complete} (UPDATE) — {@code {params}} from the return route;</li>
 *   <li>{@code POST /{id}/reconnect} (UPDATE) → {@code {connection, authorizationUrl}};</li>
 *   <li>{@code POST /{id}/refresh} (UPDATE) → {@code {authorizationUrl?, nextRefreshPossibleAt,
 *       syncRunId?}} or {@code 409 error.bank.refresh.notYetPossible};</li>
 *   <li>{@code POST /{id}/sync} (UPDATE) → {@code 202 {runId}};</li>
 *   <li>{@code PATCH /{id}} (UPDATE) — rename;</li>
 *   <li>{@code PUT /{id}/credentials} (UPDATE) — write-only credentials → {@code {credentialsSet,
 *       credentialsUpdatedAt}} (values are never returned, R2.3);</li>
 *   <li>{@code POST /{id}/disable} (UPDATE);</li>
 *   <li>{@code DELETE /{id}} (DELETE) → {@code 204}; {@code 409} unless disabled / pending and
 *       without links.</li>
 * </ul>
 * All business rules and {@code error.bank.*} codes live in {@link BankConnectionService} /
 * {@link BankConnectionSecretService}; this controller only maps to secret-free DTOs.
 */
@RestController
@RequestMapping("/api/bank/connections")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
public class BankConnectionController {

    private final BankConnectionService connectionService;
    private final BankConnectionSecretService secretService;
    private final BankAccountService accountService;
    private final BankFeedConnectorRegistry registry;

    // --- reads ---------------------------------------------------------------------------------

    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<List<BankConnectionDto>> list() {
        Map<Long, Long> counts = accountService.listBankAccounts(null).stream()
                .filter(a -> a.getConnection() != null)
                .collect(Collectors.groupingBy(a -> a.getConnection().getId(), Collectors.counting()));
        List<BankConnectionDto> out = connectionService.list().stream()
                .map(c -> toDto(c, counts.getOrDefault(c.getId(), 0L).intValue(), null))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<BankConnectionDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(detail(connectionService.get(id)));
    }

    // --- create / authorize / complete ---------------------------------------------------------

    @PostMapping
    @PermissionOperation("CREATE")
    public ResponseEntity<ConnectionResponses.Authorization> create(
            @RequestBody BankAdminRequests.CreateConnection request) {
        BankAdminRequests.CreateConnection body = request == null
                ? new BankAdminRequests.CreateConnection(null, null, null) : request;
        AuthorizationOutcome outcome = connectionService.create(body.provider(), body.providerBankCode(),
                body.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(authorization(outcome));
    }

    @PostMapping("/{id}/authorize")
    @PermissionOperation("UPDATE")
    public ResponseEntity<ConnectionResponses.Authorization> authorize(@PathVariable Long id) {
        return ResponseEntity.ok(authorization(connectionService.continueAuthorization(id)));
    }

    @PostMapping("/{id}/complete")
    @PermissionOperation("UPDATE")
    public ResponseEntity<ConnectionResponses.Completion> complete(
            @PathVariable Long id, @RequestBody(required = false) BankAdminRequests.CompleteConnection request) {
        Map<String, String> params = request == null || request.params() == null ? Map.of() : request.params();
        CompletionOutcome outcome = connectionService.complete(id, params);
        return ResponseEntity.ok(new ConnectionResponses.Completion(detail(outcome.connection()),
                outcome.completed(), outcome.syncRunId() == null ? null : outcome.syncRunId().orElse(null)));
    }

    // --- lifecycle -----------------------------------------------------------------------------

    @PostMapping("/{id}/reconnect")
    @PermissionOperation("UPDATE")
    public ResponseEntity<ConnectionResponses.Authorization> reconnect(@PathVariable Long id) {
        return ResponseEntity.ok(authorization(connectionService.reconnect(id)));
    }

    @PostMapping("/{id}/refresh")
    @PermissionOperation("UPDATE")
    public ResponseEntity<ConnectionResponses.Refresh> refresh(@PathVariable Long id) {
        RefreshOutcome outcome = connectionService.refresh(id);
        return ResponseEntity.ok(new ConnectionResponses.Refresh(detail(outcome.connection()),
                url(outcome.authorizationUrl()), outcome.nextRefreshPossibleAt(),
                outcome.syncRunId() == null ? null : outcome.syncRunId().orElse(null)));
    }

    @PostMapping("/{id}/sync")
    @PermissionOperation("UPDATE")
    public ResponseEntity<ConnectionResponses.SyncRequested> sync(@PathVariable Long id) {
        Long runId = connectionService.requestSync(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ConnectionResponses.SyncRequested(runId));
    }

    @PatchMapping("/{id}")
    @PermissionOperation("UPDATE")
    public ResponseEntity<BankConnectionDto> rename(
            @PathVariable Long id, @RequestBody(required = false) BankAdminRequests.RenameConnection request) {
        return ResponseEntity.ok(detail(connectionService.rename(id, request == null ? null : request.displayName())));
    }

    @PutMapping("/{id}/credentials")
    @PermissionOperation("UPDATE")
    public ResponseEntity<CredentialsStatus> replaceCredentials(
            @PathVariable Long id, @RequestBody(required = false) BankAdminRequests.Credentials request) {
        Map<String, String> values = request == null || request.values() == null ? Map.of() : request.values();
        return ResponseEntity.ok(secretService.replace(id, values));
    }

    @PostMapping("/{id}/disable")
    @PermissionOperation("UPDATE")
    public ResponseEntity<BankConnectionDto> disable(@PathVariable Long id) {
        return ResponseEntity.ok(detail(connectionService.disable(id)));
    }

    @DeleteMapping("/{id}")
    @PermissionOperation("DELETE")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        connectionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- mapping -------------------------------------------------------------------------------

    private ConnectionResponses.Authorization authorization(AuthorizationOutcome outcome) {
        return new ConnectionResponses.Authorization(detail(outcome.connection()), url(outcome.authorizationUrl()));
    }

    private BankConnectionDto detail(BankConnectionEntity connection) {
        List<BankAccountEntity> accounts = connection.getId() == null
                ? List.of() : accountService.listBankAccounts(connection.getId());
        List<BankAccountDto> accountDtos = accounts.stream()
                .map(a -> BankAdminDtoMapper.bankAccount(a, connection))
                .toList();
        return toDto(connection, accountDtos.size(), accountDtos);
    }

    private BankConnectionDto toDto(BankConnectionEntity connection, int accountsCount, List<BankAccountDto> accounts) {
        boolean credentialsRequired = registry.find(connection.getProvider())
                .map(c -> c.credentialFields() != null && !c.credentialFields().isEmpty())
                .orElse(false);
        CredentialsStatus credentials = connection.getId() == null
                ? CredentialsStatus.NOT_SET : secretService.status(connection.getId());
        return BankAdminDtoMapper.connection(connection, accountsCount, credentialsRequired, credentials, accounts);
    }

    private static String url(URI uri) {
        return uri == null ? null : uri.toString();
    }
}
