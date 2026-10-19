package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.payment.PaymentTransactionService;
import com.foremen.service.payment.provider.BankFeedProvider;
import com.foremen.service.payment.provider.IncomingBankTxn;

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-07 (Requirements 8.1, 8.2, 8.3, 8.4, 11.4; design §BankFeedIngestController) — the internal
 * trigger/poll endpoint for the mass-collect bank incoming-transaction feed.
 *
 * <p>Ingest is a two-step composition of the already-built collaborators: it pulls the available
 * incoming lines from the config-selected {@link BankFeedProvider#poll()} and feeds each one through
 * {@link PaymentTransactionService#ingestBankTransaction(IncomingBankTxn)}. The transaction service
 * owns all the business rules — a correlated line becomes a {@code BANK_TRANSFER}/{@code CONFIRMED}
 * transaction that is reconciled (R8.3), idempotent by {@code externalRef} so a re-poll of the same
 * line is a no-op (R5.8), and an uncorrelated line is recorded <b>unmatched</b> without failing
 * ingest (R8.4). This controller adds no business logic; it only drives the loop. Under the default
 * {@link com.foremen.service.payment.provider.NoOpBankFeedProvider No-Op provider} the feed is empty
 * and the trigger is a harmless no-op (R8.2).
 *
 * <p><b>ABAC (entity-creation-rules step 3; R11.4).</b> The class carries the class-level
 * {@link PermissionResource @PermissionResource("PAYMENTS")} and the sole handler a method-level
 * {@link PermissionOperation @PermissionOperation("UPDATE")} — ingesting payments mutates schedule
 * state, so it resolves to the {@code (PAYMENTS, UPDATE)} pair the {@code PermissionInterceptor}
 * enforces. The pair is complete, so the startup {@code PermissionAnnotationValidator} classifies the
 * handler {@code COMPLETE} and the application starts clean (R11.4). The {@code PAYMENTS} resource row
 * and its role grants are seeded by changeset 175 (task 7.1). A distinct base path
 * ({@code /api/payments/bank-feed}) keeps this controller clear of the sibling
 * {@code PaymentScheduleController}, which shares the same {@code PAYMENTS} resource.
 */
@RestController
@RequestMapping("/api/payments/bank-feed")
@RequiredArgsConstructor
@PermissionResource("PAYMENTS")
public class BankFeedIngestController {

    private final BankFeedProvider bankFeedProvider;
    private final PaymentTransactionService paymentTransactionService;

    /**
     * Triggers a bank-feed poll-and-ingest pass (R8.1, R8.3): polls {@link BankFeedProvider#poll()}
     * and feeds every returned line through
     * {@link PaymentTransactionService#ingestBankTransaction(IncomingBankTxn)}. Each line is handled
     * idempotently by {@code externalRef} (R5.8) and an uncorrelated line is recorded unmatched
     * without aborting the pass (R8.4); under the No-Op provider the feed is empty so nothing changes
     * (R8.2). Resolves to {@code PAYMENTS}/{@code UPDATE}.
     *
     * @return {@code 200 OK} with the number of lines pulled from the feed in this pass
     */
    @PostMapping("/ingest")
    @PermissionOperation("UPDATE")
    public ResponseEntity<BankFeedIngestResult> ingest() {
        List<IncomingBankTxn> lines = bankFeedProvider.poll();
        if (lines != null) {
            for (IncomingBankTxn line : lines) {
                paymentTransactionService.ingestBankTransaction(line);
            }
        }
        int polled = lines == null ? 0 : lines.size();
        return ResponseEntity.ok(new BankFeedIngestResult(polled));
    }

    /**
     * The result of one bank-feed ingest pass.
     *
     * @param polled the number of incoming lines pulled from the feed and fed through ingest in this
     *               pass (0 under the No-Op provider)
     */
    public record BankFeedIngestResult(int polled) {
    }
}
