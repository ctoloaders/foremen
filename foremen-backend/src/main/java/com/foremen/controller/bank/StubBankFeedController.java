package com.foremen.controller.bank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connector.stub.StubDataStore;
import com.foremen.service.bank.connector.stub.StubSeed;

import lombok.RequiredArgsConstructor;

/**
 * FOR-07-04 (Requirements 4.4, 21.5) — dev/test-only seeding of the {@code STUB} connector:
 * {@code POST /api/bank/stub/connections/{id}/data} with a {@link StubSeed} body ({@code REPLACE} by
 * default, {@code APPEND} upserts) → {@code 204}. Requires {@code BANK_CONNECTIONS}/UPDATE.
 *
 * <p>The bean exists only when {@code foremen.bank-feed.stub.enabled = true} — the same condition as
 * {@code StubConnectorConfig} — so the endpoint is absent (404) in every other environment. Unknown
 * connection → {@code 404}; a non-{@code STUB} connection → {@code 409 error.bank.stub.notStubConnection};
 * a seed without the required ids → {@code 400 error.bank.stub.seedInvalid}.
 */
@RestController
@RequestMapping("/api/bank/stub")
@RequiredArgsConstructor
@PermissionResource("BANK_CONNECTIONS")
@ConditionalOnProperty(prefix = "foremen.bank-feed.stub", name = "enabled", havingValue = "true")
public class StubBankFeedController {

    public static final String NOT_STUB_CONNECTION = "error.bank.stub.notStubConnection";
    public static final String SEED_INVALID = "error.bank.stub.seedInvalid";

    private final StubDataStore stubDataStore;
    private final BankConnectionService connectionService;

    @PostMapping("/connections/{id}/data")
    @PermissionOperation("UPDATE")
    public ResponseEntity<Void> seed(@PathVariable Long id, @RequestBody StubSeed seed) {
        BankConnectionEntity connection = connectionService.get(id);
        if (connection.getProvider() != FeedProvider.STUB) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_STUB_CONNECTION);
        }
        StubSeed body = seed == null ? new StubSeed(null, null, null, null, null, null) : seed;
        try {
            stubDataStore.seed(connection.getId(), body);
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SEED_INVALID);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
